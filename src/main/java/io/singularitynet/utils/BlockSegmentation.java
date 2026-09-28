package io.singularitynet.utils;

import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import org.apache.logging.log4j.LogManager;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public final class BlockSegmentation {
    private static final boolean BLOCK_MODE = switch (System.getProperty("vereya.seg.mode", "texture")
            .trim().toLowerCase(Locale.ROOT)) {
        case "block" -> true;
        case "texture" -> false;
        default -> throw new IllegalArgumentException("vereya.seg.mode must be block or texture");
    };
    // Chunk workers build in parallel; each vertex must inherit its own worker's block label.
    private static final ThreadLocal<Integer> BUILD_COLOUR = new ThreadLocal<>();
    // Render-thread-only scope of a draw with the separate label VBO bound.
    private static boolean drawingColours;

    // Defer registry access until colours are needed, rather than when mode is first queried.
    private static final class Palette {
        private static final Map<String, Integer> COLOURS = BlockColourPalette.create(
                Registries.BLOCK.getIds().stream().map(Object::toString).toList());
    }

    private BlockSegmentation() {}

    /** Chosen once at client startup so chunk buffers and draws use the same mode. */
    public static boolean isEnabled() { return BLOCK_MODE; }

    public static int colour(String identifier) {
        Integer colour = Palette.COLOURS.get(identifier);
        if (colour == null) throw new IllegalArgumentException("Unknown block: " + identifier);
        return colour;
    }

    public static void exportPaletteIfRequested() {
        if (!isEnabled()) return;
        String filename = System.getProperty("vereya.seg.paletteFile");
        if (filename == null || filename.isBlank()) return;
        Map<String, String> colours = new TreeMap<>();
        Palette.COLOURS.forEach((id, rgb) -> colours.put(id, String.format(Locale.ROOT, "#%06X", rgb)));
        try {
            Path path = Path.of(filename).toAbsolutePath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, new JSONObject(colours).toString(2));
        } catch (IOException exception) {
            LogManager.getLogger(BlockSegmentation.class).warn("Cannot export block segmentation palette", exception);
        }
    }

    public static void begin(BlockState state) {
        if (!isEnabled()) return;
        // Use the owning block ID, not its state, face sprite, biome tint or lighting.
        BUILD_COLOUR.set(colour(Registries.BLOCK.getId(state.getBlock()).toString()));
    }

    public static Integer currentColour() { return BUILD_COLOUR.get(); }
    public static void end() { BUILD_COLOUR.remove(); }
    public static boolean isDrawingColours() { return drawingColours; }
    public static void setDrawingColours(boolean drawing) { drawingColours = drawing; }
}
