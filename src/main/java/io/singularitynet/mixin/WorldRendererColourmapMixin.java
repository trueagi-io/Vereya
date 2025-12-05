package io.singularitynet.mixin;

import io.singularitynet.utils.TextureHelper;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.WorldRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs a dedicated segmentation render pass immediately before the default
 * world render when colour-map production is active. The second invocation of
 * {@link WorldRenderer#render} executes the normal pipeline so the on-screen
 * view remains unchanged.
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererColourmapMixin {

    private static final ThreadLocal<Boolean> VEREYA$SEGMENTATION_RENDERING = ThreadLocal.withInitial(() -> false);

    @Inject(method = "render", at = @At("TAIL"))
    private void vereya$renderColourMap(RenderTickCounter tickCounter,
                                        boolean renderBlockOutline,
                                        Camera camera,
                                        GameRenderer gameRenderer,
                                        LightmapTextureManager lightmap,
                                        Matrix4f positionMatrix,
                                        Matrix4f projectionMatrix,
                                        CallbackInfo ci) {
        if (!TextureHelper.isProducingColourMap()) {
            return;
        }
        if (VEREYA$SEGMENTATION_RENDERING.get()) {
            return;
        }

        VEREYA$SEGMENTATION_RENDERING.set(true);
        try {
            TextureHelper.colourmapFrame = true;
            TextureHelper.beginSegmentationPass();
            Matrix4f segPosition = new Matrix4f(positionMatrix);
            Matrix4f segProjection = new Matrix4f(projectionMatrix);
            ((WorldRenderer) (Object) this).render(tickCounter,
                    renderBlockOutline,
                    camera,
                    gameRenderer,
                    lightmap,
                    segPosition,
                    segProjection);
        } finally {
            TextureHelper.endSegmentationPass();
            TextureHelper.colourmapFrame = false;
            VEREYA$SEGMENTATION_RENDERING.set(false);
        }
    }
}
