package io.singularitynet.mixin;

import net.minecraft.client.gui.hud.ChatHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import net.minecraft.client.gui.hud.InGameHud;

@Mixin(InGameHud.class)
public interface InGameHudMixin {
    @Accessor("chatHud") @Mutable
    public void setChatHud(ChatHud hud);
}
