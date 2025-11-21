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
            if (!server.awaitFirstFrame(60, TimeUnit.SECONDS)) throw new AssertionError("No colour-map frames within timeout");
            LOG.info("First frame: " + server.getLastHeader());

            // No rotation — collect baseline immediately before spawning
            int preSpawnFrames = Integer.getInteger("seg.test.preSpawnFrames", 30);
            if (preSpawnFrames > 0) {
                server.awaitFramesAtLeast(preSpawnFrames, 60, TimeUnit.SECONDS);
                LOG.info("Pre-spawn baseline: frames=" + server.getFrameCount()
                        + " uniq(last/max)=" + server.getLastUniqueColors() + "/" + server.getMaxUniqueColors()
                        + " entity_uniq(last/max)=" + server.getLastEntityLikeUniqueColors() + "/" + server.getMaxEntityLikeUniqueColors());
            }

            // Spawn many chickens near the agent using chat /summon.
            int spawnCount = Integer.getInteger("seg.test.spawnCount", 40);
            String[] cmds = new String[spawnCount];
            for (int i = 0; i < spawnCount; i++) cmds[i] = "chat /summon minecraft:chicken ~ ~ ~";
            Thread spawner = conn.createCmdSenderThread("127.0.0.1", cmdPort, cmds, 1000, "Spawner");
            spawner.setDaemon(true); spawner.start();

            // Observe frames for a while and track entity-like unique colours
            server.awaitFramesAtLeast(80, 120, TimeUnit.SECONDS);
            LOG.info("Frames seen=" + server.getFrameCount());
            LOG.info("Unique colours (last/max)=" + server.getLastUniqueColors() + "/" + server.getMaxUniqueColors());
            LOG.info("Entity-like unique colours (last/max)=" + server.getLastEntityLikeUniqueColors() + "/" + server.getMaxEntityLikeUniqueColors());

            // Simple outcome: print summary to stdout; do not assert here.
            System.out.println("SUMMARY: frames=" + server.getFrameCount()
                    + " uniq=" + server.getLastUniqueColors()
                    + " uniqMax=" + server.getMaxUniqueColors()
                    + " entityUniq=" + server.getLastEntityLikeUniqueColors()
                    + " entityUniqMax=" + server.getMaxEntityLikeUniqueColors());
        } finally {
            try { proc.destroy(); } catch (Throwable ignored) {}
            try { if (!proc.waitFor(5, TimeUnit.SECONDS)) proc.destroyForcibly(); } catch (Throwable ignored) {}
            TestUtils.cleanupLaunchedProcesses(xvfbDisplay, jdwpPort);
        }
    }

    // All helpers moved to TestUtils/MConnector

    // Rotation helpers removed — test does not rotate the player now
}
