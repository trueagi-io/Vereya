package io.singularitynet.mixin;


import net.minecraft.entity.mob.MobEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.entity.Entity;

@Mixin(Entity.class)
public class EntityMixin {

    private static final Logger LOGGER = LogManager.getLogger(EntityMixin.class);

    @Inject(method="remove", at=@At("HEAD"), cancellable = true)
    public void remove(Entity.RemovalReason reason, CallbackInfo c) {
        if ((Object) this instanceof MobEntity) {
            MobEntity mob = (MobEntity) (Object) this;
            if(mob.isAiDisabled()){
                if(reason == Entity.RemovalReason.DISCARDED){
                    mob.setDespawnCounter(0);
                    c.cancel();
                    LOGGER.trace("cancelled removal of entity " + mob.getUuidAsString());
                } else {
                    LOGGER.trace("removed entity " + mob.getUuidAsString());
                }
            }
        }
    }
}
