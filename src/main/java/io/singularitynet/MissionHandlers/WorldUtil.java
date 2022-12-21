package io.singularitynet.MissionHandlers;

import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.DataPackSettings;
import net.minecraft.util.registry.DynamicRegistryManager;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.level.LevelInfo;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.Properties;
import java.util.UUID;

public class WorldUtil {
    public static void createLevel(boolean hardcore, Long seed, Difficulty difficulty) {
        UUID uuid = UUID.randomUUID();
        String worldName = uuid.toString().substring(0, 5);
        String levelName = "Vereya-test" + worldName;
        LevelInfo levelInfo;
        GameRules gameRules = new GameRules();
        GeneratorOptions generatorOptions = WorldUtil.getGeneratorOptionsDefault(hardcore, OptionalLong.of(seed));
        levelInfo = new LevelInfo(levelName.trim(), GameMode.DEFAULT,
                hardcore, difficulty,
                true, gameRules, DataPackSettings.SAFE_MODE);
        DynamicRegistryManager.Immutable immutable = DynamicRegistryManager.BUILTIN.get();
        MinecraftClient.getInstance().createWorld(worldName, levelInfo, immutable, generatorOptions);
    }

    static GeneratorOptions getGeneratorOptionsDefault(boolean hardcore, OptionalLong seed){
        DynamicRegistryManager.Immutable immutable = DynamicRegistryManager.BUILTIN.get();
        GeneratorOptions generatorOptions = GeneratorOptions.getDefaultOptions(immutable);
        return generatorOptions.withHardcore(hardcore, seed);
    }
/*
    public static void createLevelFlat(boolean hardcore,
                                   Difficulty difficulty, Properties properties) {
        UUID uuid = UUID.randomUUID();
        String worldName = uuid.toString().substring(0, 5);
        String levelName = "Vereya-test" + worldName;
        GameRules gameRules = new GameRules();
        LevelInfo levelInfo = new LevelInfo(levelName.trim(), GameMode.DEFAULT,
                hardcore, difficulty,
                true, gameRules, DataPackSettings.SAFE_MODE);
        DynamicRegistryManager impl = DynamicRegistryManager.createAndLoad();

        GeneratorOptions generatorOptions = getGeneratorOptions(properties);
        MinecraftClient.getInstance().createWorld(worldName, levelInfo, impl, generatorOptions);
    }
    static GeneratorOptions getGeneratorOptions(Properties properties) {
        DynamicRegistryManager impl = DynamicRegistryManager.createAndLoad();
        GeneratorOptions generatorOptions =  GeneratorOptions.fromProperties(impl, properties);
        return generatorOptions;
    }
*/
}
