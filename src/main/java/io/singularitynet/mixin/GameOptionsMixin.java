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

    @Accessor("swapHandsKey")
    public KeyBinding getKeySwapHands();

    @Accessor("swapHandsKey") @Mutable
    public void setKeySwapHands(KeyBinding key);

    // setters for movement commands
    @Accessor("forwardKey") @Mutable
    public void setKeyForward(KeyBinding key);

    @Accessor("backKey") @Mutable
    public void setKeyBack(KeyBinding key);

    @Accessor("leftKey") @Mutable
    public void setKeyLeft(KeyBinding key);

    @Accessor("rightKey") @Mutable
    public void setKeyRight(KeyBinding key);

    @Accessor("jumpKey") @Mutable
    public void setKeyJump(KeyBinding key);

    @Accessor("sneakKey") @Mutable
    public void setKeySneak(KeyBinding key);

    @Accessor("sprintKey") @Mutable
    public void setKeySprint(KeyBinding key);

    // getters for the movement commands
    @Accessor("forwardKey")
    public KeyBinding getKeyForward();

    @Accessor("backKey")
    public KeyBinding getKeyBack();

    @Accessor("leftKey")
    public KeyBinding getKeyLeft();

    @Accessor("rightKey")
    public KeyBinding getKeyRight();

    @Accessor("jumpKey")
    public KeyBinding getKeyJump();

    @Accessor("sprintKey")
    public KeyBinding getKeySprint();

    @Accessor("sneakKey")
    public KeyBinding getKeySneak();

    // getter and setter for inventory
    @Accessor("inventoryKey")
    public KeyBinding getKeyInventory();

    @Accessor("inventoryKey") @Mutable
    public void setKeyInventory(KeyBinding key);

    // getter and setter for drop
    @Accessor("dropKey")
    public KeyBinding getKeyDrop();

    @Accessor("dropKey") @Mutable
    public void setKeyDrop(KeyBinding key);

    @Accessor("pickItemKey") @Mutable
    public void setKeyPickItem(KeyBinding key);

    @Accessor("pickItemKey")
    public KeyBinding getKeyPickItem();
}
