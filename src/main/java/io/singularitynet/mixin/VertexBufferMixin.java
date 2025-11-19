package io.singularitynet.mixin;

import io.singularitynet.utils.TextureHelper;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.util.Identifier;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gl.VertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VertexBuffer.class)
public abstract class VertexBufferMixin {

    @ModifyVariable(method = "draw(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lnet/minecraft/client/gl/ShaderProgram;)V",
                    at = @At("HEAD"),
                    argsOnly = true)
    private ShaderProgram vereya$swapProgram(ShaderProgram original) {
        if (!TextureHelper.isProducingColourMap() || !TextureHelper.colourmapFrame || original == null) {
            return original;
        }
        ShaderProgram annotate = TextureHelper.getAnnotateProgramForFormat(original.getFormat());
        TextureHelper.applyPendingColourToProgram(annotate);
        return annotate;
    }

    @Inject(method = "draw(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lnet/minecraft/client/gl/ShaderProgram;)V", at = @At("HEAD"))
    private void vereya$markBlockDrawStart(org.joml.Matrix4f pos, org.joml.Matrix4f proj, ShaderProgram program, CallbackInfo ci) {
        if (!TextureHelper.isProducingColourMap() || !TextureHelper.colourmapFrame) return;
        // If rendering an entity, enforce entity colour and do NOT toggle block-draw based on atlas binds.
        if (TextureHelper.hasCurrentEntity()) {
            TextureHelper.setPendingColourForCurrentEntity();
            return;
        }
        // Only consider a block draw if we actually have a current block type captured.
        Identifier last = TextureHelper.getLastBoundTexture();
        boolean isAtlas = last != null && (SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE.equals(last)
                || (last.getPath() != null && last.getPath().contains("textures/atlas/")));
        // Rely on BlockRenderManagerMixin to toggle block draws; do nothing here
    }

    @Inject(method = "draw(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lnet/minecraft/client/gl/ShaderProgram;)V", at = @At("TAIL"))
    private void vereya$markBlockDrawEnd(org.joml.Matrix4f pos, org.joml.Matrix4f proj, ShaderProgram program, CallbackInfo ci) {
        if (!TextureHelper.isProducingColourMap() || !TextureHelper.colourmapFrame) return;
        TextureHelper.setDrawingBlock(false);
    }
}
