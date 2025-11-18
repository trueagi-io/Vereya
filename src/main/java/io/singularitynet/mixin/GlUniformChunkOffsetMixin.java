package io.singularitynet.mixin;

import io.singularitynet.utils.TextureHelper;
import net.minecraft.client.gl.GlUniform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GlUniform.class)
public abstract class GlUniformChunkOffsetMixin {

    @Shadow private String name;

    @Inject(method = "set(FFF)V", at = @At("HEAD"))
    private void vereya$captureChunkOffset(float x, float y, float z, CallbackInfo ci) {
        if ("ChunkOffset".equals(this.name)) {
            TextureHelper.updateChunkOffset(x, y, z);
        }
    }
}
