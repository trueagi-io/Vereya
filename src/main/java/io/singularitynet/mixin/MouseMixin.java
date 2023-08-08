package io.singularitynet.mixin;


import io.singularitynet.Client.VereyaModClient;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public class MouseMixin {
    @Inject(method="onMouseButton", at = @At("HEAD"))
    private void onOnMouseButton(long window, int button, int action, int mods, CallbackInfo ci) {
        if(VereyaModClient.MyMouse.class.isInstance(this)){
            VereyaModClient.MyMouse myMouse =  ((VereyaModClient.MyMouse)((Object)this));
            myMouse.onMouseUsed();
        }
    }

    /**
     * Cancellable injection, note that we set the "cancellable"
     * flag to "true" in the injector annotation
     */
    @Inject(method = "onCursorPos", at = @At("HEAD"), cancellable = true)
    private void onOnCursorPos(long window, double x, double y, CallbackInfo ci) {
        if(VereyaModClient.MyMouse.class.isInstance(this)){
            VereyaModClient.MyMouse myMouse =  ((VereyaModClient.MyMouse)((Object)this));
            myMouse.onMouseUsed();
            boolean result = myMouse.shouldUpdate();
            if (result)
                return;
            ci.cancel();
        }
    }
}
