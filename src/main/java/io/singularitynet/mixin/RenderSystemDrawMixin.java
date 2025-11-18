package io.singularitynet.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import io.singularitynet.utils.TextureHelper;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.client.gl.ShaderProgram;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderSystem.class)
public abstract class RenderSystemDrawMixin {

    private static final Logger LOGGER = LogManager.getLogger(RenderSystemDrawMixin.class);

    @Inject(method = "drawElements(III)V", at = @At("HEAD"))
    private static void vereya$applyAnnotateUniforms(int mode, int count, int type, CallbackInfo ci) {
        if (!TextureHelper.isProducingColourMap() || !TextureHelper.colourmapFrame) {
            return;
        }
        // If drawing blocks and we know the current block type, force a stable
        // per-type colour regardless of the last bound texture.
        if (TextureHelper.isDrawingBlock()) {
            TextureHelper.setPendingColourForCurrentBlock();
        } else {
            // If drawing with the block atlas bound and we know the current block type,
            // force a stable per-type colour before uploading.
            TextureHelper.updateAtlasOverrideColourForCurrentBlock();
        }
        ShaderProgram program = RenderSystem.getShader();
        if (program == null) {
            return;
        }
        TextureHelper.applyPendingColourToProgram(program);
        int[] pending = TextureHelper.getPendingColourRGB();
        GlUniform r = program.getUniform("entityColourR");
        GlUniform g = program.getUniform("entityColourG");
        GlUniform b = program.getUniform("entityColourB");
        if (r != null && g != null && b != null) {
            LOGGER.info("RenderSystemDrawMixin: applying colour R:{} G:{} B:{}", pending[0], pending[1], pending[2]);
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
        GlUniform grid = program.getUniform("atlasGrid");
        if (grid != null) {
            grid.set(128);
            grid.upload();
        }
    }
}
