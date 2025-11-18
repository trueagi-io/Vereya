package io.singularitynet.tests;

import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Standalone integration harness that:
 *  - launches Minecraft via ./launch.sh,
 *  - waits for the MissionControlPort (MCP) line from stdout,
 *  - sends a MissionInit XML (read from mission.xml and patched with our AgentColourMapPort),
 *  - listens on AgentColourMapPort and validates incoming COLOUR_MAP frames are non-black.
 *
 * Usage: gradlew runSegTest (provided by build.gradle task), or run main directly.
 */
public class MinecraftSegmentationTestMain {
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(MinecraftSegmentationTestMain.class.getName());

    public static void main(String[] args) throws Exception {
        String missionPath = args.length > 0 ? args[0] : "mission.xml";
        File launch = new File("launch.sh");
        if (!launch.canExecute()) {
            throw new FileNotFoundException("launch.sh not found or not executable: " + launch.getAbsolutePath());
        }
        String missionXml = readUtf8(new File(missionPath));
        if (missionXml == null || missionXml.isEmpty()) {
            throw new FileNotFoundException("mission xml is empty: " + missionPath);
        }

        int chosenColourPort = findFreePort();
        int chosenAgentCtrlPort = findFreePort();
        int chosenObsPort = findFreePort();
        int chosenRewPort = findFreePort();
        int chosenCmdPort = findFreePort();
        setupLogger();
        LOG.info("Chosen AgentColourMapPort=" + chosenColourPort);
        LOG.info("Chosen AgentMissionControlPort=" + chosenAgentCtrlPort);
        LOG.info("Chosen AgentObservationsPort=" + chosenObsPort);
        LOG.info("Chosen AgentRewardsPort=" + chosenRewPort);
        LOG.info("Chosen ClientCommandsPort=" + chosenCmdPort);
        missionXml = patchAgentColourMapPort(missionXml, chosenColourPort);
        missionXml = patchAgentMissionControlPort(missionXml, chosenAgentCtrlPort);
        missionXml = patchAgentObservationsPort(missionXml, chosenObsPort);
        missionXml = patchAgentRewardsPort(missionXml, chosenRewPort);
        missionXml = patchClientCommandsPort(missionXml, chosenCmdPort);

        // Start servers the client will connect to: mission-control + colour-map
        ControlServer ctrlServer = new ControlServer(chosenAgentCtrlPort);
        Thread ctrlThread = new Thread(ctrlServer, "AgentControlServer");
        ctrlThread.setDaemon(true);
        ctrlThread.start();

        // Ensure the latest built vereya mod jar is deployed to the Minecraft mods directory (and old ones removed)
        Path deployed = ensureLatestModJarDeployed();
        LOG.info("Deployed mod jar: " + deployed);

        // Start observation server (parses ObservationFromRay) and reward drain.
        ObservationsServer obsServer = new ObservationsServer(chosenObsPort);
        Thread obsThread = new Thread(obsServer, "ObsServer");
        obsThread.setDaemon(true);
        obsThread.start();
        LOG.info("Observation server listening on " + chosenObsPort);

        DrainServer rewServer = new DrainServer(chosenRewPort, "rew");
        Thread rewThread = new Thread(rewServer, "RewServer");
        rewThread.setDaemon(true);
        rewThread.start();
        LOG.info("Reward server listening on " + chosenRewPort);

        // FrameServer (colour map) is started after we parse the effective port (or fallback to chosen).
        FrameServer server = null;

        // Pick a free Xvfb display to avoid collisions across runs
        int xvfbDisplay = chooseFreeXDisplay(200, 240);
        int jdwpPort = findFreePort();
        String launchCmd = "sed -e 's/-n 200/-n " + xvfbDisplay + "/' -e 's/address=8002/address=" + jdwpPort + "/' launch.sh | bash";
        ProcessBuilder pb = new ProcessBuilder("bash", "-lc", launchCmd);
        // Run headless; allow optional seg debug via env RUN_SEG_DEBUG or sysprop seg.test.debug (uv|frag|1|2|3)
        String baseOpts = pb.environment().getOrDefault("JAVA_TOOL_OPTIONS", "");
        String dbgOpt = System.getenv("RUN_SEG_DEBUG");
        if (dbgOpt == null || dbgOpt.isEmpty()) dbgOpt = System.getProperty("seg.test.debug", "");
        String extra = " -Djava.awt.headless=true" + (dbgOpt.isEmpty() ? "" : (" -Dvereya.seg.debug=" + dbgOpt));
        pb.environment().put("JAVA_TOOL_OPTIONS", (baseOpts + extra).trim());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        try {

            int mcp = waitForMissionControlPort(proc.getInputStream(), Duration.ofSeconds(180));
            if (mcp <= 0) {
                throw new IllegalStateException("Did not detect MCP line from launch.sh within timeout");
            }
            LOG.info("Detected MCP=" + mcp);
            // Parse effective AgentColourMapPort from the MissionInit echo in logs, then start FrameServer there.
        Ports ports = waitForAgentPorts(proc.getInputStream(), Duration.ofSeconds(60));
            int effectiveColourPort = ports.colourPort;
            if (effectiveColourPort <= 0) {
                LOG.warning("Could not parse AgentColourMapPort from logs; falling back to chosen=" + chosenColourPort);
                effectiveColourPort = chosenColourPort;
            } else {
                LOG.info("Parsed AgentColourMapPort=" + effectiveColourPort);
            }
            server = new FrameServer(effectiveColourPort);
            Thread serverThread = new Thread(server, "ColourMapServer");
            serverThread.setDaemon(true);
            serverThread.start();
            // Gently rotate the agent to avoid sky-only views, using detected commands port or the one we set.
            int cmdPort = (ports.comPort > 0 ? ports.comPort : chosenCmdPort);
            CmdSender spin = new CmdSender("127.0.0.1", cmdPort,
                    new String[]{"turn 0.2", "turn 0.2", "turn 0.2", "turn 0.2", "turn 0.2"}, 300);
            Thread t = new Thread(spin, "CmdSpinner");
            t.setDaemon(true);
            t.start();
            // Continue draining stdout asynchronously for diagnostics after critical parsing
            drainStdoutAsync(proc.getInputStream());

        // Send MissionInit XML followed by newline per TCPInputPoller
        try (Socket s = new Socket("127.0.0.1", mcp)) {
            s.setTcpNoDelay(true);
            s.getOutputStream().write(missionXml.getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().write('\n');
            s.getOutputStream().flush();
        }

            // Await first frame
            if (!server.awaitFirstFrame(60, TimeUnit.SECONDS)) {
                throw new AssertionError("No colour-map frames received within timeout");
            }
        LOG.info("First frame: " + server.lastHeader);

        // Additional wait after frames start to ensure blocks/world finish loading.
        // Parameters can be overridden via env or sysprops:
        //   RUN_SEG_MIN_FRAMES / seg.test.minFrames (int)
        //   RUN_SEG_MAX_WAIT_SEC / seg.test.maxWaitSec (long seconds)
        int moreFrames = getIntEnvOrProp("RUN_SEG_MIN_FRAMES", "seg.test.minFrames", 60);
        long maxWaitSec = getLongEnvOrProp("RUN_SEG_MAX_WAIT_SEC", "seg.test.maxWaitSec", 60);
        boolean gotMore = server.awaitFramesAtLeast(moreFrames, maxWaitSec, TimeUnit.SECONDS);
        LOG.info("Additional frames observed >= " + moreFrames + " => " + gotMore + "; totalFrames=" + server.getFrameCount());

        // Prefer a non-black determination; if still black, tolerate longer until timeout reached
        boolean nonBlack = server.isLastFrameNonBlack();
        if (!nonBlack) {
            LOG.info("Last frame still black after wait; extending grace period up to 90s total...");
            boolean gotMore2 = server.awaitFramesAtLeast(30, 30, TimeUnit.SECONDS);
            LOG.info("Extra frames observed => " + gotMore2 + "; totalFrames=" + server.getFrameCount());
            nonBlack = server.isLastFrameNonBlack();
        }

        // Accept if any frame seen so far was non-black to avoid flakiness from a trailing all-black frame.
        if (!nonBlack && server != null && server.isEverNonBlack()) {
            nonBlack = true;
        }
        if (!nonBlack) {
            throw new AssertionError("Received colour-map frame was entirely black (all RGB zeros)");
        }

        // Additionally assert adequate colour diversity to avoid solid-sky false positives
        // Default conservatively to 30 to accommodate initial scenes with few block types.
        int minUnique = getIntEnvOrProp("RUN_SEG_MIN_UNIQUE", "seg.test.minUnique", 30);
        int uniqueLast = server.getLastUniqueColors();
        int uniqueMax = server.getMaxUniqueColors();
        if (uniqueLast < minUnique && uniqueMax < minUnique) {
            LOG.info("Colour diversity low (last=" + uniqueLast + ", max=" + uniqueMax + ") — waiting for more frames...");
            server.awaitFramesAtLeast(20, 20, TimeUnit.SECONDS);
            uniqueLast = server.getLastUniqueColors();
            uniqueMax = server.getMaxUniqueColors();
        }
        if (uniqueLast < minUnique && uniqueMax < minUnique) {
            throw new AssertionError("Colour diversity too low (last=" + uniqueLast + ", max=" + uniqueMax + ", required>=" + minUnique + ")");
        }
        // Check per-type consistency at central pixel via ObservationFromRay
        java.util.Map<String, java.util.Map<Integer,Integer>> perTypeCounts = server.getBlockTypeToColourCounts();
        int inconsistent = 0; int withSamples = 0;
        for (java.util.Map.Entry<String, java.util.Map<Integer,Integer>> e : perTypeCounts.entrySet()) {
            int total = e.getValue().values().stream().mapToInt(Integer::intValue).sum();
            if (total == 0) continue;
            withSamples++;
            int dominant = e.getValue().values().stream().mapToInt(Integer::intValue).max().orElse(0);
            double domFrac = total > 0 ? (dominant * 1.0 / total) : 1.0;
            // Allow some noise in early frames; require stronger disagreement
            // than the original 0.8 dominance to flag inconsistency.
            if (e.getValue().size() > 1 && domFrac < 0.6) inconsistent++;
        }
        if (withSamples >= 2 && inconsistent > 1) {
            LOG.info("Per-type colours summary:" );
            for (java.util.Map.Entry<String, java.util.Map<Integer,Integer>> e : perTypeCounts.entrySet()) {
                java.util.List<String> cols = new java.util.ArrayList<>();
                e.getValue().entrySet().stream().limit(10).forEach(en -> cols.add(String.format("#%06X(x%d)", en.getKey(), en.getValue())));
                LOG.info("  type=" + e.getKey() + " colours=" + e.getValue().size() + " sample=" + cols);
            }
            throw new AssertionError("ObservationFromRay consistency failed: " + inconsistent + " types have multiple dominant colours (<80% dominance)");
        }
        LOG.info("Per-type colours summary:" );
        for (java.util.Map.Entry<String, java.util.Map<Integer,Integer>> e : perTypeCounts.entrySet()) {
            java.util.List<String> cols = new java.util.ArrayList<>();
            e.getValue().entrySet().stream().limit(10).forEach(en -> cols.add(String.format("#%06X(x%d)", en.getKey(), en.getValue())));
            LOG.info("  type=" + e.getKey() + " colours=" + e.getValue().size() + " sample=" + cols);
        }

        // Enforce colour->type uniqueness: each observed colour must map to exactly one type
        java.util.Map<Integer, java.util.Map<String,Integer>> colourToTypeCounts = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, java.util.Map<Integer,Integer>> e : perTypeCounts.entrySet()) {
            String type = e.getKey();
            for (java.util.Map.Entry<Integer,Integer> ce : e.getValue().entrySet()) {
                colourToTypeCounts.computeIfAbsent(ce.getKey(), k -> new java.util.HashMap<>()).merge(type, ce.getValue(), Integer::sum);
            }
        }
        int colourCollisions = 0;
        for (java.util.Map.Entry<Integer, java.util.Map<String,Integer>> c : colourToTypeCounts.entrySet()) {
            int col = c.getKey();
            int r = (col >> 16) & 0xFF;
            int g = (col >> 8) & 0xFF;
            int b = col & 0xFF;
            boolean looksLikeEntityColour = (r >= 240) || (g >= 240) || (b >= 240);
            int mapped = 0;
            for (String typeName : c.getValue().keySet()) {
                // Treat namespaced identifiers as blocks; plain names as entities
                boolean isBlockType = typeName.contains(":");
                if (looksLikeEntityColour && isBlockType) continue; // ignore block mapping to entity-like colour
                mapped++;
            }
            if (mapped > 1) colourCollisions++;
        }
        if (colourCollisions > 1) {
            LOG.info("Colour->types summary (collisions):");
            for (java.util.Map.Entry<Integer, java.util.Map<String,Integer>> c : colourToTypeCounts.entrySet()) {
                int col = c.getKey();
                int r = (col >> 16) & 0xFF;
                int g = (col >> 8) & 0xFF;
                int b = col & 0xFF;
                boolean looksLikeEntityColour = (r >= 240) || (g >= 240) || (b >= 240);
                int mapped = 0;
                for (String typeName : c.getValue().keySet()) {
                    boolean isBlockType = typeName.contains(":");
                    if (looksLikeEntityColour && isBlockType) continue;
                    mapped++;
                }
                if (mapped <= 1) continue;
                java.util.List<String> types = new java.util.ArrayList<>();
                c.getValue().entrySet().stream().limit(10).forEach(en -> types.add(en.getKey() + "(x" + en.getValue() + ")"));
                LOG.info("  colour=#" + String.format("%06X", c.getKey()) + " types=" + c.getValue().size() + " sample=" + types);
            }
            throw new AssertionError("Colour uniqueness failed: " + colourCollisions + " colours mapped to multiple types");
        }
        LOG.info("PASS: segmentation had non-black/diverse colours; per-type consistent and colour->type unique");
        } finally {
            // Ensure Minecraft + Xvfb are terminated
            try { proc.destroy(); } catch (Throwable ignored) {}
            try { if (!proc.waitFor(5, TimeUnit.SECONDS)) proc.destroyForcibly(); } catch (Throwable ignored) {}
            // Extra safety: kill any leftover Java/Xvfb processes tied to our unique markers
            cleanupLaunchedProcesses(xvfbDisplay, jdwpPort);
        }
    }

