package io.singularitynet.utils;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public final class BlockColourPalette {
    private static final int CAPACITY = 192 * 192 * 192;

    private BlockColourPalette() {}

    public static Map<String, Integer> create(Collection<String> identifiers) {
        Map<String, Integer> colours = new LinkedHashMap<>();
        // Stable for the same registry set, regardless of registration order.
        // Adding/removing IDs can shift colours, so export the palette with a dataset.
        identifiers.stream().distinct().sorted().forEach(identifier -> {
            int index = colours.size();
            if (index >= CAPACITY) throw new IllegalArgumentException("Block palette exhausted");
            // Coprime with CAPACITY: permutes the colour space without collisions.
            int code = (int) ((index * 104729L) % CAPACITY);
            // Base-192 digits keep every channel in 32..223 without losing uniqueness.
            int red = 32 + code % 192;
            int green = 32 + (code / 192) % 192;
            int blue = 32 + code / (192 * 192);
            colours.put(identifier, (red << 16) | (green << 8) | blue);
        });
        return Map.copyOf(colours);
    }
}
