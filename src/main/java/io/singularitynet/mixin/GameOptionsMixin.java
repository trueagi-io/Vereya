package io.singularitynet.mixin;

import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GameOptions.class)
public interface GameOptionsMixin {
    @Accessor("keyAttack") @Mutable
    public void setKeyAttack(KeyBinding key);

    @Accessor("keyAttack")
    public KeyBinding getKeyAttack();

    @Accessor("keyUse") @Mutable
    public void setKeyUse(KeyBinding key);

    @Accessor("keyUse")
    public KeyBinding getKeyUse();

    @Accessor("keysHotbar") @Mutable
    public void setKeysHotbar(KeyBinding[] keys);

    @Accessor("keysHotbar")
    public KeyBinding[] getKeysHotbar();
}
