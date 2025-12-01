package io.singularitynet.tests;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.nio.file.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import org.json.JSONObject;

public final class MConnector {
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(MConnector.class.getName());

    private final String missionXmlOriginal;
    private final int chosenColourPort;
    private final int chosenAgentCtrlPort;
    private final int chosenObsPort;
    private final int chosenRewPort;
    private final int chosenCmdPort;
    private final int chosenVideoPort;
    private final int chosenDepthPort;
    private final String missionXmlPatched;

    private Thread ctrlThread;
    private Thread rewThread;
    private VideoServer videoServer;
    private VideoServer colourmapServer;
    private VideoServer depthServer;
    private StringServer observationServer;
    private Thread videoThread;
    private Thread colourThread;
    private Thread depthThread;
    private final java.util.concurrent.BlockingQueue<TimestampedVideoFrame> videoQueue = new java.util.concurrent.LinkedBlockingQueue<>();
    private final java.util.concurrent.BlockingQueue<TimestampedVideoFrame> colourQueue = new java.util.concurrent.LinkedBlockingQueue<>();
    private final java.util.concurrent.BlockingQueue<TimestampedVideoFrame> depthQueue = new java.util.concurrent.LinkedBlockingQueue<>();
    private final java.util.concurrent.atomic.AtomicReference<org.json.JSONObject> lastObservation = new java.util.concurrent.atomic.AtomicReference<>();
    // Saving
    private Path segSaveDir;
    private Path videoSaveDir;
    private Path obsSaveDir;
    private Path depthSaveDir;
    private final java.util.concurrent.atomic.AtomicInteger segCounter = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger videoCounter = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger obsCounter = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger depthCounter = new java.util.concurrent.atomic.AtomicInteger();

    public MConnector(String missionXml) throws IOException {
        this.missionXmlOriginal = missionXml;
        this.chosenColourPort = TestUtils.findFreePort();
        this.chosenAgentCtrlPort = TestUtils.findFreePort();
        this.chosenObsPort = TestUtils.findFreePort();
        this.chosenRewPort = TestUtils.findFreePort();
        this.chosenCmdPort = TestUtils.findFreePort();
        this.chosenVideoPort = TestUtils.findFreePort();
        this.chosenDepthPort = TestUtils.findFreePort();
        String patched = missionXml;
        patched = TestUtils.patchAgentColourMapPort(patched, this.chosenColourPort);
        patched = TestUtils.patchAgentMissionControlPort(patched, this.chosenAgentCtrlPort);
        patched = TestUtils.patchAgentObservationsPort(patched, this.chosenObsPort);
        patched = TestUtils.patchAgentRewardsPort(patched, this.chosenRewPort);
        patched = TestUtils.patchClientCommandsPort(patched, this.chosenCmdPort);
        patched = TestUtils.patchAgentVideoPort(patched, this.chosenVideoPort);
        patched = TestUtils.patchAgentDepthPort(patched, this.chosenDepthPort);
        patched = TestUtils.ensureVideoProducer(patched);
        patched = TestUtils.ensureDepthProducer(patched);
        patched = TestUtils.ensureColourMapRespectOpacity(patched);
        this.missionXmlPatched = patched;
        LOG.info("MConnector ports: colour=" + chosenColourPort + ", ctrl=" + chosenAgentCtrlPort + ", obs=" + chosenObsPort + ", rew=" + chosenRewPort + ", cmd=" + chosenCmdPort + ", video=" + chosenVideoPort + ", depth=" + chosenDepthPort);
    }

    public String getPatchedMissionXml() { return missionXmlPatched; }
    public int getChosenColourPort() { return chosenColourPort; }
    public int getChosenCtrlPort() { return chosenAgentCtrlPort; }
    public int getChosenObsPort() { return chosenObsPort; }
    public int getChosenRewPort() { return chosenRewPort; }
    public int getChosenCmdPort() { return chosenCmdPort; }
    public int getChosenVideoPort() { return chosenVideoPort; }
    public int getChosenDepthPort() { return chosenDepthPort; }

