package io.singularitynet.mixin;

import io.singularitynet.utils.TextureHelper;
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
}
