package io.singularitynet.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import io.singularitynet.utils.TextureHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks low-level texture binding so that any call to GlStateManager._bindTexture
 * (eg from AbstractTexture.bindTexture) participates in segmentation logging and
 * colour selection via TextureHelper.
 */
@Mixin(GlStateManager.class)
public abstract class GlStateManagerBindTextureMixin {

    @Inject(method = "_bindTexture(I)V", at = @At("HEAD"))
    private static void vereya$onBindTexture(int glId, CallbackInfo ci) {
        TextureHelper.onTextureBoundGlId(glId);
    }
}