    public void startServers() {
        TestUtils.ControlServer ctrlServer = new TestUtils.ControlServer(chosenAgentCtrlPort);
        ctrlThread = new Thread(ctrlServer, "AgentControlServer");
        ctrlThread.setDaemon(true);
        ctrlThread.start();

        this.observationServer = new StringServer(this.chosenObsPort, (s) -> {
            try { lastObservation.set(new org.json.JSONObject(s)); } catch (Throwable ignored) {}
            Observations.parseObservation(s);
            // Save observation JSON in arrival order
            ensureDirsInitialized();
            int idx = obsCounter.incrementAndGet();
            Path out = obsSaveDir.resolve(String.format("obs_%05d.json", idx));
            try { Files.writeString(out, s, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING); } catch (IOException ignored) {}
        });
        observationServer.start();

        TestUtils.DrainServer rewServer = new TestUtils.DrainServer(chosenRewPort, "rew");
        rewThread = new Thread(rewServer, "RewServer");
        rewThread.setDaemon(true);
        rewThread.start();

        this.videoServer = new VideoServer(this.chosenVideoPort, 4, FrameType.VIDEO, (frame) -> {
            videoQueue.offer(frame);
            // Save video PNG
            ensureDirsInitialized();
            int idx = videoCounter.incrementAndGet();
            Path out = videoSaveDir.resolve(String.format("frame_%05d.png", idx));
            try { savePng(frame._pixels, frame.iWidth, frame.iHeight, frame.iCh, out); } catch (Throwable ignored) {}
        });
        this.videoThread = videoServer.start();
    }

    public Thread startVideoServer() { return this.videoThread; }

