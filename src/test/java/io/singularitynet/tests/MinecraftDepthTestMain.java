package io.singularitynet.tests;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Simplified depth-map smoke test, modelled on MinecraftSegmentationTestMain.
 * <p>
 * It starts a mission, connects via the queue-based MConnector, and checks
 * that:
 * - at least one depth frame is received,
 * - the depth frame is not entirely zero, and
 * - depth still looks sane when segmentation (colour-map) is enabled in the
 *   same mission.
 */
public class MinecraftDepthTestMain {
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(MinecraftDepthTestMain.class.getName());

    public static void main(String[] args) throws Exception {
        TestUtils.cleanupPreviousArtifacts();
        String missionPath = args.length > 0 ? args[0] : "mission.xml";
        File launch = new File("launch.sh");
        if (!launch.canExecute()) {
            throw new FileNotFoundException("launch.sh not found or not executable: " + launch.getAbsolutePath());
        }
        String missionXml = TestUtils.readUtf8(new File(missionPath));
        if (missionXml == null || missionXml.isEmpty()) {
            throw new FileNotFoundException("mission xml is empty: " + missionPath);
        }

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
        String dbgOpt = System.getenv("RUN_SEG_DEBUG");
        if (dbgOpt == null || dbgOpt.isEmpty()) {
            dbgOpt = System.getProperty("seg.test.debug", "");
        }
        // Allow optional segmentation debug; depth test itself does not rely on it.
        pb.environment().put("JAVA_TOOL_OPTIONS",
                (baseOpts + (" -Djava.awt.headless=true" + (dbgOpt.isEmpty() ? "" : (" -Dvereya.seg.debug=" + dbgOpt)))).trim());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        try {
            int mcp = conn.waitForMissionControlPort(proc.getInputStream(), Duration.ofSeconds(180));
            if (mcp <= 0) {
                throw new IllegalStateException("Did not detect MCP line from launch.sh within timeout");
            }
            LOG.info("Detected MCP=" + mcp);

            MConnector.Resolved res = conn.startFromLogs(proc.getInputStream(), Duration.ofSeconds(60));
            TestUtils.drainStdoutAsync(proc.getInputStream());
            conn.sendMissionInit("127.0.0.1", mcp);

            // First, wait for a colour-map frame if the mission enables segmentation,
            // to ensure segmentation and depth coexist without breaking rendering.
            TimestampedVideoFrame seg = conn.waitSegFrame(30, TimeUnit.SECONDS);
            if (seg != null) {
                LOG.info("Segmentation frame observed before depth check: " +
                        seg.iWidth + "x" + seg.iHeight + " ch=" + seg.iCh);
            } else {
                LOG.info("No segmentation frame observed within 30s; proceeding to depth check only.");
            }

            // Now wait for a depth frame.
            TimestampedVideoFrame depthFrame = conn.waitDepthFrame(30, TimeUnit.SECONDS);
            if (depthFrame == null) {
                throw new AssertionError("No depth frames received within timeout");
            }
            LOG.info("First depth frame: " + depthFrame.iWidth + "x" + depthFrame.iHeight +
                    " ch=" + depthFrame.iCh + " type=" + depthFrame.frametype);

            // Basic sanity checks: correct channel count and non-zero content.
            if (depthFrame.iCh != 2) {
                throw new AssertionError("Expected depth frame with 2 channels (uint16), got " + depthFrame.iCh);
            }

            long sum = depthFrame.sumUnsigned();
            LOG.info("Depth frame sum of uint16 values = " + sum);
            if (sum == 0L) {
                throw new AssertionError("Depth frame appears to be all zeros");
            }

            LOG.info("PASS: depth frame received, non-zero, and segmentation (if enabled) did not prevent depth capture.");
        } finally {
            try {
                proc.destroy();
            } catch (Throwable ignored) {}
            try {
                if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                    proc.destroyForcibly();
                }
            } catch (Throwable ignored) {}
            TestUtils.cleanupLaunchedProcesses(xvfbDisplay, jdwpPort);
        }
    }
}
