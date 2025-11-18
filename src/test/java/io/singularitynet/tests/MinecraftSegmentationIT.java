package io.singularitynet.tests;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * JUnit wrapper to run the integration harness when RUN_SEG_TEST=1 is set.
 * Prefer using: gradlew runSegTest
 */
public class MinecraftSegmentationIT {
    @Test
    void launchesAndReceivesSegmentationFrames() throws Exception {
        assumeTrue("1".equals(System.getenv().getOrDefault("RUN_SEG_TEST", "0")),
                "Set RUN_SEG_TEST=1 to run this integration test.");
        MinecraftSegmentationTestMain.main(new String[]{ System.getProperty("mission", "mission.xml") });
    }
}

