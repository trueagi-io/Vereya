package io.singularitynet.mixin;

import io.singularitynet.utils.TextureHelper;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gl.VertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

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
}
