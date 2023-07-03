package io.singularitynet.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MobEntity.class)
public abstract class MobEntityMixin extends LivingEntity {

    protected MobEntityMixin(EntityType<? extends MobEntity> entityType, World world) {
        super(entityType, world);
    }

    @Inject(method = "canMoveVoluntarily", at = @At("HEAD"), cancellable = true)
    protected void onCanMoveVoluntarily(CallbackInfoReturnable<Boolean> cir){
        cir.setReturnValue(!this.getWorld().isClient);
    }

    @Inject(method = "tickNewAi", at = @At("HEAD"), cancellable = true)
    protected void tickNewAi(CallbackInfo ci){
        if (this.isAiDisabled()){
            ci.cancel();
        }
    }

    @Shadow
    public abstract boolean isAiDisabled();
}
