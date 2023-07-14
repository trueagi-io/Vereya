package io.singularitynet.mixin;


import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.control.BodyControl;
import net.minecraft.entity.ai.control.JumpControl;
import net.minecraft.entity.ai.control.MoveControl;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MobEntity.class)
public abstract class MobEntityMixin extends LivingEntity {
    @Shadow @Final private BodyControl bodyControl;
    @Final
    private static Logger LOGGER = LogManager.getLogger(MobEntityMixin.class.getName());

    @Shadow protected JumpControl jumpControl;
    @Shadow protected MoveControl moveControl;

    protected MobEntityMixin(EntityType<? extends MobEntity> entityType, World world) {
        super(entityType, world);
    }

    @Inject(method="canMoveVoluntarily", at = @At("HEAD"), cancellable = true)
    protected void onCanMoveVoluntarily(CallbackInfoReturnable<Boolean> cir){
        cir.setReturnValue(!this.getWorld().isClient);
    }


    @Inject(method="tickNewAi", at = @At("HEAD"), cancellable = true)
    protected void tickNewAi(CallbackInfo ci){
        if (this.isAiDisabled()){
            this.moveControl.tick();
            this.jumpControl.tick();
            // there are other controls
            // this.bodyControl.tick();
            ci.cancel();
        }
    }

    @Shadow
    public abstract boolean isAiDisabled();
}
