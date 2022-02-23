package io.singularitynet.MissionHandlers;

import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.DataPackSettings;
import net.minecraft.util.registry.DynamicRegistryManager;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.level.LevelInfo;

import java.util.OptionalLong;

public class WorldUtil {
    public static void createLevel(boolean hardcore, Long seed, Difficulty difficulty) {
        String saveDirectoryName = "../save";
        String levelName = "Vereya-test";
        LevelInfo levelInfo;
        GameRules gameRules = new GameRules();
        GeneratorOptions generatorOptions = WorldUtil.getGeneratorOptionsDefault(hardcore, OptionalLong.of(seed));
        levelInfo = new LevelInfo(levelName.trim(), GameMode.DEFAULT,
                hardcore, difficulty,
                true, gameRules, DataPackSettings.SAFE_MODE);
        DynamicRegistryManager.Impl impl = DynamicRegistryManager.create();
        MinecraftClient.getInstance().createWorld(saveDirectoryName, levelInfo, impl, generatorOptions);
    }

    static GeneratorOptions getGeneratorOptionsDefault(boolean hardcore, OptionalLong seed){
        DynamicRegistryManager.Impl impl = DynamicRegistryManager.create();
        GeneratorOptions generatorOptions = GeneratorOptions.getDefaultOptions(impl);
        return generatorOptions.withHardcore(hardcore, seed);
    }
}
