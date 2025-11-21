package io.singularitynet.tests;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.IntFunction;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.nio.file.*;
import java.util.Locale;
import org.json.JSONObject;

/**
 * High-level connector for tests: handles mission XML patching, port selection,
 * waiting for ports from Minecraft logs, and starting small TCP helpers
 * (control, observations, rewards). Tests should use this instead of duplicating
 * networking/XML glue in each main class.
 */
public final class MConnector {
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(MConnector.class.getName());

    private final String missionXmlOriginal;
    private final int chosenColourPort;
    private final int chosenAgentCtrlPort;
    private final int chosenObsPort;
    private final int chosenRewPort;
    private final int chosenCmdPort;
    private final int chosenVideoPort;
    private final String missionXmlPatched;

    private Thread ctrlThread;
    private Thread obsThread;
    private Thread rewThread;

    public MConnector(String missionXml) throws IOException {
        this.missionXmlOriginal = missionXml;
        this.chosenColourPort = TestUtils.findFreePort();
        this.chosenAgentCtrlPort = TestUtils.findFreePort();
        this.chosenObsPort = TestUtils.findFreePort();
        this.chosenRewPort = TestUtils.findFreePort();
        this.chosenCmdPort = TestUtils.findFreePort();
        this.chosenVideoPort = TestUtils.findFreePort();
        String patched = missionXml;
        patched = TestUtils.patchAgentColourMapPort(patched, this.chosenColourPort);
        patched = TestUtils.patchAgentMissionControlPort(patched, this.chosenAgentCtrlPort);
        patched = TestUtils.patchAgentObservationsPort(patched, this.chosenObsPort);
        patched = TestUtils.patchAgentRewardsPort(patched, this.chosenRewPort);
        patched = TestUtils.patchClientCommandsPort(patched, this.chosenCmdPort);
        patched = TestUtils.patchAgentVideoPort(patched, this.chosenVideoPort);
        patched = TestUtils.ensureVideoProducer(patched);
        patched = TestUtils.ensureColourMapRespectOpacity(patched);
        this.missionXmlPatched = patched;
        LOG.info("MConnector ports: colour=" + chosenColourPort
                + ", ctrl=" + chosenAgentCtrlPort
                + ", obs=" + chosenObsPort
                + ", rew=" + chosenRewPort
                + ", cmd=" + chosenCmdPort
                + ", video=" + chosenVideoPort);
    }

    public String getPatchedMissionXml() { return missionXmlPatched; }
    public int getChosenColourPort() { return chosenColourPort; }
    public int getChosenCtrlPort() { return chosenAgentCtrlPort; }
    public int getChosenObsPort() { return chosenObsPort; }
    public int getChosenRewPort() { return chosenRewPort; }
    public int getChosenCmdPort() { return chosenCmdPort; }
    public int getChosenVideoPort() { return chosenVideoPort; }

    /** Start lightweight TCP servers used by the mod during tests. */
    public void startServers() {
        TestUtils.ControlServer ctrlServer = new TestUtils.ControlServer(chosenAgentCtrlPort);
        ctrlThread = new Thread(ctrlServer, "AgentControlServer");
        ctrlThread.setDaemon(true);
        ctrlThread.start();

        TestUtils.ObservationsServer obsServer = new TestUtils.ObservationsServer(chosenObsPort);
        obsThread = new Thread(obsServer, "ObsServer");
        obsThread.setDaemon(true);
        obsThread.start();

        TestUtils.DrainServer rewServer = new TestUtils.DrainServer(chosenRewPort, "rew");
        rewThread = new Thread(rewServer, "RewServer");
        rewThread.setDaemon(true);
        rewThread.start();
        LOG.info("Started servers: ctrl=" + chosenAgentCtrlPort + ", obs=" + chosenObsPort + ", rew=" + chosenRewPort);
    }

