package io.singularitynet.tests;

import java.io.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Spawn chickens and compare pre/post unique-colour counts using new API. */
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
        pb.environment().put("JAVA_TOOL_OPTIONS", (baseOpts + (" -Djava.awt.headless=true" + (dbgOpt.isEmpty()?"":(" -Dvereya.seg.debug="+dbgOpt)))).trim());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        try {
            int mcp = conn.waitForMissionControlPort(proc.getInputStream(), Duration.ofSeconds(180));
            if (mcp <= 0) throw new IllegalStateException("Did not detect MCP line from launch.sh within timeout");
            LOG.info("Detected MCP=" + mcp);

            MConnector.Resolved res = conn.startFromLogs(proc.getInputStream(), Duration.ofSeconds(60));
            int cmdPort = res.cmdPort;
            TestUtils.drainStdoutAsync(proc.getInputStream());
            conn.sendMissionInit("127.0.0.1", mcp);

            // Wait first seg frame
            TimestampedVideoFrame seg0 = conn.waitSegFrame();
            if (seg0 == null) throw new AssertionError("No colour-map frames within timeout");
            LOG.info("First seg frame: " + seg0.iWidth + "x" + seg0.iHeight + " ch=" + seg0.iCh);

            // Stabilize baseline
            int preSpawnFrames = Integer.getInteger("seg.test.preSpawnFrames", 30);
            int minPreSpawnFrames = Integer.getInteger("seg.test.minPreSpawnFrames", Integer.getInteger("RUN_SEG_MIN_PRESPAWN_FRAMES", 50));
            preSpawnFrames = Math.max(preSpawnFrames, minPreSpawnFrames);
            for (int i = 0; i < preSpawnFrames; i++) conn.waitSegFrame(2, TimeUnit.SECONDS);
            int stableFrames = Integer.getInteger("seg.test.stableFrames", Integer.getInteger("RUN_SEG_STABLE_FRAMES", 10));
            long stableTimeoutSec = Long.getLong("seg.test.stableTimeoutSec", Long.getLong("RUN_SEG_STABLE_TIMEOUT_SEC", 120L));
            java.util.ArrayList<Integer> uniqHist = new java.util.ArrayList<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(stableTimeoutSec);
            while (System.nanoTime() < deadline) {
                TimestampedVideoFrame seg = conn.waitSegFrame(2, TimeUnit.SECONDS);
                if (seg == null) continue;
                int u = seg.countUniqueColors(4096);
                uniqHist.add(u);
                if (uniqHist.size() >= stableFrames && tailMin(uniqHist, stableFrames) == tailAvg(uniqHist, stableFrames) && u > 0) break;
            }
            int uniqBefore = uniqHist.isEmpty()? 0 : uniqHist.get(uniqHist.size()-1);
            LOG.info("Baseline stabilized: uniqBefore=" + uniqBefore);

            // Spawn chickens one block in front of the player using local (caret) coordinates and keep them stationary (NoAI).
            // ^ ^ ^1 means forward by 1 block relative to the executing player's facing.
            String[] cmds = new String[]{
                    // Slight left/right offsets; place between 3 and 4 blocks ahead.
                    "chat /summon chicken ^-0.5 ^ ^3.0 {NoAI:1b}",
                    "chat /summon chicken ^0.5 ^ ^3.0 {NoAI:1b}",
                    "chat /summon chicken ^-0.5 ^ ^3.5 {NoAI:1b}",
                    "chat /summon chicken ^0.5 ^ ^3.5 {NoAI:1b}",
                    "chat /summon chicken ^-0.5 ^ ^4.0 {NoAI:1b}",
                    "chat /summon chicken ^0.5 ^ ^4.0 {NoAI:1b}",
            };
            Thread spawner = conn.createCmdSenderThread("127.0.0.1", cmdPort, cmds, 1000, "Spawner");
            spawner.start();

            // Post-spawn stabilization
            java.util.ArrayList<Integer> uniqAfterHist = new java.util.ArrayList<>();
            long postDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Long.getLong("seg.test.postStableTimeoutSec", Long.getLong("RUN_SEG_POST_STABLE_TIMEOUT_SEC", 180L)));
            while (System.nanoTime() < postDeadline) {
                TimestampedVideoFrame seg = conn.waitSegFrame(2, TimeUnit.SECONDS);
                if (seg == null) continue;
                int u = seg.countUniqueColors(4096);
                uniqAfterHist.add(u);
                if (uniqAfterHist.size() >= stableFrames && tailMin(uniqAfterHist, stableFrames) == tailAvg(uniqAfterHist, stableFrames) && u > 0) break;
            }
            int uniqAfter = uniqAfterHist.isEmpty()? 0 : uniqAfterHist.get(uniqAfterHist.size()-1);
            int delta = Math.max(0, uniqAfter - uniqBefore);
            int minUniqueRequired = Integer.getInteger("seg.test.minUnique", Integer.getInteger("RUN_SEG_MIN_UNIQUE", 9));
            if (Math.min(tailMin(uniqAfterHist, stableFrames), uniqAfter) < minUniqueRequired) {
                String msg = "Colour diversity too low after spawn (last=" + uniqAfter + ", required>=" + minUniqueRequired + ")";
                LOG.severe(msg);
                throw new AssertionError(msg);
            }

            // Observation checks on post-stabilized window
            java.util.Map<String, java.util.Map<Integer,Integer>> perType = new java.util.HashMap<>();
            int framesCollect = Math.max(3, stableFrames);
            for (int i = 0; i < framesCollect; i++) {
                TimestampedVideoFrame seg = conn.waitSegFrame(2, TimeUnit.SECONDS);
                if (seg == null) continue;
                String rt = MConnector.Observations.getLatestRayType();
                if (rt != null && !rt.isEmpty()) updatePerTypeFromCenter(perType, seg, rt);
            }
            SegmentationTestBase.assertObservationFromRayConsistency(perType, LOG, "post-spawn", 0.8, 2);
            SegmentationTestBase.assertColourToBlockTypeUniqueness(perType, LOG, "post-spawn");

            int allowedIncrease = Integer.getInteger("seg.test.allowedIncrease", Integer.getInteger("RUN_SEG_ALLOWED_INCREASE", 2));
            if (delta > allowedIncrease) {
                String msg = "Too many new colours after spawning chickens: delta=" + delta + " (>" + allowedIncrease + ")";
                LOG.severe(msg);
                throw new AssertionError(msg);
            }

            // Sanity: ensure we actually saved a meaningful number of frames.
            int minSaved = Integer.getInteger("seg.test.minSavedFrames", 50);
            String base = System.getProperty("seg.test.saveDir", "images/seg");
            java.nio.file.Path baseDir = java.nio.file.Paths.get(base);
            java.nio.file.Path latestRun = null;
            try {
                java.util.Optional<java.nio.file.Path> last = java.nio.file.Files.list(baseDir)
                        .filter(p -> java.nio.file.Files.isDirectory(p) && p.getFileName().toString().startsWith("run-"))
                        .max(java.util.Comparator.comparing(java.nio.file.Path::toString));
                if (last.isPresent()) latestRun = last.get();
            } catch (Throwable ignored) {}
            if (latestRun != null) {
                long saved = 0;
                try {
                    saved = java.nio.file.Files.list(latestRun)
                            .filter(p -> p.getFileName().toString().endsWith(".png"))
                            .count();
                } catch (Throwable ignored) {}
                if (saved < minSaved) {
                    String msg = "Too few saved segmentation frames: count=" + saved + " (<" + minSaved + ") in " + latestRun;
                    LOG.severe(msg);
                    throw new AssertionError(msg);
                }
            }
            String summary = "SUMMARY: uniqBefore=" + uniqBefore + " uniqAfter=" + uniqAfter + " delta=" + delta;
            System.out.println(summary); LOG.info(summary);
        } finally {
            try { proc.destroy(); } catch (Throwable ignored) {}
            try { if (!proc.waitFor(5, TimeUnit.SECONDS)) proc.destroyForcibly(); } catch (Throwable ignored) {}
            TestUtils.cleanupLaunchedProcesses(xvfbDisplay, jdwpPort);
        }
    }

    private static int tailMin(java.util.List<Integer> hist, int n) { int c=0,min=Integer.MAX_VALUE; for (int i=hist.size()-1;i>=0&&c<n;i--){min=Math.min(min,hist.get(i));c++;} return c==0?0:min; }
    private static int tailAvg(java.util.List<Integer> hist, int n) { int c=0; long s=0; for (int i=hist.size()-1;i>=0&&c<n;i--){s+=hist.get(i);c++;} return c==0?0:(int)Math.round(s*1.0/c); }
    private static void updatePerTypeFromCenter(java.util.Map<String, java.util.Map<Integer,Integer>> map, TimestampedVideoFrame f, String type) {
        int rgb = f.getCenterRGB();
        map.computeIfAbsent(type, k -> new java.util.HashMap<>()).merge(rgb, 1, Integer::sum);
    }
}
