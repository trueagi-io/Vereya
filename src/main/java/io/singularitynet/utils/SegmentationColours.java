package io.singularitynet.utils;

/** CPU labels carried from chunk building to GPU upload: one 0xRRGGBB per vertex,
 * in mesh order; null means no label buffer, not an all-black buffer. */
public interface SegmentationColours {
    int[] vereya$getColours();
    void vereya$setColours(int[] colours);
}
