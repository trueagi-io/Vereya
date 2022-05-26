package io.singularitynet.mixin;

import io.singularitynet.MissionHandlers.CommandForKey;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.apache.commons.compress.harmony.pack200.NewAttributeBands;
import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

@Mixin(KeyBinding.class)
public abstract class KeyBindingMixin {
    @Shadow public abstract String getTranslationKey();

    @Inject(at = @At("TAIL"), method = "<init>(Ljava/lang/String;Lnet/minecraft/client/util/InputUtil$Type;ILjava/lang/String;)V")
    private void init(String translationKey, InputUtil.Type type, int code, String category, CallbackInfo ci) {
        LogManager.getLogger().debug(translationKey + this.hashCode() + " KeyConstructor: "  +
                CommandForKey.KeyHook.class.isInstance(this) + " code: " + code);
        Map<InputUtil.Key, KeyBinding> bindingMap = KeyBindingMixin.getKeyToBindings();
        List<InputUtil.Key> keyList = new LinkedList<>();
        for(Map.Entry<InputUtil.Key, KeyBinding> item : bindingMap.entrySet()) {
            if(item.getValue().getTranslationKey().equals(this.getTranslationKey())) {
                LogManager.getLogger().debug("bindingMap has " + item.getKey().getCode() + ": " + item.getValue().hashCode());
                keyList.add(item.getKey());
            }
        }
        if(keyList.size() > 1) {
            LogManager.getLogger().debug("found multiple keys for the same KeyBinding" + keyList);
        }
    }

    @Accessor("KEY_TO_BINDINGS")
    public static Map<InputUtil.Key, KeyBinding> getKeyToBindings() {
        throw new AssertionError();
    }
}
