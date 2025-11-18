package io.singularitynet.mixin;

import io.singularitynet.utils.TextureHelper;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.WorldRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses the regular sky draw during the segmentation pass; the
 * segmentation framebuffer is pre-filled with the mission sky colour.
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererSkyMixin {

    @Inject(method = "renderSky(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FLnet/minecraft/client/render/Camera;ZLjava/lang/Runnable;)V", at = @At("HEAD"), cancellable = true)
    private void vereya$skipSky(Matrix4f matrix4f,
                                Matrix4f projectionMatrix,
                                float tickDelta,
                                Camera camera,
                                boolean thickFog,
                                Runnable fogCallback,
                                CallbackInfo ci) {
        if (TextureHelper.isProducingColourMap() && TextureHelper.colourmapFrame) {
            ci.cancel();
        }
    }
}
