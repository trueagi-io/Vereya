package io.singularitynet.MissionHandlers;

import io.singularitynet.IMalmoMessageListener;
import io.singularitynet.MalmoMessage;
import io.singularitynet.MalmoMessageType;
import io.singularitynet.SidesMessageHandler;
import io.singularitynet.projectmalmo.MissionInit;
import io.singularitynet.utils.CraftingHelper;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeType;
import net.minecraft.server.network.ServerPlayerEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;

public class SimpleCraftCommandsImplementationServer extends CommandBase  implements IMalmoMessageListener {
    private static final Logger LOGGER = LogManager.getLogger(SimpleCraftCommandsImplementationServer.class.getName());

    public static class CraftMessage extends MalmoMessage
    {

        public CraftMessage(String parameters)
        {
            super(MalmoMessageType.CLIENT_CRAFT, parameters);
        }

        public CraftMessage(String parameters, String fuel_type){
            super(MalmoMessageType.CLIENT_CRAFT, parameters);
            this.getData().put("fuel_type", fuel_type);
        }
    }

    @Override
    public void onMessage(MalmoMessageType messageType, Map<String, String> data) {
        throw new RuntimeException("Calling client message handler on server");
    }

    @Override
    public void onMessage(MalmoMessageType messageType, Map<String, String> data, ServerPlayerEntity player)
    {
        LOGGER.debug("Got crafting message " + messageType + " " + data.get("message"));
        // Try crafting recipes first:
        List<Recipe> matching_recipes;
        String[] split = data.get("message").split(" ");
        matching_recipes = CraftingHelper.getRecipesForRequestedOutput(split[0], false, player);

        // crafting doensn't require furnace or campfire
        for (Recipe recipe : matching_recipes.stream().filter(recipe -> {return recipe.getType() == RecipeType.CRAFTING;}).toList())
        {
            if (CraftingHelper.attemptCrafting(player, recipe))
                return;
        }

        // campfire cooking, requires campfire, doesn't consume furnace
        for (Recipe recipe : matching_recipes.stream().filter(recipe -> {return recipe.getType() == RecipeType.CAMPFIRE_COOKING;}).toList()) {
            if (CraftingHelper.attemptCampfireCooking(player, recipe))
                return;
        }

        // Now try smelting recipes that require furnace
        for (Recipe recipe : matching_recipes.stream().filter(recipe -> {return recipe.getType() == RecipeType.SMELTING;}).toList())
        {
            String fuel_type = "birch_log";
            if (split.length > 1) {
                fuel_type = split[1];
            }

            if (CraftingHelper.attemptSmelting(player, recipe, fuel_type))
                return;
        }

        // blasting is the same as smelting
    }

    @Override
    public boolean isOverriding() {
        return false;
    }

    @Override
    public void setOverriding(boolean b) {

    }


    @Override
    public void install(MissionInit missionInit)
    {
        LOGGER.debug("Installing SimpleCraftCommandsImplementationServer");
        SidesMessageHandler.client2server.registerForMessage(this, MalmoMessageType.CLIENT_CRAFT);
    }

    @Override
    public void deinstall(MissionInit missionInit) {
        LOGGER.debug("Deinstalling SimpleCraftCommandsImplementationServer");
        SidesMessageHandler.client2server.deregisterForMessage(this, MalmoMessageType.CLIENT_CRAFT);
    }

    @Override
    protected boolean onExecute(String verb, String parameter, MissionInit missionInit) {
        throw new RuntimeException("calling onExecute on server");
    }
}
