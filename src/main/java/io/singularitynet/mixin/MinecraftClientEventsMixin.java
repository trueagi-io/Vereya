package io.singularitynet.mixin;

import io.singularitynet.events.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public class MinecraftClientEventsMixin {
    @Inject(at = @At("HEAD"), method = "setScreen(Lnet/minecraft/client/gui/screen/Screen;)V")
    private void setScreen(@Nullable Screen screen, CallbackInfo info) {
        ScreenEvents.SET_SCREEN.invoker().interact((MinecraftClient) (Object) this, screen);
    }
}
