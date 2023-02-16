package io.singularitynet.mixin;


import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Mouse.class)
public interface MouseAccessorMixin {
    @Accessor("cursorDeltaX")
    public double getCursorDeltaX();
    @Accessor("cursorDeltaY")
    public double getCursorDeltaY();
}
