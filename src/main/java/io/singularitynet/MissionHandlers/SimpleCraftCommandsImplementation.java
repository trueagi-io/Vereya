// --------------------------------------------------------------------------------------------------
//  Copyright (c) 2016 Microsoft Corporation
//
//  Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
//  associated documentation files (the "Software"), to deal in the Software without restriction,
//  including without limitation the rights to use, copy, modify, merge, publish, distribute,
//  sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
//  furnished to do so, subject to the following conditions:
//
//  The above copyright notice and this permission notice shall be included in all copies or
//  substantial portions of the Software.
//
//  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
//  NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
//  NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
//  DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
//  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
// --------------------------------------------------------------------------------------------------
package io.singularitynet.MissionHandlers;


import io.singularitynet.*;

import io.singularitynet.projectmalmo.MissionInit;
import io.singularitynet.projectmalmo.SimpleCraftCommand;
import io.singularitynet.utils.CraftingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeType;
import net.minecraft.server.network.ServerPlayerEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;


public class SimpleCraftCommandsImplementation extends CommandBase  implements IMalmoMessageListener {
    private boolean isOverriding;
    // Directly reference a log4j logger.
    private static final Logger LOGGER = LogManager.getLogger();

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
    protected boolean onExecute(String verb, String parameter, MissionInit missionInit)
    {
        if (verb.equalsIgnoreCase(SimpleCraftCommand.CRAFT.value()))
        {
            LOGGER.info("crafting message " + verb);
            ClientPlayNetworking.send(NetworkConstants.CLIENT2SERVER, (new CraftMessage(parameter).toBytes()));
            return true;
        }
        return false;
    }


    @Override
    public void onMessage(MalmoMessageType messageType, Map<String, String> data) {
        throw new RuntimeException("Unexpected message to client");
    }

    @Override
    public void onMessage(MalmoMessageType messageType, Map<String, String> data, ServerPlayerEntity player)
    {
        // Try crafting recipes first:
        List<Recipe> matching_recipes;
        String[] split = data.get("message").split(" ");
        matching_recipes = CraftingHelper.getRecipesForRequestedOutput(split[0], false);

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
    public boolean isOverriding()
    {
        return this.isOverriding;
    }

    @Override
    public void setOverriding(boolean b)
    {
        this.isOverriding = b;
    }

    @Override
    public void install(MissionInit missionInit)
    {
        SidesMessageHandler.client2server.registerForMessage(this, MalmoMessageType.CLIENT_CRAFT);
    }

    @Override
    public void deinstall(MissionInit missionInit) {
        SidesMessageHandler.client2server.deregisterForMessage(this, MalmoMessageType.CLIENT_CRAFT);
    }
}
