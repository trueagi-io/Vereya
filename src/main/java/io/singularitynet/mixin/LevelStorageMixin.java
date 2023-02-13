package io.singularitynet.mixin;

import net.minecraft.world.level.storage.LevelStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.nio.file.Path;

@Mixin(LevelStorage.class)
public interface LevelStorageMixin {
    @Accessor("savesDirectory") @Mutable
    public void setSavesDirectory(Path path);

    @Accessor("backupsDirectory") @Mutable
    public void setBackupsDirectory(Path path);
}

