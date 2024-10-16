package io.singularitynet.MissionHandlers;

import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.dimension.DimensionOptionsRegistryHolder;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import org.apache.logging.log4j.LogManager;

import java.util.Properties;
import java.util.UUID;


public class WorldUtil {
    public static void createLevel(boolean hardcore, Long seed, Difficulty difficulty) throws Exception {
        UUID uuid = UUID.randomUUID();
        String worldName = uuid.toString().substring(0, 5);
        String levelName = "Vereya-test" + worldName;
        GameRules gameRules = new GameRules();
        MinecraftClient client = MinecraftClient.getInstance();
        LevelInfo levelInfo = new LevelInfo(levelName.trim(),
                GameMode.DEFAULT, hardcore, difficulty, true,
                gameRules,
                DataConfiguration.SAFE_MODE);
        LogManager.getLogger().debug("creating world with seed " + seed);
        GeneratorOptions generatorOptions = new GeneratorOptions(seed, true, false);
        client.createIntegratedServerLoader().createAndStart(levelName, levelInfo, generatorOptions, WorldUtil::getDefaultOverworldOptions, client.currentScreen);
    }

    public static DimensionOptionsRegistryHolder getDefaultOverworldOptions(DynamicRegistryManager dynamicRegistryManager) {
        return dynamicRegistryManager.get(RegistryKeys.WORLD_PRESET).entryOf(WorldPresets.DEFAULT).value().createDimensionsRegistryHolder();
    }

    public static DimensionOptionsRegistryHolder getFlatOverworldOptions(DynamicRegistryManager dynamicRegistryManager) {
        return dynamicRegistryManager.get(RegistryKeys.WORLD_PRESET).entryOf(WorldPresets.FLAT).value().createDimensionsRegistryHolder();
    }

    public static void createLevelFlat(boolean hardcore, Long seed,
                                   Difficulty difficulty, Properties properties) {
        UUID uuid = UUID.randomUUID();
        String worldName = uuid.toString().substring(0, 5);
        String levelName = "Vereya-test" + worldName;
        GameRules gameRules = new GameRules();
        MinecraftClient client = MinecraftClient.getInstance();
        LevelInfo levelInfo = new LevelInfo(levelName.trim(), GameMode.DEFAULT,
                hardcore, difficulty,
                true, gameRules, DataConfiguration.SAFE_MODE);
        GeneratorOptions generatorOptions = new GeneratorOptions(seed, true, false);
        client.createIntegratedServerLoader().createAndStart(levelName, levelInfo, generatorOptions, WorldUtil::getFlatOverworldOptions, client.currentScreen);
        /*
        DynamicRegistryManager.Impl impl = DynamicRegistryManager.create();

        GeneratorOptions generatorOptions = getGeneratorOptions(properties);
        MinecraftClient.getInstance().createWorld(worldName, levelInfo, impl, generatorOptions);
         */
    }

}