    /** Start a video receiver server to save normal frames to images/video. */
    public Thread startVideoServer() {
        VideoReceiver vr = new VideoReceiver(this.chosenVideoPort);
        Thread t = new Thread(vr, "VideoServer");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** Parse MissionControlPort (MCP) from Minecraft stdout. */
    public int waitForMissionControlPort(InputStream stdout, Duration timeout) throws IOException {
        return TestUtils.waitForMissionControlPort(stdout, timeout);
    }

    /** Parse ports echoed back from MissionInit. */
    public TestUtils.Ports waitForAgentPorts(InputStream stdout, Duration timeout) throws IOException {
        return TestUtils.waitForAgentPorts(stdout, timeout);
    }

    /** Fallback to chosen colour port if parsing failed. */
    public int resolveColourPort(TestUtils.Ports ports) {
        return (ports != null && ports.colourPort > 0) ? ports.colourPort : chosenColourPort;
    }

    /** Fallback to chosen commands port if parsing failed. */
    public int resolveCmdPort(TestUtils.Ports ports) {
        return (ports != null && ports.comPort > 0) ? ports.comPort : chosenCmdPort;
    }

    /** Container for resolved ports and a started frame server thread. */
    public static class Resolved<T extends Runnable> {
        public final TestUtils.Ports ports; public final int colourPort; public final int cmdPort; public final T server; public final Thread thread;
        Resolved(TestUtils.Ports ports, int colourPort, int cmdPort, T server, Thread thread){ this.ports=ports; this.colourPort=colourPort; this.cmdPort=cmdPort; this.server=server; this.thread=thread; }
    }

    /**
     * Parse ports from logs, resolve effective colour/command ports, and start a FrameServer via the provided factory.
     * Returns the resolved ports and the started server/thread.
     */
    public <T extends Runnable> Resolved<T> startFrameServerFromLogs(InputStream stdout, Duration timeout,
                                                                     IntFunction<T> frameServerFactory,
                                                                     String threadName) throws IOException {
        TestUtils.Ports ports = waitForAgentPorts(stdout, timeout);
        int effectiveColourPort = resolveColourPort(ports);
        if (effectiveColourPort <= 0) {
            LOG.warning("Could not resolve AgentColourMapPort from logs; falling back to chosen=" + getChosenColourPort());
            effectiveColourPort = getChosenColourPort();
        } else {
            LOG.info("Parsed AgentColourMapPort=" + effectiveColourPort);
        }
        T server = frameServerFactory.apply(effectiveColourPort);
        Thread serverThread = new Thread(server, (threadName != null && !threadName.isEmpty()) ? threadName : "ColourMapServer");
        serverThread.setDaemon(true);
        serverThread.start();
        int cmd = resolveCmdPort(ports);
        return new Resolved<>(ports, effectiveColourPort, cmd, server, serverThread);
    }

    /**
     * Generic frame receiver used by both tests. Parses the COLOUR_MAP stream, tracks basic metrics,
     * persists optional PNGs/CSV, and exposes latest frame + header.
     */
    public static class FrameReceiver implements Runnable {
        private final int port;
        private final Path saveDir;
        private java.io.BufferedWriter csv;

        private volatile boolean stop = false;
        private final CountDownLatch firstFrameLatch = new CountDownLatch(1);
        private volatile int frameCount = 0;
        private volatile byte[] lastFrame;
        private volatile JSONObject lastHeader;

        private volatile boolean everNonBlack = false;
        private volatile int lastUniqueColors = 0, maxUniqueColors = 0;
        private volatile int lastEntityLike = 0, maxEntityLike = 0;

        private final java.util.List<Integer> uniqueHistory = new java.util.ArrayList<>();
        private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<Integer,Integer>> blockTypeToColourCounts = new java.util.concurrent.ConcurrentHashMap<>();
        private static int getIntEnvOrProp(String envKey, String propKey, int def) {
            String v = System.getenv(envKey);
            if (v == null || v.isEmpty()) v = System.getProperty(propKey);
            if (v == null || v.isEmpty()) return def;
            try { return Integer.parseInt(v.trim()); } catch (Exception ignored) { return def; }
        }

        public FrameReceiver(int port) {
            this.port = port;
            this.saveDir = initSaveDir();
            try {
                Path csvPath = this.saveDir.resolve("metrics.csv");
                this.csv = Files.newBufferedWriter(csvPath, java.nio.charset.StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                this.csv.write("frame,w,h,ch,uniq,uniq_max,entity_uniq,entity_uniq_max,center_r,center_g,center_b,ray_type,ray_distance,ray_dt_ms\n");
                this.csv.flush();
            } catch (IOException ignored) {}
        }

        private static Path initSaveDir() {
            String base = System.getProperty("seg.test.saveDir", "images/seg");
            Path d = Paths.get(base, String.format("run-%d", System.currentTimeMillis()/1000));
            try { Files.createDirectories(d); } catch (IOException ignored) {}
            return d;
        }

        @Override
        public void run() {
            try (java.net.ServerSocket ss = new java.net.ServerSocket(port)) {
                ss.setReuseAddress(true);
                while (!stop) {
                    try (java.net.Socket s = ss.accept()) {
                        s.setTcpNoDelay(true);
                        InputStream in = s.getInputStream();
                        while (!stop) {
                            int packetLen = TestUtils.readIntBE(in);
                            if (packetLen <= 0 || packetLen > 50_000_000) break;
                            byte[] payload = TestUtils.readFully(in, packetLen);
                            ByteBuffer pb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
                            if (pb.remaining() < 4) break;
                            int jsonLen = pb.getInt();
                            if (jsonLen < 0 || jsonLen > pb.remaining()) break;
                            byte[] jsonBytes = new byte[jsonLen];
                            pb.get(jsonBytes);
                            JSONObject hdr = new JSONObject(new String(jsonBytes, StandardCharsets.UTF_8));
                            int w = hdr.optInt("img_width", -1);
                            int h = hdr.optInt("img_height", -1);
                            int ch = hdr.optInt("img_ch", 3);
                            if (w <= 0 || h <= 0 || ch < 3 || ch > 4) break;
                            int expected = w * h * ch;
                            if (pb.remaining() < expected) break;
                            byte[] frame = new byte[expected];
                            pb.get(frame);

                            this.lastHeader = hdr;
                            this.lastFrame = frame;
                            this.frameCount++;
                            try {
                                if (!everNonBlack && isBufferNonBlack(frame, ch)) everNonBlack = true;
                                this.lastUniqueColors = computeUniqueColors(frame, ch, 4096);
                                this.uniqueHistory.add(this.lastUniqueColors);
                                if (this.lastUniqueColors > this.maxUniqueColors) this.maxUniqueColors = this.lastUniqueColors;
                                this.lastEntityLike = computeEntityLikeUniqueColors(frame, ch, 4096);
                                if (this.lastEntityLike > this.maxEntityLike) this.maxEntityLike = this.lastEntityLike;

                                // Update center-pixel mapping using latest observation ray type and distance
                                long frameTimeNs = System.nanoTime();
                                int cx = Math.max(0, Math.min(w - 1, w / 2));
                                int cy = Math.max(0, Math.min(h - 1, h / 2));
                                int off = (cy * w + cx) * ch;
                                int bC = frame[off] & 0xFF;
                                int gC = frame[off + 1] & 0xFF;
                                int rC = frame[off + 2] & 0xFF;
                                int rgbC = (rC << 16) | (gC << 8) | bC;
                                String rayType = TestUtils.ObservationsServer.getLatestRayType();
                                Double rayDist = TestUtils.ObservationsServer.getLatestRayDistance();
                                long obsNs = TestUtils.ObservationsServer.getLatestRayTimestampNs();
                                long dtMs = (obsNs == 0L) ? -1L : (frameTimeNs - obsNs) / 1_000_000L;
                                int maxSkewMs = getIntEnvOrProp("RUN_SEG_MAX_OBS_SKEW_MS", "seg.test.maxObsSkewMs", 200);
                                boolean fresh = (rayType != null && !rayType.isEmpty() && dtMs >= 0 && dtMs <= maxSkewMs);
                                if (fresh) {
                                    blockTypeToColourCounts.computeIfAbsent(rayType, k -> new java.util.concurrent.ConcurrentHashMap<>())
                                            .merge(rgbC, 1, Integer::sum);
                                } else {
                                    // discard stale mapping; keep rayType blank for CSV
                                    if (dtMs < 0) rayType = ""; else rayType = "";
                                }

                                try { savePng(frame, w, h, ch, this.frameCount); } catch (Throwable ignored) {}
                                try {
                                    if (csv != null) {
                                        csv.write(String.format(Locale.ROOT, "%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%s,%s,%d\n",
                                                this.frameCount, w, h, ch,
                                                this.lastUniqueColors, this.maxUniqueColors,
                                                this.lastEntityLike, this.maxEntityLike,
                                                rC, gC, bC,
                                                rayType == null ? "" : rayType,
                                                rayDist == null ? "" : String.format(Locale.ROOT, "%.3f", rayDist),
                                                dtMs));
                                        csv.flush();
                                    }
                                } catch (IOException ignored) {}

                                // Also log each observation sample concisely for debugging.
                                if (rayType != null && !rayType.isEmpty()) {
                                    java.util.logging.Logger.getLogger(MConnector.class.getName())
                                            .info(String.format(Locale.ROOT,
                                                    "OBS-RAY frame=%d type=%s dist=%s color=#%06X dtMs=%d",
                                                    this.frameCount,
                                                    rayType,
                                                    rayDist == null ? "" : String.format(Locale.ROOT, "%.3f", rayDist),
                                                    rgbC & 0xFFFFFF,
                                                    dtMs));
                                }
                            } catch (Throwable ignored) {}
                            firstFrameLatch.countDown();
                        }
                    } catch (Throwable t) {
                        // continue accepting
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private void savePng(byte[] bgr, int w, int h, int ch, int index) throws IOException {
            if (bgr == null || w <= 0 || h <= 0 || ch < 3) return;
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
            int stride = ch; int off = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int b = bgr[off] & 0xFF; int g = bgr[off + 1] & 0xFF; int r = bgr[off + 2] & 0xFF;
                    int rgb = (r << 16) | (g << 8) | b; img.setRGB(x, y, rgb); off += stride;
                }
            }
            String name = String.format("frame_%05d.png", index);
            Path out = saveDir.resolve(name);
            try (OutputStream os = Files.newOutputStream(out, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                javax.imageio.ImageIO.write(img, "PNG", os);
            }
        }

        public boolean awaitFirstFrame(long t, TimeUnit u) throws InterruptedException { return firstFrameLatch.await(t, u); }
        public boolean awaitFramesAtLeast(int additional, long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            int start = this.frameCount;
            while (System.nanoTime() < deadline) {
                if (this.frameCount - start >= additional) return true;
                Thread.sleep(50);
            }
            return this.frameCount - start >= additional;
        }

        public int getFrameCount() { return frameCount; }
        public JSONObject getLastHeader() { return lastHeader; }
        public byte[] getLastFrame() { return lastFrame; }
        public int getLastUniqueColors() { return lastUniqueColors; }
        public int getMaxUniqueColors() { return maxUniqueColors; }
        public int getLastEntityLikeUniqueColors() { return lastEntityLike; }
        public int getMaxEntityLikeUniqueColors() { return maxEntityLike; }
        public boolean isLastFrameNonBlack() { return isBufferNonBlack(lastFrame, lastHeader != null ? lastHeader.optInt("img_ch", 3) : 3); }
        public boolean isEverNonBlack() { return everNonBlack; }
        public java.util.Map<String, java.util.Map<Integer,Integer>> getBlockTypeToColourCounts() { return new java.util.HashMap<>(blockTypeToColourCounts); }
        /** Clears aggregated per-type colour counts to allow ignoring early frames. */
        public void resetTypeCounts() {
            blockTypeToColourCounts.clear();
        }
        public int getTailMinUnique(int tailFrames) {
            if (uniqueHistory.isEmpty() || tailFrames <= 0) return 0;
            int from = Math.max(0, uniqueHistory.size() - tailFrames);
            int min = Integer.MAX_VALUE;
            for (int i = from; i < uniqueHistory.size(); i++) min = Math.min(min, uniqueHistory.get(i));
            return min == Integer.MAX_VALUE ? 0 : min;
        }
        public int getTailAvgUnique(int tailFrames) {
            if (uniqueHistory.isEmpty() || tailFrames <= 0) return 0;
            int from = Math.max(0, uniqueHistory.size() - tailFrames);
            long sum = 0; int cnt = 0;
            for (int i = from; i < uniqueHistory.size(); i++) { sum += uniqueHistory.get(i); cnt++; }
            return cnt == 0 ? 0 : (int)Math.round(sum * 1.0 / cnt);
        }

        public String getLatestRayType() { return TestUtils.ObservationsServer.getLatestRayType(); }

        // low-level IO helpers deduplicated in TestUtils
        private static boolean isBufferNonBlack(byte[] fr, int ch) {
            if (fr == null || ch < 3) return false;
            int stride = ch; int pixels = fr.length / stride; int step = Math.max(1, pixels / 4096);
            for (int px = 0; px < pixels; px += step) {
                int off = px * stride; int b = fr[off] & 0xFF, g = fr[off+1] & 0xFF, r = fr[off+2] & 0xFF; if ((r|g|b) != 0) return true;
            }
            return false;
        }
        private static int computeUniqueColors(byte[] fr, int ch, int maxSamples) {
            int stride = ch, pixels = fr.length/stride, step = Math.max(1, pixels/Math.max(1,maxSamples));
            java.util.HashSet<Integer> uniq = new java.util.HashSet<>();
            for (int px = 0; px < pixels; px += step) {
                int off = px*stride; int b=fr[off]&0xFF, g=fr[off+1]&0xFF, r=fr[off+2]&0xFF; int rgb=(r<<16)|(g<<8)|b; uniq.add(rgb); if(uniq.size()>=maxSamples) break;
            }
            return uniq.size();
        }
        private static int computeEntityLikeUniqueColors(byte[] fr, int ch, int maxSamples) {
            int stride = ch, pixels = fr.length/stride, step = Math.max(1, pixels/Math.max(1,maxSamples));
            java.util.HashSet<Integer> uniq = new java.util.HashSet<>();
            for (int px = 0; px < pixels; px += step) {
                int off = px*stride; int b=fr[off]&0xFF, g=fr[off+1]&0xFF, r=fr[off+2]&0xFF; if(r<240 && g<240 && b<240) continue; int rgb=(r<<16)|(g<<8)|b; uniq.add(rgb); if(uniq.size()>=maxSamples) break;
            }
            return uniq.size();
        }
    }

    /** Simple receiver for normal VIDEO frames that saves PNGs under images/video. */
    public static class VideoReceiver implements Runnable {
        final int port;
        final Path saveDir;
        volatile boolean stop = false;
        public VideoReceiver(int port) {
            this.port = port;
            String base = System.getProperty("video.test.saveDir", "images/video");
            this.saveDir = Paths.get(base, String.format("run-%d", System.currentTimeMillis()/1000));
            try { Files.createDirectories(this.saveDir); } catch (IOException ignored) {}
        }
        @Override public void run() {
            try (ServerSocket ss = new ServerSocket(port)) {
                ss.setReuseAddress(true);
                while (!stop) {
                    try (Socket s = ss.accept()) {
                        s.setTcpNoDelay(true);
                        InputStream in = s.getInputStream();
                        int idx = 0;
                        while (!stop) {
                            int packetLen = TestUtils.readIntBE(in);
                            if (packetLen <= 0 || packetLen > 50_000_000) break;
                            byte[] payload = TestUtils.readFully(in, packetLen);
                            ByteBuffer pb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
                            if (pb.remaining() < 4) break;
                            int jsonLen = pb.getInt();
                            if (jsonLen < 0 || jsonLen > pb.remaining()) break;
                            byte[] jsonBytes = new byte[jsonLen]; pb.get(jsonBytes);
                            org.json.JSONObject hdr = new org.json.JSONObject(new String(jsonBytes, java.nio.charset.StandardCharsets.UTF_8));
                            int w = hdr.optInt("img_width", -1);
                            int h = hdr.optInt("img_height", -1);
                            int ch = hdr.optInt("img_ch", 4);
                            if (w <= 0 || h <= 0 || ch < 3 || ch > 4) break;
                            int expected = w * h * ch;
                            if (pb.remaining() < expected) break;
                            byte[] frame = new byte[expected]; pb.get(frame);
                            savePng(frame, w, h, ch, ++idx);
                        }
                    } catch (Throwable t) {
                        // continue accepting
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        private void savePng(byte[] bgra, int w, int h, int ch, int index) throws IOException {
            if (bgra == null || w <= 0 || h <= 0 || ch < 3) return;
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
            int stride = ch; int off = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int b = bgra[off] & 0xFF;
                    int g = bgra[off + 1] & 0xFF;
                    int r = bgra[off + 2] & 0xFF;
                    int rgb = (r << 16) | (g << 8) | b;
                    img.setRGB(x, y, rgb);
                    off += stride;
                }
            }
            Path out = saveDir.resolve(String.format("frame_%05d.png", index));
            try (OutputStream os = Files.newOutputStream(out, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                javax.imageio.ImageIO.write(img, "PNG", os);
            }
        }
    }

    /** Send MissionInit XML via MCP as a single line (TCPInputPoller expects newline-terminated). */
    public void sendMissionInit(String host, int mcpPort) throws IOException {
        try (Socket s = new Socket()) {
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(host, mcpPort), 5000);
            OutputStream out = s.getOutputStream();
            out.write(missionXmlPatched.getBytes(StandardCharsets.UTF_8));
            out.write('\n');
            out.flush();
        }
    }

    /** Create a thread that will connect to the commands port and send commands with delay. */
    public Thread createCmdSenderThread(String host, int cmdPort, String[] cmds, int delayMs, String name) {
        TestUtils.CmdSender sender = new TestUtils.CmdSender(host, cmdPort, cmds, delayMs);
        Thread t = new Thread(sender, name != null ? name : "CmdSender");
        t.setDaemon(true);
        return t;
    }

    /**
     * Convenience static for callers that do not have an instance but need a command-sender thread.
     */
    public static Thread createCmdThread(String host, int cmdPort, String[] cmds, int delayMs, String name) {
        TestUtils.CmdSender sender = new TestUtils.CmdSender(host, cmdPort, cmds, delayMs);
        Thread t = new Thread(sender, name != null ? name : "CmdSender");
        t.setDaemon(true);
        return t;
    }
}
