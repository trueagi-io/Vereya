package io.singularitynet.mixin;

import io.singularitynet.events.ServerEntityEventsVereya;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net/minecraft/server/world/ServerWorld$ServerEntityHandler")
abstract class ServerWorldEntityLoaderMixin {
    // final synthetic Lnet/minecraft/server/world/ServerWorld; field_26936
    @SuppressWarnings("ShadowTarget")
    @Shadow
    @Final
    private ServerWorld field_26936;

    @Inject(method = "startTracking(Lnet/minecraft/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void invokeEntityLoadEvent(Entity entity, CallbackInfo ci) {
        ActionResult result = ServerEntityEventsVereya.BEFORE_ENTITY_LOAD.invoker().interact(entity, this.field_26936);
        if (result == ActionResult.FAIL) {
            ci.cancel();
        }
    }
}
