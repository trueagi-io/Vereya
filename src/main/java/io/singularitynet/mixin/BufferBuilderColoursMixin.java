package io.singularitynet.mixin;

import io.singularitynet.utils.BlockSegmentation;
import io.singularitynet.utils.SegmentationColours;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;

@Mixin(BufferBuilder.class)
public abstract class BufferBuilderColoursMixin {
    @Shadow private int vertexCount;
    @Unique private int[] vereya$colours;

    @Inject(method = "beginVertex", at = @At("RETURN"))
    private void vereya$captureColour(CallbackInfoReturnable<Long> callback) {
        Integer colour = BlockSegmentation.currentColour();
        // Leave ordinary/texture-mode buffers allocation-free. Once labels exist, retain
        // an entry for every vertex (zero outside block context) to preserve mesh indexing.
        if (colour == null && vereya$colours == null) return;
        if (vereya$colours == null) vereya$colours = new int[Math.max(256, vertexCount)];
        if (vertexCount > vereya$colours.length) {
            vereya$colours = Arrays.copyOf(vereya$colours, Math.max(vertexCount, vereya$colours.length * 2));
        }
        // beginVertex has already incremented vertexCount at this RETURN injection.
        vereya$colours[vertexCount - 1] = colour == null ? 0 : colour;
    }

    @Inject(method = "build", at = @At("RETURN"))
    private void vereya$attachColours(CallbackInfoReturnable<BuiltBuffer> callback) {
        if (callback.getReturnValue() != null && vereya$colours != null) {
            // Detach from the builder for the worker-to-render-thread upload handoff.
            ((SegmentationColours) callback.getReturnValue()).vereya$setColours(
                    Arrays.copyOf(vereya$colours, vertexCount));
        }
        vereya$colours = null;
    }
}
