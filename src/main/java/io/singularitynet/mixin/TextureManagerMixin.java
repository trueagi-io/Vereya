package io.singularitynet.mixin;

import io.singularitynet.utils.TextureHelper;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureManager.class)
public abstract class TextureManagerMixin {

    @Inject(method = "bindTextureInner", at = @At("TAIL"))
    private void vereya$afterBind(Identifier id, CallbackInfo ci) {
        TextureHelper.onTextureBound(id);
    }

    /**
     * After a texture is registered and has a GL id, record the mapping so we
     * can later recover the Identifier when only the integer id is available.
     */
    @Inject(method = "registerTexture", at = @At("TAIL"))
    private void vereya$afterRegisterTexture(Identifier id, AbstractTexture texture, CallbackInfo ci) {
        if (id == null || texture == null) return;
        try {
            int glId = texture.getGlId();
            TextureHelper.registerTextureGlId(id, glId);
        } catch (Throwable t) {
            // Best-effort; missing ids will simply appear as unmapped in logs.
        }
    }
}
