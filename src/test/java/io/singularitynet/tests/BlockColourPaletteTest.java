package io.singularitynet.tests;

import io.singularitynet.utils.BlockColourPalette;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class BlockColourPaletteTest {
    @Test
    void assignsDistinctColoursIndependentOfInputOrder() {
        var names = List.of("minecraft:grass_block", "minecraft:dirt", "minecraft:oak_log");
        var colours = BlockColourPalette.create(names);
        assertEquals(3, new HashSet<>(colours.values()).size());
        assertEquals(colours, BlockColourPalette.create(names.reversed()));
    }

    @Test
    void largePaletteHasNoCollisionsAndStaysInBlockRange() {
        var names = IntStream.range(0, 100000).mapToObj(index -> "test:block_" + index).toList();
        var colours = BlockColourPalette.create(names);
        assertEquals(names.size(), new HashSet<>(colours.values()).size());
        colours.values().forEach(colour -> {
            for (int shift : new int[]{0, 8, 16}) {
                int channel = (colour >> shift) & 255;
                assertTrue(channel >= 32 && channel <= 223);
            }
        });
    }
}
