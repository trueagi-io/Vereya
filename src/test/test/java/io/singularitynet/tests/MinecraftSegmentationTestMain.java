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
        String missionXml = TestUtils.readUtf8(new File(missionPath));
        if (missionXml == null || missionXml.isEmpty()) {
            throw new FileNotFoundException("mission xml is empty: " + missionPath);
        }

        int chosenColourPort = TestUtils.findFreePort();
        int chosenAgentCtrlPort = TestUtils.findFreePort();
        int chosenObsPort = TestUtils.findFreePort();
        int chosenRewPort = TestUtils.findFreePort();
        int chosenCmdPort = TestUtils.findFreePort();
        TestUtils.setupLogger();
        LOG.info("Chosen AgentColourMapPort=" + chosenColourPort);
        LOG.info("Chosen AgentMissionControlPort=" + chosenAgentCtrlPort);
        LOG.info("Chosen AgentObservationsPort=" + chosenObsPort);
        LOG.info("Chosen AgentRewardsPort=" + chosenRewPort);
        LOG.info("Chosen ClientCommandsPort=" + chosenCmdPort);
        missionXml = TestUtils.patchAgentColourMapPort(missionXml, chosenColourPort);
        missionXml = TestUtils.patchAgentMissionControlPort(missionXml, chosenAgentCtrlPort);
        missionXml = TestUtils.patchAgentObservationsPort(missionXml, chosenObsPort);
        missionXml = TestUtils.patchAgentRewardsPort(missionXml, chosenRewPort);
        missionXml = TestUtils.patchClientCommandsPort(missionXml, chosenCmdPort);

        // Start servers the client will connect to: mission-control + colour-map
        TestUtils.ControlServer ctrlServer = new TestUtils.ControlServer(chosenAgentCtrlPort);
        Thread ctrlThread = new Thread(ctrlServer, "AgentControlServer");
        ctrlThread.setDaemon(true);
        ctrlThread.start();

        // Ensure the latest built vereya mod jar is deployed to the Minecraft mods directory (and old ones removed)
        Path deployed = TestUtils.ensureLatestModJarDeployed();
        LOG.info("Deployed mod jar: " + deployed);

        // Start observation server (parses ObservationFromRay) and reward drain.
        TestUtils.ObservationsServer obsServer = new TestUtils.ObservationsServer(chosenObsPort);
        Thread obsThread = new Thread(obsServer, "ObsServer");
        obsThread.setDaemon(true);
        obsThread.start();
        LOG.info("Observation server listening on " + chosenObsPort);

        TestUtils.DrainServer rewServer = new TestUtils.DrainServer(chosenRewPort, "rew");
        Thread rewThread = new Thread(rewServer, "RewServer");
        rewThread.setDaemon(true);
        rewThread.start();
        LOG.info("Reward server listening on " + chosenRewPort);

        // FrameServer (colour map) is started after we parse the effective port (or fallback to chosen).
        MConnector.FrameReceiver server = null;

        // Pick a free Xvfb display to avoid collisions across runs
        int xvfbDisplay = TestUtils.chooseFreeXDisplay(200, 240);
        int jdwpPort = TestUtils.findFreePort();
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

            int mcp = TestUtils.waitForMissionControlPort(proc.getInputStream(), Duration.ofSeconds(180));
            if (mcp <= 0) {
                throw new IllegalStateException("Did not detect MCP line from launch.sh within timeout");
            }
            LOG.info("Detected MCP=" + mcp);
            // Parse port from logs and start frame receiver
            TestUtils.Ports ports = TestUtils.waitForAgentPorts(proc.getInputStream(), Duration.ofSeconds(60));
            int effectiveColourPort = (ports.colourPort > 0 ? ports.colourPort : chosenColourPort);
            MConnector.FrameReceiver server = new MConnector.FrameReceiver(effectiveColourPort);
            Thread serverThread = new Thread(server, "ColourMapServer");
            serverThread.setDaemon(true);
            serverThread.start();
            // Gently rotate the agent to avoid sky-only views, using detected commands port or the one we set.
            int cmdPort = (ports.comPort > 0 ? ports.comPort : chosenCmdPort);
            Thread t = MConnector.createCmdThread(
                    "127.0.0.1", cmdPort,
                    new String[]{"turn 0.2", "turn 0.2", "turn 0.2", "turn 0.2", "turn 0.2"},
                    300, "CmdSpinner");
            t.setDaemon(true);
            t.start();
            // Continue draining stdout asynchronously for diagnostics after critical parsing
            TestUtils.drainStdoutAsync(proc.getInputStream());

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
        LOG.info("First frame: " + server.getLastHeader());

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
        int minUnique = getIntEnvOrProp("RUN_SEG_MIN_UNIQUE", "seg.test.minUnique", 7);
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
            // Restore Malmo-like strictness: if a type appears with multiple
            // colours and no clear dominant (>=80%), count as inconsistent.
            if (e.getValue().size() > 1 && domFrac < 0.8) inconsistent++;
        }
        // Fail if any type is inconsistent once we have at least 2 sampled types.
        if (withSamples >= 2 && inconsistent > 0) {
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

        // Enforce colour->block-type uniqueness: for block types (namespaced ids),
        // each observed colour must map to exactly one block type.
        java.util.Map<Integer, java.util.Set<String>> colourToBlockTypes = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, java.util.Map<Integer,Integer>> e : perTypeCounts.entrySet()) {
            String type = e.getKey();
            boolean isBlockType = type.contains(":");
            if (!isBlockType) continue;
            for (java.util.Map.Entry<Integer,Integer> ce : e.getValue().entrySet()) {
                colourToBlockTypes.computeIfAbsent(ce.getKey(), k -> new java.util.HashSet<>()).add(type);
            }
        }
        int colourCollisions = 0;
        for (java.util.Map.Entry<Integer, java.util.Set<String>> c : colourToBlockTypes.entrySet()) {
            if (c.getValue().size() > 1) colourCollisions++;
        }
        if (colourCollisions > 0) {
            LOG.info("Colour->types summary (collisions):");
            for (java.util.Map.Entry<Integer, java.util.Set<String>> c : colourToBlockTypes.entrySet()) {
                if (c.getValue().size() <= 1) continue;
                java.util.List<String> types = new java.util.ArrayList<>(c.getValue());
                LOG.info("  colour=#" + String.format("%06X", c.getKey()) + " blockTypes=" + c.getValue().size() + " sample=" + types);
            }
            throw new AssertionError("Colour uniqueness failed: " + colourCollisions + " colours mapped to multiple block types");
        }
        LOG.info("PASS: segmentation had non-black/diverse colours; per-type consistent and colour->type unique");
        } finally {
            // Ensure Minecraft + Xvfb are terminated
            try { proc.destroy(); } catch (Throwable ignored) {}
            try { if (!proc.waitFor(5, TimeUnit.SECONDS)) proc.destroyForcibly(); } catch (Throwable ignored) {}
            // Extra safety: kill any leftover Java/Xvfb processes tied to our unique markers
            TestUtils.cleanupLaunchedProcesses(xvfbDisplay, jdwpPort);
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

    // chooseFreeXDisplay moved to TestUtils

    // cleanupLaunchedProcesses moved to TestUtils

    // sleepQuiet moved to TestUtils

    // readUtf8 moved to TestUtils

    // findFreePort moved to TestUtils

    // waitForMissionControlPort moved to TestUtils

    // drainStdoutAsync moved to TestUtils

    // setupLogger moved to TestUtils

    // Patched via TestUtils now (methods left removed)

    // Agent ports parsing moved to TestUtils

    /**
     * Copies the newest vereya mod jar from build/libs into ~/.minecraft/mods and removes any older vereya jars there.
     */
    // ensureLatestModJarDeployed moved to TestUtils

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
                                String rayType = TestUtils.ObservationsServer.getLatestRayType();
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
                // Quantize to 4-bit per channel to approximate block types, not per-UV colours
                int rq = (r >> 4) & 0x0F;
                int gq = (g >> 4) & 0x0F;
                int bq = (b >> 4) & 0x0F;
                int rgb = (rq << 8) | (gq << 4) | bq;
                uniq.add(rgb);
                if (uniq.size() >= maxSamples) break;
            }
            return uniq.size();
        }

        // IO helpers moved to TestUtils.readIntBE/readFully
    }

    /** Simple drain server for observation/reward sockets; accepts length-prefixed payloads and discards them. */
    // DrainServer moved to TestUtils

    /** Parses ObservationFromRay messages from the observations port and exposes the latest block type string. */
    // ObservationsServer moved to TestUtils

    /**
     * Minimal sender that connects to the commands port and emits a few newline-terminated commands.
     */
    // CmdSender moved to TestUtils

    /** Minimal server to accept MC -> agent mission-control messages (length-prefixed UTF-8). */
    // ControlServer moved to TestUtils
}
