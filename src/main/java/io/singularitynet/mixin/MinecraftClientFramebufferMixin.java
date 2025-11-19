package io.singularitynet.mixin;

import io.singularitynet.utils.TextureHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ensures the segmentation render pass targets the off-screen segmentation
 * framebuffer instead of the on-screen framebuffer. Without this, parts of the
 * render pipeline that explicitly re-bind the main framebuffer during a world
 * render (e.g., post-processing or weather layers) would override our manual
 * FBO binding and draw the segmentation colours into the visible game window.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientFramebufferMixin {

    @Inject(method = "getFramebuffer", at = @At("HEAD"), cancellable = true)
    private void vereya$returnSegmentationFboWhenActive(CallbackInfoReturnable<Framebuffer> cir) {
        if (TextureHelper.isProducingColourMap() && TextureHelper.colourmapFrame) {
            Framebuffer seg = TextureHelper.getSegmentationFramebuffer();
            if (seg != null) {
                cir.setReturnValue(seg);
            }
        }
    }
}

