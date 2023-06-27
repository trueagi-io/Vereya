package io.singularitynet.MissionHandlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonObject;
import io.singularitynet.MissionHandlerInterfaces.ICommandHandler;
import io.singularitynet.MissionHandlerInterfaces.IObservationProducer;
import io.singularitynet.projectmalmo.MissionInit;
import io.singularitynet.projectmalmo.ObservationFromRecipe;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.Recipe;
import net.minecraft.util.collection.DefaultedList;

import java.util.List;

class ObservationFromRecipesImplementation extends HandlerBase implements IObservationProducer, ICommandHandler {
    private boolean sendRec;
    private int counter;

    @Override
    public void cleanup() {

    }

    @Override
    public void prepare(MissionInit missionInit) {

    }

    @Override
    public void writeObservationsToJSON(JsonObject json, MissionInit currentMissionInit) {
        if (!this.sendRec){
            return;
        }
        this.sendRec = false;
        List<Recipe<?>> result = MinecraftClient.getInstance().world.getRecipeManager().values().stream().toList();
        JsonArray recipes = new JsonArray();
        for (Recipe r: result) {
            JsonObject rec = new JsonObject(); // recipe
            ItemStack out = r.getOutput(MinecraftClient.getInstance().world.getRegistryManager());
            rec.add("name", new JsonPrimitive(out.getItem().getTranslationKey()));
            rec.add("count", new JsonPrimitive(out.getCount()));
            DefaultedList<Ingredient> ingredients = r.getIngredients();
            JsonArray ingArray = new JsonArray(); // ingredients
            for(Ingredient ingrid: ingredients) {
                JsonArray ingStacks = new JsonArray();
                for(ItemStack s:ingrid.getMatchingStacks()){
                    JsonObject ing = new JsonObject();
                    ing.add("type", new JsonPrimitive(s.getItem().getTranslationKey()));
                    ing.add("count", new JsonPrimitive(s.getCount()));
                    ingStacks.add(ing);
                }
                ingArray.add(ingStacks);
            }
            rec.add("ingredients", ingArray);
            rec.add("recipe_type", new JsonPrimitive(r.getType().toString()));
            rec.add("group", new JsonPrimitive(r.getGroup()));
            recipes.add(rec);
        }
        json.add("recipes", recipes);
    }

    @Override
    public boolean isOverriding() {
        return false;
    }

    @Override
    public void setOverriding(boolean b) {

    }

    @Override
    public void install(MissionInit currentMissionInit) {
        sendRec = false;
    }

    @Override
    public void deinstall(MissionInit currentMissionInit) {

    }

    @Override
    public boolean execute(String command, MissionInit currentMissionInit) {
        String comm[] = command.split(" ", 2);
        if (comm.length == 2 && comm[0].equalsIgnoreCase(ObservationFromRecipe.RECIPES.value()) &&
                !comm[1].equalsIgnoreCase("off")) {
            this.sendRec = true;
            return true;
        }
        return false;
    }
}
