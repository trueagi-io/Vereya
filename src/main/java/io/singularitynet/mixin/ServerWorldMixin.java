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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerWorld.class)
class ServerWorldMixin {

     @Inject(method = "Lnet/minecraft/server/world/ServerWorld;addEntity(Lnet/minecraft/entity/Entity;)Z", at = @At("HEAD"), cancellable = true)
     void invokeEntityAddEvent(Entity entity, CallbackInfoReturnable<Boolean> cir) {
            ActionResult result = ServerEntityEventsVereya.BEFORE_ENTITY_ADD.invoker().interact(entity, (ServerWorld)(Object)this);
            if (result == ActionResult.FAIL) {
                cir.cancel();
            }
     }
}

