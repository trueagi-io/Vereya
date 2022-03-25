package io.singularitynet.mixin;

import io.singularitynet.TitleScreenEvents;
import net.minecraft.client.gui.screen.TitleScreen;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public class TitleScreenMixin {
    @Shadow @Nullable private String splashText;

    @Inject(at = @At("TAIL"), method = "init()V")
    private void init(CallbackInfo info) {
        if(this.splashText != null) {
            TitleScreenEvents.END_TITLESCREEN_INIT.invoker().onTitleScreenEndInit();
            System.out.println("This line is printed by an example mod mixin!");
        }
    }
}