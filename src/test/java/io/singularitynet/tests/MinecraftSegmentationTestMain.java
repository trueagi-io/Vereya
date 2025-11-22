package io.singularitynet.tests;

import java.io.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Simplified segmentation test using new queue-based API. */
public class MinecraftSegmentationTestMain {
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(MinecraftSegmentationTestMain.class.getName());

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
        pb.environment().put("JAVA_TOOL_OPTIONS", (baseOpts + (" -Djava.awt.headless=true" + (dbgOpt.isEmpty()?"":(" -Dvereya.seg.debug="+dbgOpt)))).trim());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        try {
            int mcp = conn.waitForMissionControlPort(proc.getInputStream(), Duration.ofSeconds(180));
            if (mcp <= 0) throw new IllegalStateException("Did not detect MCP line from launch.sh within timeout");
            LOG.info("Detected MCP=" + mcp);

            MConnector.Resolved res = conn.startFromLogs(proc.getInputStream(), Duration.ofSeconds(60));
            TestUtils.drainStdoutAsync(proc.getInputStream());
            conn.sendMissionInit("127.0.0.1", mcp);

            // Wait first segmentation frame
            TimestampedVideoFrame seg0 = conn.waitSegFrame();
            if (seg0 == null) throw new AssertionError("No colour-map frames within timeout");
            LOG.info("First seg frame: " + seg0.iWidth + "x" + seg0.iHeight + " ch=" + seg0.iCh);

            int moreFrames = getIntEnvOrProp("RUN_SEG_MIN_FRAMES", "seg.test.minFrames", 60);
            long maxWaitSec = getLongEnvOrProp("RUN_SEG_MAX_WAIT_SEC", "seg.test.maxWaitSec", 60);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(maxWaitSec);
            java.util.ArrayList<Integer> uniqHist = new java.util.ArrayList<>();
            java.util.Map<String, java.util.Map<Integer,Integer>> perType = new java.util.HashMap<>();
            int maxUnique = 0; boolean everNonBlack = false; int frames = 0;
            while (frames < moreFrames && System.nanoTime() < deadline) {
                TimestampedVideoFrame seg = (frames == 0) ? seg0 : conn.waitSegFrame(2, TimeUnit.SECONDS);
                if (seg == null) continue;
                frames++;
                everNonBlack |= seg.isNonBlack();
                int u = seg.countUniqueColors(4096);
                uniqHist.add(u); if (u > maxUnique) maxUnique = u;
                String rt = MConnector.Observations.getLatestRayType();
                if (rt != null && !rt.isEmpty()) updatePerTypeFromCenter(perType, seg, rt);
            }
            LOG.info("Observed frames=" + frames);

            if (!everNonBlack) {
                long extra = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (System.nanoTime() < extra) {
                TimestampedVideoFrame seg = conn.waitSegFrame(2, TimeUnit.SECONDS);
                if (seg == null) continue;
                if (seg.isNonBlack()) { everNonBlack = true; break; }
            }
            }
            if (!everNonBlack) throw new AssertionError("Received colour-map frame was entirely black (all RGB zeros)");

            int minUnique = getIntEnvOrProp("RUN_SEG_MIN_UNIQUE", "seg.test.minUnique", 9);
            int lastU = uniqHist.isEmpty()? 0 : uniqHist.get(uniqHist.size()-1);
            if (lastU < minUnique && maxUnique < minUnique) {
                long extra = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (System.nanoTime() < extra) {
                    TimestampedVideoFrame seg = conn.waitSegFrame(2, TimeUnit.SECONDS);
                    if (seg == null) continue;
                    int u = seg.countUniqueColors(4096);
                    uniqHist.add(u); if (u > maxUnique) maxUnique = u; lastU = u;
                    if (lastU >= minUnique) break;
                }
            }
            if (lastU < minUnique && maxUnique < minUnique) throw new AssertionError("Colour diversity too low (last=" + lastU + ", max=" + maxUnique + ", required>=" + minUnique + ")");

            int graceFrames = getIntEnvOrProp("RUN_SEG_GRACE_FRAMES", "seg.test.graceFrames", 30);
            int tailFrames = getIntEnvOrProp("RUN_SEG_TAIL_FRAMES", "seg.test.tailFrames", 60);
            int tailMinRequired = getIntEnvOrProp("RUN_SEG_TAIL_MIN_UNIQUE", "seg.test.tailMinUnique", minUnique);
            if (uniqHist.size() >= tailFrames + graceFrames) {
                int tailMin = tailMin(uniqHist, tailFrames);
                int tailAvg = tailAvg(uniqHist, tailFrames);
                LOG.info("Tail diversity: min=" + tailMin + " avg=" + tailAvg + " required>=" + tailMinRequired);
                if (tailMin < tailMinRequired) throw new AssertionError("Tail colour diversity too low (minLastN=" + tailMin + ")");
            }

            SegmentationTestBase.assertObservationFromRayConsistency(perType, LOG, "main", 0.8, 2);
            SegmentationTestBase.assertColourToBlockTypeUniqueness(perType, LOG, "main");
            LOG.info("PASS: segmentation had non-black/diverse colours; per-type consistent and colour->type unique");
        } finally {
            try { proc.destroy(); } catch (Throwable ignored) {}
            try { if (!proc.waitFor(5, TimeUnit.SECONDS)) proc.destroyForcibly(); } catch (Throwable ignored) {}
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

    private static int tailMin(java.util.List<Integer> hist, int n) { int c=0,min=Integer.MAX_VALUE; for (int i=hist.size()-1;i>=0&&c<n;i--){min=Math.min(min,hist.get(i));c++;} return c==0?0:min; }
    private static int tailAvg(java.util.List<Integer> hist, int n) { int c=0; long s=0; for (int i=hist.size()-1;i>=0&&c<n;i--){s+=hist.get(i);c++;} return c==0?0:(int)Math.round(s*1.0/c); }
    private static void updatePerTypeFromCenter(java.util.Map<String, java.util.Map<Integer,Integer>> map, TimestampedVideoFrame f, String type) {
        int rgb = f.getCenterRGB();
        map.computeIfAbsent(type, k -> new java.util.HashMap<>()).merge(rgb, 1, Integer::sum);
    }
}
