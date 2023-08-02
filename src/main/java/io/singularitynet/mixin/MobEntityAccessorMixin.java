package io.singularitynet.mixin;

import net.minecraft.entity.ai.control.JumpControl;
import net.minecraft.entity.ai.control.MoveControl;
import net.minecraft.entity.mob.MobEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MobEntity.class)
public interface MobEntityAccessorMixin {

    @Accessor("moveControl")
    public MoveControl getMoveControl();

    @Accessor("moveControl") @Mutable
    public void setMoveControl(MoveControl control);

    @Accessor("jumpControl")
    public JumpControl getJumpControl();

    @Accessor("jumpControl") @Mutable
    public void setJumpControl(JumpControl control);
}
