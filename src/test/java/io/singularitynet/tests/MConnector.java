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
    private final String missionXmlPatched;

    private Thread ctrlThread;
    private Thread rewThread;
    private VideoServer videoServer;
    private VideoServer colourmapServer;
    private StringServer observationServer;
    private Thread videoThread;
    private Thread colourThread;
    private final java.util.concurrent.BlockingQueue<TimestampedVideoFrame> videoQueue = new java.util.concurrent.LinkedBlockingQueue<>();
    private final java.util.concurrent.BlockingQueue<TimestampedVideoFrame> colourQueue = new java.util.concurrent.LinkedBlockingQueue<>();
    private final java.util.concurrent.atomic.AtomicReference<org.json.JSONObject> lastObservation = new java.util.concurrent.atomic.AtomicReference<>();
    // Saving
    private Path segSaveDir;
    private Path videoSaveDir;
    private Path obsSaveDir;
    private final java.util.concurrent.atomic.AtomicInteger segCounter = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger videoCounter = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger obsCounter = new java.util.concurrent.atomic.AtomicInteger();

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
        LOG.info("MConnector ports: colour=" + chosenColourPort + ", ctrl=" + chosenAgentCtrlPort + ", obs=" + chosenObsPort + ", rew=" + chosenRewPort + ", cmd=" + chosenCmdPort + ", video=" + chosenVideoPort);
    }

    public String getPatchedMissionXml() { return missionXmlPatched; }
    public int getChosenColourPort() { return chosenColourPort; }
    public int getChosenCtrlPort() { return chosenAgentCtrlPort; }
    public int getChosenObsPort() { return chosenObsPort; }
    public int getChosenRewPort() { return chosenRewPort; }
    public int getChosenCmdPort() { return chosenCmdPort; }
    public int getChosenVideoPort() { return chosenVideoPort; }

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
        return -1;
    }

    public static class Ports { public final int colourPort, obsPort, rewPort, comPort; public Ports(int c,int o,int r,int m){colourPort=c;obsPort=o;rewPort=r;comPort=m;} }

    public Ports waitForAgentPorts(InputStream stdout, Duration timeout) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        BufferedReader br = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8));
        StringBuilder acc = new StringBuilder(); int colour = -1, obs = -1, rew = -1, com = -1;
        while (System.nanoTime() < deadline) {
            if (!br.ready()) { try { Thread.sleep(50); } catch (InterruptedException ignored) {} continue; }
            String line = br.readLine(); if (line == null) break; acc.append(line).append('\n');
            colour = tryParseTag(acc, "AgentColourMapPort", colour);
            obs = tryParseTag(acc, "AgentObservationsPort", obs);
            rew = tryParseTag(acc, "AgentRewardsPort", rew);
            int posCom = acc.indexOf("->com("); if (posCom >= 0) {
                int posListen = acc.indexOf("Listening for messages on port ", posCom);
                if (posListen > 0) {
                    int start = posListen + "Listening for messages on port ".length(); int end = start;
                    while (end < acc.length() && Character.isDigit(acc.charAt(end))) end++;
                    try { com = Integer.parseInt(acc.substring(start, end)); } catch (Exception ignored) {}
                }
            }
            if (colour > 0 && obs > 0 && rew > 0 && com > 0) return new Ports(colour, obs, rew, com);
            if (acc.length() > 200000) acc.delete(0, acc.length() - 100000);
        }
        return new Ports(colour, obs, rew, com);
    }
    private static int tryParseTag(StringBuilder acc, String tag, int current) {
        if (current > 0) return current; String open = "<" + tag + ">"; String close = "</" + tag + ">";
        int i = acc.indexOf(open); if (i >= 0) { int j = acc.indexOf(close, i); if (j > i) { String val = acc.substring(i + open.length(), j).trim(); try { return Integer.parseInt(val); } catch (Exception ignored) {} } }
        return current;
    }

    public int resolveColourPort(Ports ports) { return (ports != null && ports.colourPort > 0) ? ports.colourPort : chosenColourPort; }
    public int resolveCmdPort(Ports ports) { return (ports != null && ports.comPort > 0) ? ports.comPort : chosenCmdPort; }
    public static class Resolved { public final Ports ports; public final int colourPort; public final int cmdPort; Resolved(Ports p,int c,int m){ports=p; colourPort=c; cmdPort=m;} }

    public Resolved startFromLogs(InputStream stdout, Duration timeout) throws IOException {
        Ports ports = waitForAgentPorts(stdout, timeout);
        int effectiveColourPort = resolveColourPort(ports);
        if (effectiveColourPort <= 0) { LOG.warning("Could not resolve AgentColourMapPort from logs; falling back to chosen=" + getChosenColourPort()); effectiveColourPort = getChosenColourPort(); }
        else { LOG.info("Parsed AgentColourMapPort=" + effectiveColourPort); }
        int cmd = resolveCmdPort(ports);
        this.colourmapServer = new VideoServer(effectiveColourPort, 3, FrameType.COLOUR_MAP, (frame) -> {
            colourQueue.offer(frame);
            // Save segmentation PNG
            ensureDirsInitialized();
            int idx = segCounter.incrementAndGet();
            Path out = segSaveDir.resolve(String.format("frame_%05d.png", idx));
            try { savePng(frame._pixels, frame.iWidth, frame.iHeight, frame.iCh, out); } catch (Throwable ignored) {}
        });
        this.colourThread = this.colourmapServer.start();
        return new Resolved(ports, effectiveColourPort, cmd);
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
    public org.json.JSONObject getLatestObservation() { return lastObservation.get(); }
    public org.json.JSONObject getParticularObservation(String key) { org.json.JSONObject o = lastObservation.get(); return o == null ? null : o.optJSONObject(key); }

    public void sendMissionInit(String host, int mcpPort) throws IOException { try (Socket s = new Socket()) { s.setTcpNoDelay(true); s.connect(new InetSocketAddress(host, mcpPort), 5000); OutputStream out = s.getOutputStream(); out.write(missionXmlPatched.getBytes(StandardCharsets.UTF_8)); out.write('\n'); out.flush(); } }
    public Thread createCmdSenderThread(String host, int cmdPort, String[] cmds, int delayMs, String name) { TestUtils.CmdSender sender = new TestUtils.CmdSender(host, cmdPort, cmds, delayMs); Thread t = new Thread(sender, name != null ? name : "CmdSender"); t.setDaemon(true); return t; }
    public static Thread createCmdThread(String host, int cmdPort, String[] cmds, int delayMs, String name) { TestUtils.CmdSender sender = new TestUtils.CmdSender(host, cmdPort, cmds, delayMs); Thread t = new Thread(sender, name != null ? name : "CmdSender"); t.setDaemon(true); return t; }

    private void ensureDirsInitialized() {
        if (segSaveDir == null) segSaveDir = initRunDir(System.getProperty("seg.test.saveDir", "images/seg"));
        if (videoSaveDir == null) videoSaveDir = initRunDir(System.getProperty("video.test.saveDir", "images/video"));
        if (obsSaveDir == null) obsSaveDir = initRunDir(System.getProperty("obs.test.saveDir", "images/obs"));
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
}