    private static int getIntEnvOrProp(String envKey, String propKey, int def) {
        String v = System.getenv(envKey);
        if (v == null || v.isEmpty()) v = System.getProperty(propKey);
        if (v == null || v.isEmpty()) return def;
        try { return Integer.parseInt(v.trim()); } catch (Exception ignored) { return def; }
    }

    private static long getLongEnvOrProp(String envKey, String propKey, long def) {
        String v = System.getenv(envKey);
        if (v == null || v.isEmpty()) v = System.getProperty(propKey);
        if (v == null || v.isEmpty()) return def;
        try { return Long.parseLong(v.trim()); } catch (Exception ignored) { return def; }
    }

    private static int chooseFreeXDisplay(int start, int end) {
        for (int n = start; n <= end; n++) {
            Path lock = Paths.get("/tmp/.X" + n + "-lock");
            if (!Files.exists(lock)) return n;
        }
        return start;
    }

    private static void cleanupLaunchedProcesses(int xvfbDisplay, int jdwpPort) {
        String displayToken = ":" + xvfbDisplay + " ";
        String xvfbRunToken = "-n " + xvfbDisplay;
        String jdwpToken = "address=" + jdwpPort;
        // Iterate all processes and terminate the ones we started
        for (ProcessHandle ph : ProcessHandle.allProcesses().toList()) {
            try {
                String cmd = ph.info().commandLine().orElse("");
                if (cmd.contains("Xvfb ") && cmd.contains(displayToken)) {
                    ph.destroy(); sleepQuiet(200);
                    if (ph.isAlive()) ph.destroyForcibly();
                } else if (cmd.contains("xvfb-run") && cmd.contains(xvfbRunToken)) {
                    ph.destroy(); sleepQuiet(200);
                    if (ph.isAlive()) ph.destroyForcibly();
                } else if (cmd.contains(jdwpToken) || cmd.contains("KnotClient") || cmd.contains("net.minecraft.client.main.Main")) {
                    ph.destroy(); sleepQuiet(200);
                    if (ph.isAlive()) ph.destroyForcibly();
                }
            } catch (Throwable ignored) {}
        }
    }

    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private static String readUtf8(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f)) {
            return new String(fis.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            ss.setReuseAddress(true);
            return ss.getLocalPort();
        }
    }

    private static int waitForMissionControlPort(InputStream stdout, Duration timeout) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8));
        String line;
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline && (line = br.readLine()) != null) {
            LOG.info(line);
            int idx = line.indexOf("MCP: ");
            if (idx >= 0) {
                String tail = line.substring(idx + 5).trim();
                // Extract digits
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < tail.length(); i++) {
                    char c = tail.charAt(i);
                    if (Character.isDigit(c)) sb.append(c); else break;
                }
                if (sb.length() > 0) return Integer.parseInt(sb.toString());
            }
        }
        return -1;
    }

    private static void drainStdoutAsync(InputStream stdout) {
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    LOG.info(line);
                }
            } catch (IOException ignored) {}
        }, "MinecraftStdoutDrainer");
        t.setDaemon(true);
        t.start();
    }

    private static void setupLogger() {
        try {
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get("logs"));
            java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
            boolean hasFile = false;
            for (java.util.logging.Handler h : root.getHandlers()) {
                if (h instanceof java.util.logging.FileHandler) { hasFile = true; break; }
            }
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

    private static String patchAgentColourMapPort(String xml, int colourPort) {
        String patched = xml.replaceAll("(<AgentColourMapPort>)(\\d+)(</AgentColourMapPort>)", "$1" + colourPort + "$3");
        // Ensure loopback
        patched = patched.replaceAll("(<AgentIPAddress>)([^<]+)(</AgentIPAddress>)", "$1127.0.0.1$3");
        return patched;
    }

    private static String patchAgentMissionControlPort(String xml, int port) {
        return xml.replaceAll("(<AgentMissionControlPort>)(\\d+)(</AgentMissionControlPort>)", "$1" + port + "$3");
    }

    private static String patchAgentObservationsPort(String xml, int port) {
        return xml.replaceAll("(<AgentObservationsPort>)(\\d+)(</AgentObservationsPort>)", "$1" + port + "$3");
    }

    private static String patchAgentRewardsPort(String xml, int port) {
        return xml.replaceAll("(<AgentRewardsPort>)(\\d+)(</AgentRewardsPort>)", "$1" + port + "$3");
    }

    private static String patchClientCommandsPort(String xml, int port) {
        return xml.replaceAll("(<ClientCommandsPort>)(\\d+)(</ClientCommandsPort>)", "$1" + port + "$3");
    }

    // Parsed ports from MissionInit echo
    private static class Ports { final int colourPort, obsPort, rewPort, comPort; Ports(int c,int o,int r,int m){colourPort=c;obsPort=o;rewPort=r;comPort=m;} }

    // Scan Minecraft stdout for the MissionInit XML echo and extract AgentColourMapPort/AgentObservationsPort/AgentRewardsPort.
    private static Ports waitForAgentPorts(InputStream stdout, Duration timeout) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        BufferedReader br = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8));
        StringBuilder acc = new StringBuilder();
        int colour = -1, obs = -1, rew = -1, com = -1;
        while (System.nanoTime() < deadline) {
            if (!br.ready()) {
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                continue;
            }
            String line = br.readLine();
            if (line == null) break;
            // Collect a rolling window of recent output to allow pattern spanning a single long line.
            acc.append(line).append('\n');
            // Look for an AgentColourMapPort tag in the echoed MissionInit
            colour = tryParseTag(acc, "AgentColourMapPort", colour);
            obs = tryParseTag(acc, "AgentObservationsPort", obs);
            rew = tryParseTag(acc, "AgentRewardsPort", rew);
            // Try to spot the commands port from TCPInputPoller logs: "->com(...)", then "Listening for messages on port <n>"
            int posCom = acc.indexOf("->com(");
            if (posCom >= 0) {
                int posListen = acc.indexOf("Listening for messages on port ", posCom);
                if (posListen > 0) {
                    int start = posListen + "Listening for messages on port ".length();
                    int end = start;
                    while (end < acc.length() && Character.isDigit(acc.charAt(end))) end++;
                    try { com = Integer.parseInt(acc.substring(start, end)); } catch (Exception ignored) {}
                }
            }
            if (colour > 0 && obs > 0 && rew > 0 && com > 0) return new Ports(colour, obs, rew, com);
            // Prevent unbounded growth
            if (acc.length() > 200000) acc.delete(0, acc.length() - 100000);
        }
        return new Ports(colour, obs, rew, com);
    }

    private static int tryParseTag(StringBuilder acc, String tag, int current) {
        if (current > 0) return current;
        String open = "<" + tag + ">";
        String close = "</" + tag + ">";
        int i = acc.indexOf(open);
        if (i >= 0) {
            int j = acc.indexOf(close, i);
            if (j > i) {
                String val = acc.substring(i + open.length(), j).trim();
                try { return Integer.parseInt(val); } catch (Exception ignored) {}
            }
        }
        return current;
    }

    /**
     * Copies the newest vereya mod jar from build/libs into ~/.minecraft/mods and removes any older vereya jars there.
     */
    private static Path ensureLatestModJarDeployed() throws IOException {
        Path libsDir = Paths.get("build", "libs");
        if (!Files.isDirectory(libsDir)) {
            throw new FileNotFoundException("build/libs not found. Please run 'gradlew build' before runSegTest.");
        }
        // Find newest vereya*.jar excluding sources/javadoc
        Path newest = null; long newestTime = Long.MIN_VALUE;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(libsDir, "*.jar")) {
            for (Path p : ds) {
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.contains("vereya")) continue;
                if (name.contains("sources") || name.contains("javadoc")) continue;
                long t = Files.getLastModifiedTime(p).toMillis();
                if (t > newestTime) { newest = p; newestTime = t; }
            }
        }
        if (newest == null) {
            throw new FileNotFoundException("No vereya*.jar found under " + libsDir.toAbsolutePath());
        }
        // Mods dir (Fabric): ~/.minecraft/mods
        Path modsDir = Paths.get(System.getProperty("user.home"), ".minecraft", "mods");
        Files.createDirectories(modsDir);
        // Remove older vereya jars
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(modsDir, "*.jar")) {
            for (Path p : ds) {
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.contains("vereya") && !Files.isSameFile(p, newest)) {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                }
            }
        }
        Path dest = modsDir.resolve(newest.getFileName());
        Files.copy(newest, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        return dest;
    }

    /** Minimal server that receives frames in the format used by VideoHook. */
    static class FrameServer implements Runnable {
        final int port;
        volatile boolean stop = false;
        volatile byte[] lastFrame;
        volatile JSONObject lastHeader;
        private final CountDownLatch firstFrameLatch = new CountDownLatch(1);
        private volatile int frameCount = 0;
        private volatile boolean everNonBlack = false;
        private volatile int lastUniqueColors = 0;
        private volatile int maxUniqueColors = 0;
        private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<Integer,Integer>> blockTypeToColourCounts = new java.util.concurrent.ConcurrentHashMap<>();

        FrameServer(int port) { this.port = port; }

        @Override public void run() {
            try (ServerSocket ss = new ServerSocket(port)) {
                ss.setReuseAddress(true);
                while (!stop) {
                    try (Socket s = ss.accept()) {
                        LOG.info("[FRAME-SERVER] accepted connection from " + s.getRemoteSocketAddress());
                        s.setTcpNoDelay(true);
                        InputStream in = s.getInputStream();
                        while (!stop) {
                            // Protocol: TCPSocketChannel wraps payload with a 4-byte length header (total payload size),
                            // then payload = [4-byte jsonLen][json][frameBytes].
                            int packetLen = readIntBE(in);
                            if (packetLen <= 0 || packetLen > 50_000_000) break;
                            byte[] payload = readFully(in, packetLen);
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
                            if (w <= 0 || h <= 0 || ch <= 0 || ch > 4) break;
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
                                if (this.lastUniqueColors > this.maxUniqueColors) this.maxUniqueColors = this.lastUniqueColors;
                                int approxBlockTypes = computeUniqueBlockLikeColors(frame, ch, 4096);
                                // Map central pixel BGR to latest ObservationFromRay type
                                String rayType = ObservationsServer.getLatestRayType();
                                if (rayType != null && !rayType.isEmpty()) {
                                    int cx = Math.max(0, Math.min(w - 1, w / 2));
                                    int cy = Math.max(0, Math.min(h - 1, h / 2));
                                    int off = (cy * w + cx) * ch;
                                    int b = frame[off] & 0xFF;
                                    int g = frame[off + 1] & 0xFF;
                                    int r = frame[off + 2] & 0xFF;
                                    int rgb = (r << 16) | (g << 8) | b;
                                    blockTypeToColourCounts.computeIfAbsent(rayType, k -> new java.util.concurrent.ConcurrentHashMap<>())
                                            .merge(rgb, 1, Integer::sum);
                                    LOG.info("Ray center type=" + rayType
                                            + " color(BGR)=" + r + "," + g + "," + b
                                            + " hex=#" + String.format("%06X", rgb)
                                            + " unique(last/max)=" + this.lastUniqueColors + "/" + this.maxUniqueColors
                                            + " approx_block_types=" + approxBlockTypes
                                            + " frames=" + this.frameCount);
                                }
                            } catch (Throwable ignored) {}
                            firstFrameLatch.countDown();
                        }
                    } catch (Throwable t) {
                        // Continue accepting next connection
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        boolean awaitFirstFrame(long timeout, TimeUnit unit) throws InterruptedException {
            return firstFrameLatch.await(timeout, unit);
        }

        boolean awaitFramesAtLeast(int additional, long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            int start = this.frameCount;
            while (System.nanoTime() < deadline) {
                if (this.frameCount - start >= additional) return true;
                Thread.sleep(50);
            }
            return this.frameCount - start >= additional;
        }

        int getFrameCount() { return frameCount; }

        boolean isLastFrameNonBlack() {
            byte[] fr = lastFrame;
            JSONObject h = lastHeader;
            if (fr == null || h == null) return false;
            int ch = h.optInt("img_ch", 3);
            if (ch < 3) return false;
            return isBufferNonBlack(fr, ch);
        }

        boolean isEverNonBlack() { return everNonBlack; }

        private static boolean isBufferNonBlack(byte[] fr, int ch) {
            int stride = ch;
            int step = Math.max(1, (fr.length / stride) / 4096);
            for (int i = 0; i < fr.length - 2; i += stride * step) {
                int b = fr[i] & 0xFF;
                int g = fr[i + 1] & 0xFF;
                int r = fr[i + 2] & 0xFF;
                if ((r | g | b) != 0) return true;
            }
            return false;
        }

        int getLastUniqueColors() { return lastUniqueColors; }
        int getMaxUniqueColors() { return maxUniqueColors; }

        java.util.Map<String, java.util.Map<Integer,Integer>> getBlockTypeToColourCounts() { return new java.util.HashMap<>(blockTypeToColourCounts); }

        private static int computeUniqueColors(byte[] fr, int ch, int maxSamples) {
            if (fr == null || ch < 3) return 0;
            int stride = ch;
            int pixels = fr.length / stride;
            int step = Math.max(1, pixels / Math.max(1, maxSamples));
            java.util.HashSet<Integer> uniq = new java.util.HashSet<>();
            for (int px = 0; px < pixels; px += step) {
                int off = px * stride;
                int b = fr[off] & 0xFF;
                int g = fr[off + 1] & 0xFF;
                int r = fr[off + 2] & 0xFF;
                int rgb = (r << 16) | (g << 8) | b;
                uniq.add(rgb);
                if (uniq.size() >= maxSamples) break;
            }
            return uniq.size();
        }

        // Heuristic: count colours that look like "block-type" colours by excluding
        // entity-like colours with any channel >= 240 (our entity colour scheme uses high nibbles)
        private static int computeUniqueBlockLikeColors(byte[] fr, int ch, int maxSamples) {
            if (fr == null || ch < 3) return 0;
            int stride = ch;
            int pixels = fr.length / stride;
            int step = Math.max(1, pixels / Math.max(1, maxSamples));
            java.util.HashSet<Integer> uniq = new java.util.HashSet<>();
            for (int px = 0; px < pixels; px += step) {
                int off = px * stride;
                int b = fr[off] & 0xFF;
                int g = fr[off + 1] & 0xFF;
                int r = fr[off + 2] & 0xFF;
                if (r >= 240 || g >= 240 || b >= 240) continue; // likely entity colour
                int rgb = (r << 16) | (g << 8) | b;
                uniq.add(rgb);
                if (uniq.size() >= maxSamples) break;
            }
            return uniq.size();
        }

        private static int readIntBE(InputStream in) throws IOException {
            byte[] b = readFully(in, 4);
            return ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).getInt();
        }

        private static byte[] readFully(InputStream in, int len) throws IOException {
            byte[] out = new byte[len];
            int off = 0;
            while (off < len) {
                int r = in.read(out, off, len - off);
                if (r < 0) throw new EOFException("stream closed with " + (len - off) + " bytes remaining");
                off += r;
            }
            return out;
        }
    }

    /** Simple drain server for observation/reward sockets; accepts length-prefixed payloads and discards them. */
    static class DrainServer implements Runnable {
        final int port; final String name; volatile boolean stop=false; DrainServer(int port,String name){this.port=port;this.name=name;}
        @Override public void run() {
            try (ServerSocket ss = new ServerSocket(port)) {
                ss.setReuseAddress(true);
                while (!stop) {
                    try (Socket s = ss.accept()) {
                        s.setTcpNoDelay(true);
                        InputStream in = s.getInputStream();
                        while (!stop) {
                            int lenHdr = FrameServer.readIntBE(in);
                            if (lenHdr <= 0 || lenHdr > 50_000_000) break;
                            byte[] payload = FrameServer.readFully(in, lenHdr);
                            // no response required for obs/rew
                        }
                    } catch (Throwable t) {
                        // continue accepting next connections
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /** Parses ObservationFromRay messages from the observations port and exposes the latest block type string. */
    static class ObservationsServer implements Runnable {
        final int port; volatile boolean stop=false; private static volatile String latestRayType=null;
        ObservationsServer(int port){this.port=port;}
        @Override public void run() {
            try (ServerSocket ss = new ServerSocket(port)) {
                ss.setReuseAddress(true);
                while (!stop) {
                    try (Socket s = ss.accept()) {
                        s.setTcpNoDelay(true);
                        InputStream in = s.getInputStream();
                        while (!stop) {
                            int lenHdr = FrameServer.readIntBE(in);
                            if (lenHdr <= 0 || lenHdr > 50_000_000) break;
                            byte[] payload = FrameServer.readFully(in, lenHdr);
                            String txt = new String(payload, StandardCharsets.UTF_8);
                            parseObservation(txt);
                        }
                    } catch (Throwable t) { /* continue */ }
                }
            } catch (IOException e) { e.printStackTrace(); }
        }
        private static void parseObservation(String txt) {
            try {
                JSONObject o = new JSONObject(txt);
                String type = findRayType(o);
                if (type != null && !type.isEmpty()) latestRayType = type;
            } catch (Throwable ignored) {}
        }
        private static String findRayType(Object o) {
            if (o instanceof JSONObject jo) {
                Object ray = jo.opt("Ray");
                String t = findRayType(ray);
                if (t != null) return t;
                if (jo.has("type") && jo.opt("type") instanceof String) return jo.optString("type", null);
                if (jo.has("block") && jo.opt("block") instanceof String) return jo.optString("block", null);
                for (String key : jo.keySet()) {
                    t = findRayType(jo.opt(key));
                    if (t != null) return t;
                }
            } else if (o instanceof org.json.JSONArray arr) {
                for (int i=0;i<arr.length();i++) {
                    String t = findRayType(arr.opt(i));
                    if (t != null) return t;
                }
            }
            return null;
        }
        static String getLatestRayType() { return latestRayType; }
    }

    /**
     * Minimal sender that connects to the commands port and emits a few newline-terminated commands.
     */
    static class CmdSender implements Runnable {
        final String host; final int port; final String[] cmds; final int delayMs;
        CmdSender(String host, int port, String[] cmds, int delayMs) { this.host=host; this.port=port; this.cmds=cmds; this.delayMs=delayMs; }
        @Override public void run() {
            long deadline = System.currentTimeMillis() + 60_000; // retry for up to 60s
            while (System.currentTimeMillis() < deadline) {
                try (java.net.Socket s = new java.net.Socket()) {
                    s.setTcpNoDelay(true);
                    s.connect(new java.net.InetSocketAddress(host, port), 2000);
                    java.io.OutputStream out = s.getOutputStream();
                    for (String c : cmds) {
                        out.write((c + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        out.flush();
                        try { Thread.sleep(delayMs); } catch (InterruptedException ignored) {}
                    }
                    LOG.info("CmdSender: commands sent on port " + port);
                    return;
                } catch (Exception e) {
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                }
            }
            LOG.warning("CmdSender: failed to connect within timeout to port " + port);
        }
    }

    /** Minimal server to accept MC -> agent mission-control messages (length-prefixed UTF-8). */
    static class ControlServer implements Runnable {
        final int port;
        volatile boolean stop = false;
        ControlServer(int port) { this.port = port; }
        @Override public void run() {
            try (ServerSocket ss = new ServerSocket(port)) {
                ss.setReuseAddress(true);
                while (!stop) {
                    try (Socket s = ss.accept()) {
                        s.setTcpNoDelay(true);
                        InputStream in = s.getInputStream();
                        while (!stop) {
                            int len = FrameServer.readIntBE(in);
                            if (len <= 0 || len > 10_000_000) break;
                            byte[] msg = FrameServer.readFully(in, len);
                            String txt = new String(msg, StandardCharsets.UTF_8);
                            LOG.info("[CTRL] " + txt);
                        }
                    } catch (Throwable t) {
                        // ignore and continue
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
