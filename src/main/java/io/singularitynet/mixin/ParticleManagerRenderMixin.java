package io.singularitynet.mixin;

import io.singularitynet.utils.TextureHelper;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.LightmapTextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the particle render scope so segmentation can unify particle colours.
 */
@Mixin(ParticleManager.class)
public abstract class ParticleManagerRenderMixin {

    @Inject(method = "renderParticles(Lnet/minecraft/client/render/LightmapTextureManager;Lnet/minecraft/client/render/Camera;F)V",
            at = @At("HEAD"))
    private void vereya$beginParticleRender(LightmapTextureManager lightmap,
                                            Camera camera,
                                            float tickDelta,
                                            CallbackInfo ci) {
        if (TextureHelper.isProducingColourMap() && TextureHelper.colourmapFrame) {
            TextureHelper.setRenderingParticles(true);
        }
    }

    @Inject(method = "renderParticles(Lnet/minecraft/client/render/LightmapTextureManager;Lnet/minecraft/client/render/Camera;F)V",
            at = @At("TAIL"))
    private void vereya$endParticleRender(LightmapTextureManager lightmap,
                                          Camera camera,
                                          float tickDelta,
                                          CallbackInfo ci) {
        TextureHelper.setRenderingParticles(false);
    }
}
