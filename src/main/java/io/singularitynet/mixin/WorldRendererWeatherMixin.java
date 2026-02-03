package io.singularitynet.mixin;

import io.singularitynet.utils.TextureHelper;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skip weather rendering (rain/snow) during the segmentation pass so it
 * doesn't overwrite the segmentation colours with transient precipitation.
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererWeatherMixin {

    @Inject(method = "renderWeather(Lnet/minecraft/client/render/LightmapTextureManager;FDDD)V",
            at = @At("HEAD"),
            cancellable = true)
    private void vereya$skipWeatherDuringSegmentation(LightmapTextureManager lightmap,
                                                      float tickDelta,
                                                      double cameraX,
                                                      double cameraY,
                                                      double cameraZ,
                                                      CallbackInfo ci) {
        if (TextureHelper.isProducingColourMap() && TextureHelper.colourmapFrame) {
            ci.cancel();
        }
    }
}
