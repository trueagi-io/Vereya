package io.singularitynet.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import io.singularitynet.utils.TextureHelper;
import net.minecraft.client.gl.ShaderProgram;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

/**
 * Globally enforces the annotate shader during the segmentation pass by
 * intercepting RenderSystem.setShader at the source. This covers draw paths
 * outside WorldRenderer (eg entity rendering) to ensure a stable per-type
 * colour for entities and blocks.
 */
@Mixin(RenderSystem.class)
public abstract class RenderSystemShaderSwapMixin {

    private static final ThreadLocal<Boolean> VEREYA$GUARD = ThreadLocal.withInitial(() -> false);

    @Inject(method = "setShader", at = @At("HEAD"), cancellable = true)
    private static void vereya$swapToAnnotate(Supplier<ShaderProgram> supplier, CallbackInfo ci) {
        if (VEREYA$GUARD.get()) return;
        if (!TextureHelper.isProducingColourMap() || !TextureHelper.colourmapFrame) {
            return;
        }
        try {
            VEREYA$GUARD.set(true);
            ShaderProgram original = supplier.get();
            if (original == null) {
                return; // let vanilla handle nulls
            }
            ShaderProgram annotate = TextureHelper.getAnnotateProgramForFormat(original.getFormat());
            // Apply the currently pending uniform colour/debug to the annotate program
            TextureHelper.applyPendingColourToProgram(annotate);
            // Re-route the shader selection to the annotate program
            RenderSystem.setShader(() -> annotate);
            ci.cancel();
        } finally {
            VEREYA$GUARD.set(false);
        }
    }
}

