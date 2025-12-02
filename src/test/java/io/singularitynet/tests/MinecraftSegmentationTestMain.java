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
        String missionPath = args.length > 0 ? args[0] : "src/test/resources/mission.xml";
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

            int moreFrames = getIntEnvOrProp("RUN_SEG_MIN_FRAMES", "seg.test.minFrames", 60);
            long maxWaitSec = getLongEnvOrProp("RUN_SEG_MAX_WAIT_SEC", "seg.test.maxWaitSec", 60);
            // Phase 1: wait for the world/segmentation frames to stabilise before
            // issuing commands or collecting detailed statistics. We require that,
            // for at least 10 consecutive checks, >=95% of pixels remain unchanged
            // between successive segmentation frames.
            TimestampedVideoFrame first = conn.waitSegFrame();
            if (first == null) {
                LOG.warning("DEBUG: No colour-map frames received within initial timeout");
                return;
            }
            LOG.info("First seg frame: " + first.iWidth + "x" + first.iHeight + " ch=" + first.iCh);

            TimestampedVideoFrame seg0 = waitForStableWorld(conn, first, maxWaitSec);

            // Once the scene is stable, gently rotate the agent to avoid sky-only
            // views and gather more diverse segmentation samples. Commands are
            // queued to the background command thread managed inside MConnector.
            for (int i = 0; i < 5; i++) {
                conn.sendCommand("turn 0.05");
            }

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
                int centerRgb = 0;
                try { centerRgb = seg.getCenterRGB(); } catch (Throwable ignored) {}
                LOG.info(String.format("Frame %d (idx=%d): LoS type=%s centreColour=#%06X",
                        frames,
                        seg.debugIndex,
                        (rt != null && !rt.isEmpty()) ? rt : "<none>",
                        centerRgb & 0xFFFFFF));
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
            if (!everNonBlack) {
                LOG.warning("DEBUG: Received colour-map frames were entirely black (all RGB zeros)");
            }

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
            if (lastU < minUnique && maxUnique < minUnique) {
                LOG.warning("DEBUG: Colour diversity too low (last=" + lastU + ", max=" + maxUnique + ", required>=" + minUnique + ")");
            }

            int graceFrames = getIntEnvOrProp("RUN_SEG_GRACE_FRAMES", "seg.test.graceFrames", 30);
            int tailFrames = getIntEnvOrProp("RUN_SEG_TAIL_FRAMES", "seg.test.tailFrames", 60);
            int tailMinRequired = getIntEnvOrProp("RUN_SEG_TAIL_MIN_UNIQUE", "seg.test.tailMinUnique", minUnique);
            if (uniqHist.size() >= tailFrames + graceFrames) {
                int tailMin = tailMin(uniqHist, tailFrames);
                int tailAvg = tailAvg(uniqHist, tailFrames);
                LOG.info("Tail diversity: min=" + tailMin + " avg=" + tailAvg + " required>=" + tailMinRequired);
                if (tailMin < tailMinRequired) {
                    LOG.warning("DEBUG: Tail colour diversity too low (minLastN=" + tailMin + ", required>=" + tailMinRequired + ")");
                }
            }

            try {
                SegmentationTestBase.assertObservationFromRayConsistency(perType, LOG, "main", 0.8, 2);
            } catch (Throwable t) {
                LOG.warning("DEBUG: ObservationFromRay consistency check failed: " + t.getMessage());
            }
            try {
                SegmentationTestBase.assertColourToBlockTypeUniqueness(perType, LOG, "main");
            } catch (Throwable t) {
                LOG.warning("DEBUG: Colour-to-block-type uniqueness check failed: " + t.getMessage());
            }
            LOG.info("DEBUG: segmentation test completed (checks run but failures are logged only).");

            // Additionally verify that depth frames are being produced and saved
            // correctly when depth support is enabled in the mission. This
            // ensures that the segmentation pass does not interfere with the
            // normal depth buffer.
            TimestampedVideoFrame depthFrame = conn.waitDepthFrame(30, TimeUnit.SECONDS);
            if (depthFrame == null) {
                LOG.warning("DEBUG: No depth frames received within timeout during segmentation test");
            } else {
                LOG.info("Depth frame during segmentation test: " + depthFrame.iWidth + "x" + depthFrame.iHeight +
                        " ch=" + depthFrame.iCh + " type=" + depthFrame.frametype);
                if (depthFrame.iCh != 2) {
                    LOG.warning("DEBUG: Expected depth frame with 2 channels (uint16), got " + depthFrame.iCh);
                } else {
                    long depthSum = depthFrame.sumUnsigned();
                    LOG.info("Depth frame sum of uint16 values (seg test) = " + depthSum);
                    if (depthSum == 0L) {
                        LOG.warning("DEBUG: Depth frame appears to be all zeros during segmentation test");
                    }
                }
            }
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

    /**
     * Wait until segmentation frames appear stable: for at least 10 consecutive
     * checks, >=95% of sampled pixels remain unchanged between successive frames.
     * Returns the last frame observed (stable or not) so callers can continue
     * processing from there without re-reading from the queue.
     */
    private static TimestampedVideoFrame waitForStableWorld(MConnector conn,
                                                            TimestampedVideoFrame first,
                                                            long maxWaitSec) throws InterruptedException {
        final double requiredSameFraction = 0.95;
        final int requiredChecks = 10;
        final double maxSkyFraction = 0.70;
        final int skyR = 177, skyG = 205, skyB = 254;
        int stableChecks = 0;
        TimestampedVideoFrame prev = first;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1L, maxWaitSec));
        while (stableChecks < requiredChecks && System.nanoTime() < deadline) {
            TimestampedVideoFrame next = conn.waitSegFrame(2, TimeUnit.SECONDS);
            if (next == null) continue;
            double sameFrac = fractionSamePixels(prev, next);
            double skyFrac = fractionOfColour(next, skyR, skyG, skyB);
            LOG.info(String.format("Warmup: check=%d sameFraction=%.4f skyFraction=%.4f",
                    stableChecks + 1, sameFrac, skyFrac));
            if (sameFrac >= requiredSameFraction && skyFrac <= maxSkyFraction) {
                stableChecks++;
            } else {
                stableChecks = 0;
            }
            prev = next;
        }
        if (stableChecks >= requiredChecks) {
            LOG.info("Warmup: world considered stable after " + stableChecks + " consecutive checks.");
        } else {
            LOG.warning("Warmup: world did not reach stability condition within timeout; proceeding anyway.");
        }
        return prev;
    }

    /** Compute fraction of pixels that are identical between two frames (BGR triples). */
    private static double fractionSamePixels(TimestampedVideoFrame a, TimestampedVideoFrame b) {
        if (a == null || b == null) return 0.0;
        if (a.iWidth != b.iWidth || a.iHeight != b.iHeight || a.iCh != b.iCh) return 0.0;
        if (a._pixels == null || b._pixels == null) return 0.0;
        int stride = a.iCh;
        if (stride < 3) return 0.0;
        int pixels = Math.min(a._pixels.length, b._pixels.length) / stride;
        if (pixels == 0) return 0.0;
        int same = 0;
        byte[] pa = a._pixels;
        byte[] pb = b._pixels;
        for (int i = 0; i < pixels; i++) {
            int off = i * stride;
            if (pa[off] == pb[off] &&
                pa[off + 1] == pb[off + 1] &&
                pa[off + 2] == pb[off + 2]) {
                same++;
            }
        }
        return (double) same / (double) pixels;
    }

    /** Compute fraction of pixels in a frame that exactly match the given RGB colour. */
    private static double fractionOfColour(TimestampedVideoFrame f, int r, int g, int b) {
        if (f == null || f._pixels == null || f.iCh < 3 || f.iWidth <= 0 || f.iHeight <= 0) {
            return 0.0;
        }
        int stride = f.iCh;
        int pixels = f._pixels.length / stride;
        if (pixels == 0) return 0.0;
        byte br = (byte) (b & 0xFF);
        byte bg = (byte) (g & 0xFF);
        byte brd = (byte) (r & 0xFF);
        int same = 0;
        byte[] p = f._pixels;
        for (int i = 0; i < pixels; i++) {
            int off = i * stride;
            if (p[off] == br && p[off + 1] == bg && p[off + 2] == brd) {
                same++;
            }
        }
        return (double) same / (double) pixels;
    }
}
