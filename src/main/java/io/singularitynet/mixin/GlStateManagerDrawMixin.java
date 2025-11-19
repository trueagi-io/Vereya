package io.singularitynet.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import io.singularitynet.utils.TextureHelper;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.client.gl.ShaderProgram;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GlStateManager.class)
public abstract class GlStateManagerDrawMixin {

    private static final Logger LOGGER = LogManager.getLogger(GlStateManagerDrawMixin.class);

    @Inject(method = "_drawElements", at = @At("HEAD"))
    private static void vereya$updateUniforms(int mode, int count, int type, long indices, CallbackInfo ci) {
        if (!TextureHelper.isProducingColourMap() || !TextureHelper.colourmapFrame) {
            return;
        }
        ShaderProgram program = com.mojang.blaze3d.systems.RenderSystem.getShader();
        if (program == null) {
            return;
        }
        int[] pending = TextureHelper.getPendingColourRGB();
        GlUniform r = program.getUniform("entityColourR");
        GlUniform g = program.getUniform("entityColourG");
        GlUniform b = program.getUniform("entityColourB");
        if (r != null && g != null && b != null) {
            LOGGER.info("GlStateManagerDrawMixin: applying colour R:{} G:{} B:{}", pending[0], pending[1], pending[2]);
            r.set(pending[0]);
            g.set(pending[1]);
            b.set(pending[2]);
            r.upload();
            g.upload();
            b.upload();
        }
        GlUniform debug = program.getUniform("debugMode");
        if (debug != null) {
            debug.set(TextureHelper.getSegmentationDebugLevel());
            debug.upload();
        }
        GlUniform alpha = program.getUniform("respectAlpha");
        if (alpha != null) {
            alpha.set(TextureHelper.isRespectOpacity() ? 1 : 0);
            alpha.upload();
        }
        GlUniform grid = program.getUniform("atlasGrid");
        if (grid != null) {
            grid.set(32);
            grid.upload();
        }
        GlUniform lod = program.getUniform("atlasLod");
        if (lod != null) {
            lod.set(8);
            lod.upload();
        }
    }

    // Note: No _drawArrays injection (1.20.4 GlStateManager has no such target).
}
