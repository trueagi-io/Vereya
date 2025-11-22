package io.singularitynet.tests;

import java.util.logging.Logger;

/**
 * Common colour/segmentation assertions shared by integration tests.
 * Utilities here operate on per-type colour samples collected by tests
 * and ObservationFromRay-derived data captured during runs.
 */
public final class SegmentationTestBase {
    private SegmentationTestBase() {}

    public static int getIntEnvOrProp(String envKey, String propKey, int def) {
        String v = System.getenv(envKey);
        if (v == null || v.isEmpty()) v = System.getProperty(propKey);
        if (v == null || v.isEmpty()) return def;
        try { return Integer.parseInt(v.trim()); } catch (Exception ignored) { return def; }
    }

    public static long getLongEnvOrProp(String envKey, String propKey, long def) {
        String v = System.getenv(envKey);
        if (v == null || v.isEmpty()) v = System.getProperty(propKey);
        if (v == null || v.isEmpty()) return def;
        try { return Long.parseLong(v.trim()); } catch (Exception ignored) { return def; }
    }

    /**
     * Verify per-type consistency using ObservationFromRay aggregation:
     * a type is inconsistent if it appears with multiple colours and the
     * dominant colour has < dominanceThreshold share of samples.
     * The check only triggers once at least minTypesWithSamples have data.
     */
    public static void assertObservationFromRayConsistency(java.util.Map<String, java.util.Map<Integer,Integer>> perTypeCounts,
                                                           Logger LOG,
                                                           String context,
                                                           double dominanceThreshold,
                                                           int minTypesWithSamples) {
        int inconsistent = 0; int withSamples = 0;
        java.util.List<String> inconsistentSummaries = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, java.util.Map<Integer,Integer>> e : perTypeCounts.entrySet()) {
            int total = e.getValue().values().stream().mapToInt(Integer::intValue).sum();
            if (total == 0) continue;
            withSamples++;
            int dominant = e.getValue().values().stream().mapToInt(Integer::intValue).max().orElse(0);
            double domFrac = total > 0 ? (dominant * 1.0 / total) : 1.0;
            if (e.getValue().size() > 1 && domFrac < dominanceThreshold) inconsistent++;
            if (e.getValue().size() > 1 && domFrac < dominanceThreshold) {
                java.util.List<String> cols = new java.util.ArrayList<>();
                e.getValue().entrySet().stream()
                        .sorted((a,b)->Integer.compare(b.getValue(), a.getValue()))
                        .limit(20)
                        .forEach(en -> cols.add(String.format("#%06X(x%d)", en.getKey() & 0xFFFFFF, en.getValue())));
                inconsistentSummaries.add("type=" + e.getKey() + " totalSamples=" + total + " dominantShare=" + String.format(java.util.Locale.ROOT, "%.2f", domFrac) + " colours=" + cols);
            }
        }
        if (withSamples >= minTypesWithSamples && inconsistent > 0) {
            LOG.info("Inconsistent per-type colours (" + context + "):");
            for (String s : inconsistentSummaries) LOG.info("  " + s);
            String msg = "ObservationFromRay consistency failed (" + context + "): " + inconsistent +
                    " types have multiple dominant colours (<" + (int)(dominanceThreshold * 100) + "% dominance)";
            LOG.severe(msg);
            throw new AssertionError(msg);
        }
        // Still print a compact summary to logs to aid debugging
        LOG.info("Per-type colours summary (" + context + "):");
        for (java.util.Map.Entry<String, java.util.Map<Integer,Integer>> e : perTypeCounts.entrySet()) {
            java.util.List<String> cols = new java.util.ArrayList<>();
            e.getValue().entrySet().stream().limit(10).forEach(en -> cols.add(String.format("#%06X(x%d)", en.getKey(), en.getValue())));
            LOG.info("  type=" + e.getKey() + " colours=" + e.getValue().size() + " sample=" + cols);
        }
    }

    /**
     * Assert that each observed colour maps to at most one block type (block types
     * are recognised by containing a namespace colon).
     */
    public static void assertColourToBlockTypeUniqueness(java.util.Map<String, java.util.Map<Integer,Integer>> perTypeCounts,
                                                         Logger LOG,
                                                         String context) {
        java.util.Map<Integer, java.util.Set<String>> colourToBlockTypes = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, java.util.Map<Integer,Integer>> e : perTypeCounts.entrySet()) {
            String type = e.getKey();
            boolean isBlockType = type.contains(":");
            if (!isBlockType) continue;
            for (java.util.Map.Entry<Integer,Integer> ce : e.getValue().entrySet()) {
                colourToBlockTypes.computeIfAbsent(ce.getKey(), k -> new java.util.HashSet<>()).add(type);
            }
        }
        int collisions = 0;
        java.util.List<String> collisionSummaries = new java.util.ArrayList<>();
        for (java.util.Map.Entry<Integer, java.util.Set<String>> c : colourToBlockTypes.entrySet()) {
            if (c.getValue().size() > 1) {
                collisions++;
                java.util.List<String> types = new java.util.ArrayList<>(c.getValue());
                collisionSummaries.add(String.format("colour=#%06X types=%s", c.getKey() & 0xFFFFFF, types));
            }
        }
        if (collisions > 0) {
            LOG.info("Colour->types collisions (" + context + "):");
            for (String s : collisionSummaries) LOG.info("  " + s);
            String msg = "Colour uniqueness failed (" + context + "): " + collisions + " colours mapped to multiple block types";
            LOG.severe(msg);
            throw new AssertionError(msg);
        }
    }
}
