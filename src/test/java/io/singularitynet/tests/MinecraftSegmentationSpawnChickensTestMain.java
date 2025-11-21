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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Similar to MinecraftSegmentationTestMain but keeps the agent stationary and
 * spawns many chickens via chat commands. It records how many unique colours
 * appear in the segmentation stream, with a special focus on "entity-like"
 * colours (any channel >= 240) to detect mosaic behaviour on small mobs.
 */
public class MinecraftSegmentationSpawnChickensTestMain {
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(MinecraftSegmentationSpawnChickensTestMain.class.getName());

    public static void main(String[] args) throws Exception {
        TestUtils.cleanupPreviousArtifacts();
        String missionPath = args.length > 0 ? args[0] : "mission.xml";
        File launch = new File("launch.sh");
        if (!launch.canExecute()) throw new FileNotFoundException("launch.sh not found or not executable: " + launch.getAbsolutePath());
        String missionXml = TestUtils.readUtf8(new File(missionPath));
        if (missionXml == null || missionXml.isEmpty()) throw new FileNotFoundException("mission xml is empty: " + missionPath);

        TestUtils.setupLogger();
        MConnector conn = new MConnector(missionXml);
        conn.startServers();

        Path deployed = TestUtils.ensureLatestModJarDeployed();
        LOG.info("Deployed mod jar: " + deployed);

        int xvfbDisplay = TestUtils.chooseFreeXDisplay(200, 240);
        int jdwpPort = TestUtils.findFreePort();
        String launchCmd = "sed -e 's/-n 200/-n " + xvfbDisplay + "/' -e 's/address=8002/address=" + jdwpPort + "/' launch.sh | bash";
        ProcessBuilder pb = new ProcessBuilder("bash", "-lc", launchCmd);
        String baseOpts = pb.environment().getOrDefault("JAVA_TOOL_OPTIONS", "");
        String dbgOpt = System.getenv("RUN_SEG_DEBUG"); if (dbgOpt == null || dbgOpt.isEmpty()) dbgOpt = System.getProperty("seg.test.debug", "");
        String extra = " -Djava.awt.headless=true" + (dbgOpt.isEmpty() ? "" : (" -Dvereya.seg.debug=" + dbgOpt));
        pb.environment().put("JAVA_TOOL_OPTIONS", (baseOpts + extra).trim());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        try {
            int mcp = conn.waitForMissionControlPort(proc.getInputStream(), Duration.ofSeconds(180));
            if (mcp <= 0) throw new IllegalStateException("Did not detect MCP line from launch.sh within timeout");
            LOG.info("Detected MCP=" + mcp);

            MConnector.Resolved<MConnector.FrameReceiver> res = conn.startFrameServerFromLogs(
                    proc.getInputStream(), Duration.ofSeconds(60), MConnector.FrameReceiver::new, "ColourMapServer");
            MConnector.FrameReceiver server = res.server;
            int cmdPort = res.cmdPort;
            // No rotation. We want a stable viewpoint while we spawn chickens.

            // Start draining logs only after parsing ports
            TestUtils.drainStdoutAsync(proc.getInputStream());

            conn.sendMissionInit("127.0.0.1", mcp);
            if (!server.awaitFirstFrame(60, TimeUnit.SECONDS)) {
                String msg = "No colour-map frames within timeout";
                LOG.severe(msg);
                throw new AssertionError(msg);
            }
            LOG.info("First frame: " + server.getLastHeader());

            // No rotation — first, collect a baseline and then wait until the
            // unique-colour count stabilizes for N consecutive frames.
            int preSpawnFrames = Integer.getInteger("seg.test.preSpawnFrames", 30);
            if (preSpawnFrames > 0) server.awaitFramesAtLeast(preSpawnFrames, 60, TimeUnit.SECONDS);
            int stableFrames = Integer.getInteger("seg.test.stableFrames", Integer.getInteger("RUN_SEG_STABLE_FRAMES", 10));
            long stableTimeoutSec = Long.getLong("seg.test.stableTimeoutSec", Long.getLong("RUN_SEG_STABLE_TIMEOUT_SEC", 120L));
            long stableDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(stableTimeoutSec);
            LOG.info("Waiting for baseline stabilization: stableFrames=" + stableFrames + ", timeoutSec=" + stableTimeoutSec);
            while (System.nanoTime() < stableDeadline) {
                int minTail = server.getTailMinUnique(stableFrames);
                int avgTail = server.getTailAvgUnique(stableFrames);
                int last = server.getLastUniqueColors();
                if (stableFrames <= 1 || (minTail == avgTail && last == minTail && last > 0)) break;
                // Wait for one more frame and re-evaluate
                server.awaitFramesAtLeast(1, 5, TimeUnit.SECONDS);
            }
            int uniqBefore = server.getLastUniqueColors();
            int endBefore = server.getFrameCount();
            int startBefore = Math.max(1, endBefore - Math.max(1, stableFrames) + 1);
            int minBefore = server.getTailMinUnique(stableFrames);
            int avgBefore = server.getTailAvgUnique(stableFrames);
            LOG.info("Pre-spawn stabilized: frames=" + server.getFrameCount()
                    + " uniq(last/max)=" + server.getLastUniqueColors() + "/" + server.getMaxUniqueColors()
                    + " entity_uniq(last/max)=" + server.getLastEntityLikeUniqueColors() + "/" + server.getMaxEntityLikeUniqueColors()
                    + " window=" + startBefore + "-" + endBefore
                    + " min/avg=" + minBefore + "/" + avgBefore);

            // Spawn many chickens near the agent using chat /summon.
            int spawnCount = Integer.getInteger("seg.test.spawnCount", 40);
            LOG.info("Spawning chickens now: count=" + spawnCount);
            String[] cmds = new String[spawnCount];
            for (int i = 0; i < spawnCount; i++) cmds[i] = "chat /summon minecraft:chicken ~ ~ ~";
            Thread spawner = conn.createCmdSenderThread("127.0.0.1", cmdPort, cmds, 1000, "Spawner");
            spawner.setDaemon(true); spawner.start();

            // After spawning, wait until the unique colour count stabilizes again.
            long postStableTimeoutSec = Long.getLong("seg.test.postStableTimeoutSec", Long.getLong("RUN_SEG_POST_STABLE_TIMEOUT_SEC", 180L));
            long postDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(postStableTimeoutSec);
            // Give at least some frames for entities to appear
            server.awaitFramesAtLeast(stableFrames, 30, TimeUnit.SECONDS);
            while (System.nanoTime() < postDeadline) {
                int minTail = server.getTailMinUnique(stableFrames);
                int avgTail = server.getTailAvgUnique(stableFrames);
                int last = server.getLastUniqueColors();
                if (stableFrames <= 1 || (minTail == avgTail && last == minTail && last > 0)) break;
                server.awaitFramesAtLeast(1, 5, TimeUnit.SECONDS);
            }
            int uniqAfter = server.getLastUniqueColors();
            int delta = Math.max(0, uniqAfter - uniqBefore);
            int endAfter = server.getFrameCount();
            int startAfter = Math.max(1, endAfter - Math.max(1, stableFrames) + 1);
            int minAfter = server.getTailMinUnique(stableFrames);
            int avgAfter = server.getTailAvgUnique(stableFrames);
            LOG.info("Post-spawn stabilized: frames=" + server.getFrameCount()
                    + " uniqBefore=" + uniqBefore + " uniqAfter=" + uniqAfter + " delta=" + delta
                    + " entity_uniq(last/max)=" + server.getLastEntityLikeUniqueColors() + "/" + server.getMaxEntityLikeUniqueColors()
                    + " window=" + startAfter + "-" + endAfter
                    + " min/avg=" + minAfter + "/" + avgAfter);

            int allowedIncrease = Integer.getInteger("seg.test.allowedIncrease", Integer.getInteger("RUN_SEG_ALLOWED_INCREASE", 2));
            if (delta > allowedIncrease) {
                String msg = "Too many new colours after spawning chickens: delta=" + delta + " (>" + allowedIncrease + ")";
                LOG.severe(msg);
                throw new AssertionError(msg);
            }
            String summary = "SUMMARY: frames=" + server.getFrameCount()
                    + " uniqBefore=" + uniqBefore
                    + " uniqAfter=" + uniqAfter
                    + " delta=" + delta
                    + " entityUniq(last/max)=" + server.getLastEntityLikeUniqueColors() + "/" + server.getMaxEntityLikeUniqueColors();
            // Print and also log to ensure it appears in logs/test-integration.log
            System.out.println(summary);
            LOG.info(summary);
        } finally {
            try { proc.destroy(); } catch (Throwable ignored) {}
            try { if (!proc.waitFor(5, TimeUnit.SECONDS)) proc.destroyForcibly(); } catch (Throwable ignored) {}
            TestUtils.cleanupLaunchedProcesses(xvfbDisplay, jdwpPort);
        }
    }

    // All helpers moved to TestUtils/MConnector

    // Rotation helpers removed — test does not rotate the player now
}
