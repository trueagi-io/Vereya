package io.singularitynet.mixin;

import io.singularitynet.utils.SegmentationColours;
import net.minecraft.client.render.BuiltBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BuiltBuffer.class)
public abstract class BuiltBufferColoursMixin implements SegmentationColours {
    @Unique private int[] vereya$colours;

    public int[] vereya$getColours() { return vereya$colours; }
    public void vereya$setColours(int[] colours) { vereya$colours = colours; }

    // CPU labels share the mesh's lifetime; after upload the VertexBuffer owns a GPU copy.
    @Inject(method = "close", at = @At("RETURN"))
    private void vereya$releaseColours(CallbackInfo callback) { vereya$colours = null; }
}
