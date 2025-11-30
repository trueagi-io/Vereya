package io.singularitynet.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import io.singularitynet.utils.TextureHelper;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks RenderSystem.setShaderTexture so that segmentation logging and
 * colour selection see all textures bound via render phases, not just
 * those routed through TextureManager.bindTextureInner.
 */
@Mixin(RenderSystem.class)
public abstract class RenderSystemTextureMixin {

    @Inject(
            method = "setShaderTexture(ILnet/minecraft/util/Identifier;)V",
            at = @At("HEAD")
    )
    private static void vereya$onSetShaderTexture(int unit, Identifier id, CallbackInfo ci) {
        TextureHelper.onTextureBound(id);
    }
}
