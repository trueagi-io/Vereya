package io.singularitynet.tests;

import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Common helpers shared by segmentation integration tests. */
public final class TestUtils {
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(TestUtils.class.getName());

    private TestUtils() {}

    public static void cleanupPreviousArtifacts() {
        try {
            Path log = Paths.get("logs", "test-integration.log");
            try { Files.deleteIfExists(log); } catch (Exception ignored) {}
            Path segDir = Paths.get("images", "seg");
            if (Files.exists(segDir)) deleteRecursive(segDir);
            try { Files.createDirectories(segDir); } catch (Exception ignored) {}
        } catch (Throwable ignored) {}
    }

    public static void deleteRecursive(Path path) throws IOException {
        if (!Files.exists(path)) return;
        Files.walk(path).sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
        });
    }

    public static void setupLogger() {
        try {
            Files.createDirectories(Paths.get("logs"));
            java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
            boolean hasFile = false; for (java.util.logging.Handler h : root.getHandlers()) if (h instanceof java.util.logging.FileHandler) { hasFile = true; break; }
            if (!hasFile) {
                java.util.logging.FileHandler fh = new java.util.logging.FileHandler("logs/test-integration.log", true);
                fh.setFormatter(new java.util.logging.Formatter() {
                    @Override public String format(java.util.logging.LogRecord r) {
                        String ts = new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new java.util.Date(r.getMillis()));
                        return ts + " [" + r.getLevel() + "] " + r.getMessage() + "\n";
                    }
                });
                root.addHandler(fh);
            }
        } catch (Throwable ignored) {}
    }

    public static String readUtf8(File f) throws IOException { try (FileInputStream fis = new FileInputStream(f)) { return new String(fis.readAllBytes(), StandardCharsets.UTF_8); } }
    public static int findFreePort() throws IOException { try (ServerSocket ss = new ServerSocket(0)) { ss.setReuseAddress(true); return ss.getLocalPort(); } }
    public static int chooseFreeXDisplay(int start, int end) { for (int n = start; n <= end; n++) { Path lock = Paths.get("/tmp/.X" + n + "-lock"); if (!Files.exists(lock)) return n; } return start; }
    public static void cleanupLaunchedProcesses(int xvfbDisplay, int jdwpPort) {
        String displayToken = ":" + xvfbDisplay + " "; String xvfbRunToken = "-n " + xvfbDisplay; String jdwpToken = "address=" + jdwpPort;
        for (ProcessHandle ph : ProcessHandle.allProcesses().toList()) {
            try {
                String cmd = ph.info().commandLine().orElse("");
                if (cmd.contains("Xvfb ") && cmd.contains(displayToken)) { ph.destroy(); sleepQuiet(200); if (ph.isAlive()) ph.destroyForcibly(); }
                else if (cmd.contains("xvfb-run") && cmd.contains(xvfbRunToken)) { ph.destroy(); sleepQuiet(200); if (ph.isAlive()) ph.destroyForcibly(); }
                else if (cmd.contains(jdwpToken) || cmd.contains("KnotClient") || cmd.contains("net.minecraft.client.main.Main")) { ph.destroy(); sleepQuiet(200); if (ph.isAlive()) ph.destroyForcibly(); }
            } catch (Throwable ignored) {}
        }
    }
    public static void sleepQuiet(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }

    public static void drainStdoutAsync(InputStream stdout) {
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8))) {
                String line; while ((line = br.readLine()) != null) { LOG.info(line); }
            } catch (IOException ignored) {}
        }, "MinecraftStdoutDrainer");
        t.setDaemon(true); t.start();
    }

    // MCP/Agent ports parsing moved to MConnector to avoid duplication.

    // Simple drain server for observation/reward sockets; accepts length-prefixed payloads and discards them.
    public static class DrainServer implements Runnable {
        final int port; final String name; public volatile boolean stop=false;
        public DrainServer(int port,String name){this.port=port; this.name=name;}
        @Override public void run(){ try(ServerSocket ss=new ServerSocket(port)){ ss.setReuseAddress(true); while(!stop){ try(Socket s=ss.accept()){ s.setTcpNoDelay(true); InputStream in=s.getInputStream(); while(!stop){ int len = readIntBE(in); if(len<=0||len>50_000_000) break; byte[] payload = readFully(in, len); /* discard */ } } catch(Throwable t){} } } catch(IOException e){ e.printStackTrace(); } }
    }

    public static class CmdSender implements Runnable {
        final String host; final int port; final String[] cmds; final int delayMs;
        public CmdSender(String host, int port, String[] cmds, int delayMs) { this.host=host; this.port=port; this.cmds=cmds; this.delayMs=delayMs; }
        @Override public void run() { long deadline = System.currentTimeMillis() + 60_000; while (System.currentTimeMillis() < deadline) { try (Socket s = new Socket()) { s.setTcpNoDelay(true); s.connect(new InetSocketAddress(host, port), 2000); OutputStream out = s.getOutputStream(); for (String c : cmds) { out.write((c + "\n").getBytes(StandardCharsets.UTF_8)); out.flush(); try { Thread.sleep(delayMs); } catch (InterruptedException ignored) {} } LOG.info("CmdSender: commands sent on port " + port); return; } catch (Exception e) { try { Thread.sleep(1000); } catch (InterruptedException ignored) {} } } LOG.warning("CmdSender: failed to connect within timeout to port " + port); }
    }

    public static class ControlServer implements Runnable {
        final int port; volatile boolean stop=false; public ControlServer(int port){this.port=port;}
        @Override public void run(){ try(ServerSocket ss=new ServerSocket(port)){ ss.setReuseAddress(true); while(!stop){ try(Socket s=ss.accept()){ s.setTcpNoDelay(true); InputStream in=s.getInputStream(); while(!stop){ int len = readIntBE(in); if(len<=0||len>10_000_000) break; byte[] msg = readFully(in, len); String txt = new String(msg, StandardCharsets.UTF_8); LOG.info("[CTRL] "+txt); } } catch(Throwable t){} } } catch(IOException e){ e.printStackTrace(); } }
    }

    // ObservationsServer moved to MConnector.ObservationsServer; no longer defined here.

    // Small helpers for reading the frame streams
    public static int readIntBE(InputStream in) throws IOException { byte[] b = readFully(in, 4); return ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).getInt(); }
    public static byte[] readFully(InputStream in, int len) throws IOException { byte[] out = new byte[len]; int off=0; while(off<len){ int r=in.read(out,off,len-off); if(r<0) throw new EOFException("stream closed with "+(len-off)+" bytes remaining"); off+=r;} return out; }

    // Mission XML patchers used by tests
    public static String patchAgentColourMapPort(String xml, int colourPort) {
        String patched = xml.replaceAll("(<AgentColourMapPort>)(\\d+)(</AgentColourMapPort>)", "$1" + colourPort + "$3");
        return patched.replaceAll("(<AgentIPAddress>)([^<]+)(</AgentIPAddress>)", "$1127.0.0.1$3");
    }
    public static String patchAgentMissionControlPort(String xml, int port) { return xml.replaceAll("(<AgentMissionControlPort>)(\\d+)(</AgentMissionControlPort>)", "$1" + port + "$3"); }
    public static String patchAgentObservationsPort(String xml, int port) { return xml.replaceAll("(<AgentObservationsPort>)(\\d+)(</AgentObservationsPort>)", "$1" + port + "$3"); }
    public static String patchAgentRewardsPort(String xml, int port) { return xml.replaceAll("(<AgentRewardsPort>)(\\d+)(</AgentRewardsPort>)", "$1" + port + "$3"); }
    public static String patchClientCommandsPort(String xml, int port) { return xml.replaceAll("(<ClientCommandsPort>)(\\d+)(</ClientCommandsPort>)", "$1" + port + "$3"); }
    public static String patchAgentVideoPort(String xml, int port) { return xml.replaceAll("(<AgentVideoPort>)(\\d+)(</AgentVideoPort>)", "$1" + port + "$3"); }

    /** Ensure a <VideoProducer> exists under AgentHandlers. If missing, insert one with width/height
     * copied from ColourMapProducer when available, otherwise 1280x960. */
    public static String ensureVideoProducer(String xml) {
        if (xml.contains("<VideoProducer")) return xml;
        int w = 1280, h = 960;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "<ColourMapProducer[\\s\\S]*?<Width>(\\d+)</Width>[\\s\\S]*?<Height>(\\d+)</Height>[\\s\\S]*?</ColourMapProducer>").matcher(xml);
            if (m.find()) {
                w = Integer.parseInt(m.group(1));
                h = Integer.parseInt(m.group(2));
            }
        } catch (Throwable ignored) {}
        String videoXml = String.format("<VideoProducer><Width>%d</Width><Height>%d</Height></VideoProducer>", w, h);
        // Prefer inserting immediately before ColourMapProducer, else before </AgentHandlers>
        int idx = xml.indexOf("<ColourMapProducer");
        if (idx >= 0) {
            return new StringBuilder(xml).insert(idx, videoXml).toString();
        }
        int ahClose = xml.indexOf("</AgentHandlers>");
        if (ahClose >= 0) {
            return new StringBuilder(xml).insert(ahClose, videoXml).toString();
        }
        // Fallback: append at end
        return xml + videoXml;
    }

    /** Ensure ColourMapProducer has respectOpacity="true" attribute to cut out transparent texels. */
    public static String ensureColourMapRespectOpacity(String xml) {
        if (xml.contains("<ColourMapProducer") && xml.contains("respectOpacity")) return xml;
        return xml.replace("<ColourMapProducer", "<ColourMapProducer respectOpacity=\"true\"");
    }

    // XML patchers for spawn are removed; set agent spawn directly in mission.xml

    // Deploy newest vereya mod jar into the Fabric mods dir
    public static Path ensureLatestModJarDeployed() throws IOException {
        Path libsDir = Paths.get("build", "libs");
        if (!Files.isDirectory(libsDir)) throw new FileNotFoundException("build/libs not found. Please run 'gradlew build'.");
        Path newest = null; long newestTime = Long.MIN_VALUE;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(libsDir, "*.jar")) {
            for (Path p : ds) {
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.contains("vereya")) continue; if (name.contains("sources") || name.contains("javadoc")) continue;
                long t = Files.getLastModifiedTime(p).toMillis(); if (t > newestTime) { newest = p; newestTime = t; }
            }
        }
        if (newest == null) throw new FileNotFoundException("No vereya*.jar found under " + libsDir.toAbsolutePath());
        Path modsDir = Paths.get(System.getProperty("user.home"), ".minecraft", "mods"); Files.createDirectories(modsDir);
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(modsDir, "*.jar")) {
            for (Path p : ds) {
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.contains("vereya") && !Files.isSameFile(p, newest)) { try { Files.deleteIfExists(p); } catch (IOException ignored) {} }
            }
        }
        Path dest = modsDir.resolve(newest.getFileName());
        Files.copy(newest, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        return dest;
    }
}
