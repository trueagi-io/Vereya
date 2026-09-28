package io.singularitynet.tests;

import org.json.JSONObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class BlockSegmentationTestMain {
    private static Path output;
    private static boolean textureMode;
    private static final boolean LEGACY_METADATA = Boolean.getBoolean("seg.test.legacyMetadata");
    private static final Map<String, String> measuredColours = new LinkedHashMap<>();

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) throw new IllegalArgumentException("Pass a client launcher script and optional block/texture mode");
        String mode = args.length == 2 ? args[1] : "block";
        if (!mode.equals("block") && !mode.equals("texture")) throw new IllegalArgumentException("Unknown segmentation mode: " + mode);
        textureMode = mode.equals("texture");
        output = Path.of("build", "block-segmentation", (LEGACY_METADATA ? "reference-" : "")
                + mode + "-run-" + System.currentTimeMillis());
        Files.createDirectories(output);
        for (String stream : new String[]{"seg", "video", "depth", "obs"}) {
            System.setProperty(stream + ".test.saveDir", output.resolve(stream).toString());
        }
        String mission = Files.readString(Path.of("src/test/resources/mission.xml"))
                .replace("mode=\"Survival\"", "mode=\"Creative\"")
                .replace("<AllowSpawning>true</AllowSpawning>", "<AllowSpawning>false</AllowSpawning>")
                // Otherwise the movement controller can overwrite the /tp camera orientation.
                .replace("<ContinuousMovementCommands turnSpeedDegs=\"420\" />", "");
        MConnector connector = new MConnector(mission);
        connector.startServers();
        connector.startFromLogs(new ByteArrayInputStream(new byte[0]), Duration.ZERO);
        Path log = output.resolve("client.log");
        Path paletteFile = output.resolve("block-palette.json").toAbsolutePath();
        ProcessBuilder builder = new ProcessBuilder("bash", args[0]);
        builder.environment().merge("JAVA_TOOL_OPTIONS", "-Dvereya.seg.mode=" + mode
                        + " -Dvereya.seg.paletteFile=\"" + paletteFile + "\"",
                (previous, option) -> previous + " " + option);
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        Process client = builder.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
            int port = -1;
            while (System.nanoTime() < deadline && client.isAlive()) {
                String text = Files.readString(log);
                var matcher = Pattern.compile("MCP: (\\d+)").matcher(text);
                if (matcher.find() && text.contains("CLIENT enter state: DORMANT")) {
                    port = Integer.parseInt(matcher.group(1));
                    break;
                }
                Thread.sleep(200);
            }
            if (port < 0) throw new AssertionError("Client did not start; see " + log);
            connector.sendMissionInit("127.0.0.1", port);
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            boolean receivedFrame = false;
            while (client.isAlive() && System.nanoTime() < deadline) {
                if (connector.waitSegFrame(1, TimeUnit.SECONDS) != null) { receivedFrame = true; break; }
            }
            if (!receivedFrame) throw new AssertionError("No segmentation frames; see " + log);
            JSONObject palette = textureMode ? null : new JSONObject(Files.readString(paletteFile));
            if (textureMode && Files.exists(paletteFile)) throw new AssertionError("Texture mode exported a block palette");
            connector.sendCommand("chat /gamemode spectator");
            connector.sendCommand("chat /gamerule randomTickSpeed 0");
            connector.sendCommand("chat /gamerule doMobSpawning false");
            connector.sendCommand("chat /fill -3 94 -3 12 107 3 air");
            String[] blocks = {"grass_block", "dirt", "oak_log"};
            for (int index = 0; index < blocks.length; index++) {
                connector.sendCommand("chat /setblock " + (index * 4) + " 100 0 " + blocks[index]);
            }
            Thread.sleep(6000);
            if (textureMode) {
                int grassBottom = capture(connector, "grass-bottom", new double[]{0.5, 96, 0.5, 0, -90}, null);
                capture(connector, "dirt-bottom", new double[]{4.5, 96, 0.5, 0, -90}, grassBottom);
                int grassTop = capture(connector, "grass-top", new double[]{0.5, 102, 0.5, 0, 90}, null);
                if (grassTop == grassBottom) throw new AssertionError("Texture mode collapsed different grass textures");
                // Only the underside shares dirt's sprite; similar-looking side pixels
                // belong to a different sprite and need not share dirt's label.
                capture(connector, "grass-side", new double[]{0.5, 98.88, -2, 0, 0}, null);
                capture(connector, "dirt-side", new double[]{4.5, 98.88, -2, 0, 0}, null);
                System.out.println("PASS: texture mode shares dirt/grass undersides and distinguishes grass top. Captures: " + output);
                return;
            }
            HashSet<Integer> blockColours = new HashSet<>();
            int dirtColour = 0;
            for (int index = 0; index < blocks.length; index++) {
                double centre = index * 4 + 0.5;
                double[][] poses = {
                        {centre, 102, 0.5, 0, 90}, {centre, 96, 0.5, 0, -90},
                        {centre, 98.88, -2, 0, 0}, {centre, 98.88, 3, 180, 0},
                        {centre - 2.5, 98.88, 0.5, -90, 0}, {centre + 2.5, 98.88, 0.5, 90, 0}
                };
                int expected = Integer.parseInt(palette.getString("minecraft:" + blocks[index]).substring(1), 16);
                for (int face = 0; face < poses.length; face++) {
                    int colour = capture(connector, blocks[index] + "-" + face, poses[face], expected);
                    if (colour != expected) {
                        throw new AssertionError(blocks[index] + " differs on face " + face);
                    }
                }
                if (!blockColours.add(expected)) throw new AssertionError("Block colour collision: " + blocks[index]);
                if (index == 1) dirtColour = expected;
            }
            connector.sendCommand("chat /setblock 0 100 0 dirt");
            Thread.sleep(2000);
            int rebuilt = capture(connector, "grass-replaced-with-dirt", new double[]{0.5, 96, 0.5, 0, -90}, dirtColour);
            if (rebuilt != dirtColour) throw new AssertionError("Stale colour after chunk rebuild");
            System.out.println("PASS: six faces per block, distinct block colours, rebuild. Captures: " + output);
        } finally {
            var descendants = client.descendants().toList();
            descendants.forEach(ProcessHandle::destroy);
            client.destroy();
            client.waitFor(5, TimeUnit.SECONDS);
            descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            if (client.isAlive()) client.destroyForcibly();
        }
    }

    private static int capture(MConnector connector, String name, double[] pose, Integer expected) throws Exception {
        connector.sendCommand(String.format(java.util.Locale.ROOT, "chat /tp @s %.3f %.3f %.3f %.1f %.1f",
                pose[0], pose[1], pose[2], pose[3], pose[4]));
        Thread.sleep(2000);
        while (connector.waitSegFrame(1, TimeUnit.MILLISECONDS) != null) {}
        while (connector.waitVideoFrame(1, TimeUnit.MILLISECONDS) != null) {}
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        TimestampedVideoFrame frame = null;
        String lastPose = "no frame";
        int matchingFrames = 0;
        int stableFrames = 0;
        Integer previousColour = null;
        while (System.nanoTime() < deadline) {
            frame = connector.waitSegFrame(2, TimeUnit.SECONDS);
            if (frame != null) lastPose = String.format(java.util.Locale.ROOT,
                    "x=%.3f y=%.3f z=%.3f yaw=%.1f pitch=%.1f colour=#%06X expected=%s",
                    frame.xPos, frame.yPos, frame.zPos, frame.yaw, frame.pitch, frame.getCenterRGB(), expected);
            if (matchesPose(frame, pose)) {
                matchingFrames++;
                int colour = frame.getCenterRGB();
                // The legacy atlas shader's channels are below 255; exclude sky clears.
                boolean colourMatches = expected != null ? colour == expected
                        : ((colour >> 16) & 255) < 255 && ((colour >> 8) & 255) < 255 && (colour & 255) < 255;
                // Texture-mode faces can contain multiple labels from layered sprites.
                boolean uniform = textureMode || isUniformAtCentre(frame);
                stableFrames = colourMatches && uniform && previousColour != null && colour == previousColour
                        ? stableFrames + 1 : 0;
                previousColour = colour;
                // Player metadata can lead the rendered camera/FBO during a teleport.
                if (matchingFrames >= 5 && stableFrames >= 3) break;
            } else {
                matchingFrames = 0;
                stableFrames = 0;
            }
            frame = null;
        }
        if (frame == null) throw new AssertionError("No stable face for " + name + "; matching frames: "
                + matchingFrames + "; last frame: " + lastPose);
        save(frame, name + "-seg.png");
        int colour = frame.getCenterRGB();
        while (connector.waitVideoFrame(1, TimeUnit.MILLISECONDS) != null) {}
        TimestampedVideoFrame video = null;
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            video = connector.waitVideoFrame(1, TimeUnit.SECONDS);
            if (matchesPose(video, pose)) break;
            video = null;
        }
        if (video == null) throw new AssertionError("No RGB frame: " + name);
        save(video, name + "-rgb.png");
        measuredColours.put(name, String.format(java.util.Locale.ROOT, "#%06X", colour));
        Files.writeString(output.resolve("captured-colours.json"), new JSONObject(measuredColours).toString(2));
        System.out.printf("%s #%06X%n", name, colour);
        return colour;
    }

    private static boolean isUniformAtCentre(TimestampedVideoFrame frame) {
        int colour = frame.getCenterRGB();
        int radius = Math.min(frame.iWidth, frame.iHeight) / 9;
        for (int deltaY = -radius; deltaY <= radius; deltaY++) {
            for (int deltaX = -radius; deltaX <= radius; deltaX++) {
                if (frame.getRGB(frame.iWidth / 2 + deltaX, frame.iHeight / 2 + deltaY) != colour) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean matchesPose(TimestampedVideoFrame frame, double[] pose) {
        return frame != null && Math.abs(frame.xPos - pose[0]) < 0.1
                // Historical clients wrote X into all three position fields.
                && (LEGACY_METADATA || (Math.abs(frame.yPos - pose[1]) < 0.1 && Math.abs(frame.zPos - pose[2]) < 0.1))
                && Math.abs(frame.pitch - pose[4]) < 0.1
                && Math.abs(Math.IEEEremainder(frame.yaw - pose[3], 360)) < 0.1;
    }

    private static void save(TimestampedVideoFrame frame, String name) throws Exception {
        BufferedImage image = new BufferedImage(frame.iWidth, frame.iHeight, BufferedImage.TYPE_INT_RGB);
        // Captured OpenGL rows start at the bottom; PNG rows start at the top.
        for (int row = 0; row < frame.iHeight; row++) {
            for (int column = 0; column < frame.iWidth; column++) {
                image.setRGB(column, frame.iHeight - row - 1, frame.getRGB(column, row));
            }
        }
        ImageIO.write(image, "png", output.resolve(name).toFile());
    }
}