    public int waitForMissionControlPort(InputStream stdout, Duration timeout) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8));
        String line; long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline && (line = br.readLine()) != null) {
            LOG.info(line);
            int idx = line.indexOf("MCP: ");
            if (idx >= 0) {
                String tail = line.substring(idx + 5).trim();
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < tail.length(); i++) { char c = tail.charAt(i); if (Character.isDigit(c)) sb.append(c); else break; }
                if (sb.length() > 0) return Integer.parseInt(sb.toString());
            }
        }
        // Fallback: if we did not see an explicit MCP line in the logs,
        // assume the mission control port is the one we patched into the
        // mission XML (chosenAgentCtrlPort). This makes the tests robust
        // to changes in launch.sh or log formatting.
        LOG.warning("waitForMissionControlPort: did not detect MCP line within timeout; falling back to chosenAgentCtrlPort=" + this.chosenAgentCtrlPort);
        return this.chosenAgentCtrlPort;
    }

    public static class Ports { public final int colourPort, obsPort, rewPort, comPort, depthPort; public Ports(int c,int o,int r,int m,int d){colourPort=c;obsPort=o;rewPort=r;comPort=m;depthPort=d;} }

    public Ports waitForAgentPorts(InputStream stdout, Duration timeout) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        BufferedReader br = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8));
        StringBuilder acc = new StringBuilder(); int colour = -1, obs = -1, rew = -1, com = -1, depth = -1;
        while (System.nanoTime() < deadline) {
            if (!br.ready()) { try { Thread.sleep(50); } catch (InterruptedException ignored) {} continue; }
            String line = br.readLine(); if (line == null) break; acc.append(line).append('\n');
            colour = tryParseTag(acc, "AgentColourMapPort", colour);
            obs = tryParseTag(acc, "AgentObservationsPort", obs);
            rew = tryParseTag(acc, "AgentRewardsPort", rew);
            depth = tryParseTag(acc, "AgentDepthPort", depth);
            int posCom = acc.indexOf("->com("); if (posCom >= 0) {
                int posListen = acc.indexOf("Listening for messages on port ", posCom);
                if (posListen > 0) {
                    int start = posListen + "Listening for messages on port ".length(); int end = start;
                    while (end < acc.length() && Character.isDigit(acc.charAt(end))) end++;
                    try { com = Integer.parseInt(acc.substring(start, end)); } catch (Exception ignored) {}
                }
            }
            if (colour > 0 && obs > 0 && rew > 0 && com > 0 && depth > 0) return new Ports(colour, obs, rew, com, depth);
            if (acc.length() > 200000) acc.delete(0, acc.length() - 100000);
        }
        return new Ports(colour, obs, rew, com, depth);
    }
    private static int tryParseTag(StringBuilder acc, String tag, int current) {
        if (current > 0) return current; String open = "<" + tag + ">"; String close = "</" + tag + ">";
        int i = acc.indexOf(open); if (i >= 0) { int j = acc.indexOf(close, i); if (j > i) { String val = acc.substring(i + open.length(), j).trim(); try { return Integer.parseInt(val); } catch (Exception ignored) {} } }
        return current;
    }

    public int resolveColourPort(Ports ports) { return (ports != null && ports.colourPort > 0) ? ports.colourPort : chosenColourPort; }
    public int resolveDepthPort(Ports ports) { return (ports != null && ports.depthPort > 0) ? ports.depthPort : chosenDepthPort; }
    public int resolveCmdPort(Ports ports) { return (ports != null && ports.comPort > 0) ? ports.comPort : chosenCmdPort; }
    public static class Resolved { public final Ports ports; public final int colourPort; public final int depthPort; public final int cmdPort; Resolved(Ports p,int c,int d,int m){ports=p; colourPort=c; depthPort=d; cmdPort=m;} }

    public Resolved startFromLogs(InputStream stdout, Duration timeout) throws IOException {
        Ports ports = waitForAgentPorts(stdout, timeout);
        int effectiveColourPort = resolveColourPort(ports);
        if (effectiveColourPort <= 0) { LOG.warning("Could not resolve AgentColourMapPort from logs; falling back to chosen=" + getChosenColourPort()); effectiveColourPort = getChosenColourPort(); }
        else { LOG.info("Parsed AgentColourMapPort=" + effectiveColourPort); }
        int effectiveDepthPort = resolveDepthPort(ports);
        if (effectiveDepthPort <= 0) {
            LOG.warning("Could not resolve AgentDepthPort from logs; falling back to chosenDepthPort=" + getChosenDepthPort());
            effectiveDepthPort = getChosenDepthPort();
        } else {
            LOG.info("Parsed AgentDepthPort=" + effectiveDepthPort);
        }
        int cmd = resolveCmdPort(ports);
        this.colourmapServer = new VideoServer(effectiveColourPort, 3, FrameType.COLOUR_MAP, (frame) -> {
            colourQueue.offer(frame);
            // Save segmentation PNG and capture per-frame metrics with latest ObservationFromRay
            ensureDirsInitialized();
            int idx = segCounter.incrementAndGet();
            Path outPng = segSaveDir.resolve(String.format("frame_%05d.png", idx));
            try { savePng(frame._pixels, frame.iWidth, frame.iHeight, frame.iCh, outPng); } catch (Throwable ignored) {}

            long tsFrameNs = (long) (frame.timestamp * 1_000_000_000.0);
            String rayType = Observations.getLatestRayType();
            Double rayDist = Observations.getLatestRayDistance();
            double yaw = Observations.getLatestYaw();
            double pitch = Observations.getLatestPitch();
            long tsObsNs = Observations.getLatestRayTimestampNs();
            long deltaObsMs = (tsObsNs > 0L) ? ((tsFrameNs - tsObsNs) / 1_000_000L) : 0L;

            int uniq = 0; int center = 0; int cr = 0, cg = 0, cb = 0;
            try { uniq = frame.countUniqueColors(4096); } catch (Throwable ignored) {}
            try {
                center = frame.getCenterRGB();
                cr = (center >> 16) & 0xFF;
                cg = (center >> 8) & 0xFF;
                cb = (center) & 0xFF;
            } catch (Throwable ignored) {}

            // Write observation sidecar to aid debugging and correlation
            try {
                org.json.JSONObject obs = lastObservation.get();
                if (obs != null) {
                    Path outObs = segSaveDir.resolve(String.format("frame_%05d.obs.json", idx));
                    org.json.JSONObject wrapper = new org.json.JSONObject();
                    wrapper.put("ts_frame_ns", tsFrameNs);
                    wrapper.put("ts_obs_latest_ns", tsObsNs);
                    wrapper.put("observation", obs);
                    Files.writeString(outObs, wrapper.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                }
            } catch (Throwable ignored) {}

            // Append a line to metrics.csv with per-frame stats
            try {
                Path metrics = segSaveDir.resolve("metrics.csv");
                boolean writeHeader = Files.notExists(metrics);
                StringBuilder sb = new StringBuilder();
                if (writeHeader) {
                    sb.append("frame_idx,timestamp_ns,unique_colors,center_rgb_hex,center_r,center_g,center_b,obs_type,obs_distance,obs_yaw,obs_pitch,obs_ts_ns,delta_obs_ms,width,height\n");
                }
                sb.append(idx).append(',')
                  .append(tsFrameNs).append(',')
                  .append(uniq).append(',')
                  .append(String.format("#%06X", center & 0xFFFFFF)).append(',')
                  .append(cr).append(',')
                  .append(cg).append(',')
                  .append(cb).append(',')
                  .append(rayType == null ? "" : rayType.replace(',', ';')).append(',')
                  .append(rayDist == null ? "" : String.format(java.util.Locale.ROOT, "%.3f", rayDist)).append(',')
                  .append(Double.isNaN(yaw) ? "" : String.format(java.util.Locale.ROOT, "%.3f", yaw)).append(',')
                  .append(Double.isNaN(pitch) ? "" : String.format(java.util.Locale.ROOT, "%.3f", pitch)).append(',')
                  .append(tsObsNs).append(',')
                  .append(deltaObsMs).append(',')
                  .append(frame.iWidth).append(',')
                  .append(frame.iHeight)
                  .append('\n');
                Files.writeString(metrics, sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Throwable ignored) {}
        });
        this.colourThread = this.colourmapServer.start();

        // Depth video server (2 channels, uint16 per pixel) with raw frame saving.
        this.depthServer = new VideoServer(effectiveDepthPort, 2, FrameType.DEPTH_MAP, (frame) -> {
            depthQueue.offer(frame);
            ensureDirsInitialized();
            int idx = depthCounter.incrementAndGet();
            Path outPng = depthSaveDir.resolve(String.format("depth_%05d.png", idx));
            try {
                saveDepthPng(frame._pixels, frame.iWidth, frame.iHeight, frame.iCh, outPng);
            } catch (Throwable ignored) {}
        });
        this.depthThread = this.depthServer.start();

        return new Resolved(ports, effectiveColourPort, effectiveDepthPort, cmd);
    }

    public static final class Observations {
        private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(Observations.class.getName());
        private static volatile String latestRayType = null; private static volatile Double latestRayDistance = null; private static volatile Double latestYaw = null; private static volatile Double latestPitch = null; private static volatile long latestRayTimestampNs = 0L;
        private Observations() {}
        public static String getLatestRayType(){ return latestRayType; }
        public static Double getLatestRayDistance(){ return latestRayDistance; }
        public static long getLatestRayTimestampNs(){ return latestRayTimestampNs; }
        public static double getLatestYaw(){ Double d=latestYaw; return d==null? Double.NaN : d; }
        public static double getLatestPitch(){ Double d=latestPitch; return d==null? Double.NaN : d; }
        static void parseObservation(String txt) {
            try {
                JSONObject obj = new JSONObject(txt);
                LOG.info("[OBS-RAW] " + txt);
                latestRayType = null;
                latestRayDistance = null;
                latestRayTimestampNs = 0L;
                JSONObject los = obj.optJSONObject("LineOfSight");
                if (los != null) {
                    String hitType = los.optString("hitType", "");
                    if (!"MISS".equals(hitType)) {
                        // Assume all fields are present and valid
                        latestRayType = los.getString("type");
                        latestRayDistance = los.getDouble("distance");
                        latestRayTimestampNs = System.nanoTime();
                        latestYaw = obj.getDouble("Yaw");
                        latestPitch = obj.getDouble("Pitch");
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public TimestampedVideoFrame waitSegFrame(long timeout, TimeUnit unit) throws InterruptedException { return colourQueue.poll(timeout, unit); }
    public TimestampedVideoFrame waitSegFrame() throws InterruptedException { return waitSegFrame(60, TimeUnit.SECONDS); }
    public TimestampedVideoFrame waitVideoFrame(long timeout, TimeUnit unit) throws InterruptedException { return videoQueue.poll(timeout, unit); }
    public TimestampedVideoFrame waitVideoFrame() throws InterruptedException { return waitVideoFrame(60, TimeUnit.SECONDS); }
    public TimestampedVideoFrame waitDepthFrame(long timeout, TimeUnit unit) throws InterruptedException { return depthQueue.poll(timeout, unit); }
    public TimestampedVideoFrame waitDepthFrame() throws InterruptedException { return waitDepthFrame(60, TimeUnit.SECONDS); }
    public org.json.JSONObject getLatestObservation() { return lastObservation.get(); }
    public org.json.JSONObject getParticularObservation(String key) { org.json.JSONObject o = lastObservation.get(); return o == null ? null : o.optJSONObject(key); }

    public void sendMissionInit(String host, int mcpPort) throws IOException { try (Socket s = new Socket()) { s.setTcpNoDelay(true); s.connect(new InetSocketAddress(host, mcpPort), 5000); OutputStream out = s.getOutputStream(); out.write(missionXmlPatched.getBytes(StandardCharsets.UTF_8)); out.write('\n'); out.flush(); } }
    public Thread createCmdSenderThread(String host, int cmdPort, String[] cmds, int delayMs, String name) { TestUtils.CmdSender sender = new TestUtils.CmdSender(host, cmdPort, cmds, delayMs); Thread t = new Thread(sender, name != null ? name : "CmdSender"); t.setDaemon(true); return t; }
    public static Thread createCmdThread(String host, int cmdPort, String[] cmds, int delayMs, String name) { TestUtils.CmdSender sender = new TestUtils.CmdSender(host, cmdPort, cmds, delayMs); Thread t = new Thread(sender, name != null ? name : "CmdSender"); t.setDaemon(true); return t; }

    private void ensureDirsInitialized() {
        if (segSaveDir == null) segSaveDir = initRunDir(System.getProperty("seg.test.saveDir", "images/seg"));
        if (videoSaveDir == null) videoSaveDir = initRunDir(System.getProperty("video.test.saveDir", "images/video"));
        if (obsSaveDir == null) obsSaveDir = initRunDir(System.getProperty("obs.test.saveDir", "images/obs"));
        if (depthSaveDir == null) depthSaveDir = initRunDir(System.getProperty("depth.test.saveDir", "images/depth"));
    }
    private static Path initRunDir(String base) {
        Path d = Paths.get(base, String.format("run-%d", System.currentTimeMillis()/1000));
        try { Files.createDirectories(d); } catch (IOException ignored) {}
        return d;
    }
    private static void savePng(byte[] bgr, int w, int h, int ch, Path out) throws IOException {
        if (bgr == null || w <= 0 || h <= 0 || ch < 3) return;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int stride = ch; int off = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int b = bgr[off] & 0xFF;
                int g = bgr[off + 1] & 0xFF;
                int r = bgr[off + 2] & 0xFF;
                int rgb = (r << 16) | (g << 8) | b;
                img.setRGB(x, y, rgb);
                off += stride;
            }
        }
        try (OutputStream os = Files.newOutputStream(out, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ImageIO.write(img, "PNG", os);
        }
    }

    /** Save a 16-bit depth buffer as an 8-bit grayscale PNG for debugging. */
    private static void saveDepthPng(byte[] raw, int w, int h, int ch, Path out) throws IOException {
        if (raw == null || w <= 0 || h <= 0 || ch != 2) return;
        int pixels = w * h;
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int[] depth = new int[pixels];
        int min = Integer.MAX_VALUE;
        int max = 0;
        for (int i = 0; i < pixels && bb.remaining() >= 2; i++) {
            int v = bb.getShort() & 0xFFFF;
            depth[i] = v;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        if (min == Integer.MAX_VALUE) {
            // No valid samples; nothing to write.
            return;
        }
        int range = max - min;
        if (range <= 0) range = 1;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        java.awt.image.WritableRaster raster = img.getRaster();
        int idx = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = depth[idx++];
                int g = (int) ((v - min) * 255L / range);
                if (g < 0) g = 0; else if (g > 255) g = 255;
                raster.setSample(x, y, 0, g);
            }
        }
        try (OutputStream os = Files.newOutputStream(out, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ImageIO.write(img, "PNG", os);
        }
    }
}
