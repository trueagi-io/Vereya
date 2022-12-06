package io.singularitynet.MissionHandlers;

import com.mojang.serialization.Lifecycle;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.GeneratorOptionsHolder;
import net.minecraft.entity.passive.PandaEntity;
import net.minecraft.resource.DataPackSettings;
import net.minecraft.server.SaveLoader;
import net.minecraft.util.registry.DynamicRegistryManager;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.RegistryEntry;
import net.minecraft.util.registry.SimpleRegistry;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.SaveProperties;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.dimension.DimensionTypes;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.level.storage.LevelStorage;

import java.io.IOException;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Properties;
import java.util.UUID;

public class WorldUtil {
    public static void createLevel(boolean hardcore, Long seed, Difficulty difficulty) throws Exception {
        UUID uuid = UUID.randomUUID();
        String worldName = uuid.toString().substring(0, 5);
        String levelName = "Vereya-test" + worldName;
        LevelInfo levelInfo;
        GameRules gameRules = new GameRules();
        GeneratorOptions generatorOptions = WorldUtil.getGeneratorOptionsDefault(hardcore, OptionalLong.of(seed), levelName);

        levelInfo = new LevelInfo(levelName.trim(), GameMode.DEFAULT,
                hardcore, difficulty,
                true, gameRules, DataPackSettings.SAFE_MODE);
        MinecraftClient client = MinecraftClient.getInstance();
        DynamicRegistryManager.Immutable dynamicRegistryManager = DynamicRegistryManager.createAndLoad().toImmutable();
        client.createIntegratedServerLoader().createAndStart(levelName, levelInfo, dynamicRegistryManager, generatorOptions);
    }


    static GeneratorOptions getGeneratorOptionsDefault(boolean hardcore, OptionalLong seed, String levelName) throws Exception {
        DynamicRegistryManager.Immutable immutable = DynamicRegistryManager.createAndLoad().toImmutable();
        GeneratorOptions generatorOptions = WorldPresets.createDefaultOptions(immutable, seed.getAsLong());
        return generatorOptions.withHardcore(hardcore, seed);
    }

    static GeneratorOptions getGeneratorOptionsDefault1(boolean hardcore, OptionalLong seed){
        SimpleRegistry<DimensionOptions> mutableRegistry = new SimpleRegistry<DimensionOptions>(Registry.DIMENSION_KEY,
                Lifecycle.experimental(), null);
        GeneratorOptions generatorOptions = new GeneratorOptions(seed.getAsLong(), true, false,
                mutableRegistry);
        return generatorOptions.withHardcore(hardcore, seed);
    }

    public static void createLevelFlat(boolean hardcore,
                                   Difficulty difficulty, Properties properties) {
        UUID uuid = UUID.randomUUID();
        String worldName = uuid.toString().substring(0, 5);
        String levelName = "Vereya-test" + worldName;
        GameRules gameRules = new GameRules();
        LevelInfo levelInfo = new LevelInfo(levelName.trim(), GameMode.DEFAULT,
                hardcore, difficulty,
                true, gameRules, DataPackSettings.SAFE_MODE);
        /*
        DynamicRegistryManager.Impl impl = DynamicRegistryManager.create();

        GeneratorOptions generatorOptions = getGeneratorOptions(properties);
        MinecraftClient.getInstance().createWorld(worldName, levelInfo, impl, generatorOptions);
         */
    }

}
