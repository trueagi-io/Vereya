package io.singularitynet.mixin;

import net.minecraft.data.server.loottable.LootTableProvider;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Set;

@Mixin(LootTableProvider.class)
public interface LootTableProviderMixin {
    @Accessor("lootTableIds")
    Set<Identifier> getlootTableIds();

    @Accessor("lootTypeGenerators")
    List<LootTableProvider.LootTypeGenerator> getlootTypeGenerators();
}
