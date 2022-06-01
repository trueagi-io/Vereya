package io.singularitynet.mixin;


import io.singularitynet.Client.VereyaModClient;
import net.minecraft.client.Mouse;
import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

@Mixin(Mouse.class)
public class MouseMixin {
    /**
     * Cancellable injection, note that we set the "cancellable"
     * flag to "true" in the injector annotation
     */
    @Inject(method = "onCursorPos", at = @At("HEAD"), cancellable = true)
    private void onOnCursorPos(long window, double x, double y, CallbackInfo ci) {
        if(VereyaModClient.MyMouse.class.isInstance(this)){
            // it's not possible to cast 'this' to MyMouse
            // one way is to cast this to Object and then object to MyMouse
            // or use class.getDeclaredMethod etc
            boolean result = ((VereyaModClient.MyMouse)((Object)this)).shouldUpdate();
            if (result)
                return;
            ci.cancel();
        }
    }
}
