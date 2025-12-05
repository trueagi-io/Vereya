package io.singularitynet.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import io.singularitynet.utils.TextureHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks the integer-overload of RenderSystem.setShaderTexture so that binds
 * which only specify a GL id (rather than an Identifier) are still visible to
 * the segmentation logging and colour selection logic.
 */
@Mixin(RenderSystem.class)
public abstract class RenderSystemTextureGlMixin {

    @Inject(
            method = "setShaderTexture(II)V",
            at = @At("HEAD")
    )
    private static void vereya$onSetShaderTextureGl(int unit, int glId, CallbackInfo ci) {
        TextureHelper.onTextureBoundGlId(glId);
    }
}
