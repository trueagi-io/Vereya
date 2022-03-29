package io.singularitynet.mixin;

import net.minecraft.client.Keyboard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;


@Mixin(MinecraftClient.class)
public interface MinecraftClientMixin {
    @Accessor("keyboard") @Mutable
    public void setKeyboard(Keyboard keyboard);

    @Accessor("mouse") @Mutable
    public void setMouse(Mouse keyboard);

}


