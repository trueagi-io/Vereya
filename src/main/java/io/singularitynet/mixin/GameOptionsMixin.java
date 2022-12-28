package io.singularitynet.mixin;

import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GameOptions.class)
public interface GameOptionsMixin {
    @Accessor("attackKey") @Mutable
    public void setKeyAttack(KeyBinding key);

    @Accessor("attackKey")
    public KeyBinding getKeyAttack();

    @Accessor("useKey") @Mutable
    public void setKeyUse(KeyBinding key);

    @Accessor("useKey")
    public KeyBinding getKeyUse();

    @Accessor("hotbarKeys") @Mutable
    public void setKeysHotbar(KeyBinding[] keys);

    @Accessor("hotbarKeys")
    public KeyBinding[] getKeysHotbar();
}
