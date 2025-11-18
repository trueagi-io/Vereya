package io.singularitynet.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import io.singularitynet.utils.TextureHelper;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Supplier;

/**
 * During the segmentation pass, force Minecraft to use our annotate shader
 * programs in place of the normal rendertypes by redirecting calls to
 * RenderSystem.setShader. Outside the seg pass the normal supplier is used.
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererShaderMixin {

    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShader(Ljava/util/function/Supplier;)V"))
    private void vereya$swapShaderForSegmentation(Supplier<ShaderProgram> supplier) {
        if (TextureHelper.isProducingColourMap() && TextureHelper.colourmapFrame) {
            ShaderProgram orig = supplier.get();
            ShaderProgram annotate = TextureHelper.getAnnotateProgramForFormat(orig.getFormat());
            // Apply any pending colour/uniforms to the annotate program
            TextureHelper.applyPendingColourToProgram(annotate);
            RenderSystem.setShader(() -> annotate);
        } else {
            RenderSystem.setShader(supplier);
        }
    }
}

