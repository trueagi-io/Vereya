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

package io.singularitynet.utils;

import com.google.gson.JsonSyntaxException;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeType;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.function.Predicate;

public class CraftingHelper {
    // Directly reference a log4j logger.
    private static final Logger LOGGER = LogManager.getLogger();
    /**
     * Attempt to find all recipes that result in an item of the requested output.
     *
     * @param output the desired item, eg from Types.xsd - "diamond_pickaxe" etc - or as a Minecraft name - eg "tile.woolCarpet.blue"
     * @param variant if variants should be obeyed in constructing the recipes, i.e. if false, variant blind
     * @return a list of IRecipe objects that result in this item.
     */
    public static List<Recipe> getRecipesForRequestedOutput(String output, boolean variant) {
        Item item = Registries.ITEM.getOrEmpty(new Identifier(output)).orElseThrow(() -> new RuntimeException("Unknown item '" + output + "'"));
        if (item == Items.AIR) {
            throw new JsonSyntaxException("Invalid item: " + output);
        }
        ItemStack stack = new ItemStack(item);
        List<Recipe<?>> result = MinecraftClient.getInstance().world.getRecipeManager().values().stream().filter(recipe -> {
            ItemStack is = recipe.getOutput();
            if(is.isItemEqual(stack)) return true;
            return false;
        }).toList();

        List<Recipe> result1 = new ArrayList<>();
        for(Recipe recipe:result){
            result1.add(recipe);
        }
        return result1;
    }


    /**
     * Attempt to craft the given recipe.<br>
     * This pays no attention to tedious things like using the right crafting table / brewing stand etc, or getting the right shape.<br>
     * It simply takes the raw ingredients out of the player's inventory, and inserts the output of the recipe, if possible.
     *
     * @param player the SERVER SIDE player that will do the crafting.
     * @param recipe the IRecipe we wish to craft.
     * @return true if the recipe had an output, and the player had the required ingredients to create it; false otherwise.
     */
    public static boolean attemptCrafting(ServerPlayerEntity player, Recipe recipe) {
        if (player == null || recipe == null)
            return false;

        Map<Integer, Integer> inventory = countInInventory(player.getInventory(), recipe);
        LOGGER.debug("contains:");
        for (Map.Entry<Integer, Integer> elem: inventory.entrySet()) {
            LOGGER.debug(Item.byRawId(elem.getKey()).getTranslationKey() + ":" + elem.getValue());
        }
        Map<Integer, Integer> requiredCount = getIngredientCount1(inventory, recipe);

        if (requiredCount != null) {
            // We have the ingredients we need, so directly manipulate the inventory.
            // First, remove the ingredients:
            removeIngredientsFromPlayer(player, requiredCount);
            // Now add the output of the recipe:
            ItemStack resultForInventory = recipe.getOutput().copy();
            player.getInventory().offerOrDrop(resultForInventory);
            return true;
        } else {
            LOGGER.debug("count is wrong");
        }
        return false;
    }

    private static Map<Integer, Integer> countInInventory(Inventory inventory, Recipe recipe) {
        Map<Integer, Integer> result = new HashMap<Integer, Integer>();
        DefaultedList<Ingredient> ingredients = recipe.getIngredients();
        for (Ingredient ingredient : ingredients) {
            ItemStack[] stacks = ingredient.getMatchingStacks();
            for (ItemStack stack : stacks) {
                int itemid = Item.getRawId(stack.getItem());
                if (!result.containsKey(itemid)) {
                    result.put(itemid, inventory.count(stack.getItem()));
                }
            }
        }
        return result;
    }

    private static class Step{
        public int col;
        public int row;
        public Step(int acol, int arow){
            col = acol;
            row = arow;
        }
    }

    private  static class Result{
        public List<Step> steps;
        public boolean ok;
        public Result(){
            ok = false;
            steps = new LinkedList<>();
        }
    }

    private static Result findRecipe(ItemStack[][] ingredient_stacks, Map<Integer, Integer> inventory, int col){

        if (col == ingredient_stacks.length) {
            Result res  = new Result();
            res.ok = true;
            return res;
        }
        ItemStack[] stacks = ingredient_stacks[col];
        if (stacks.length == 0) {
            return findRecipe(ingredient_stacks, inventory, col + 1);
        }
        // any stack from matching stacks suffices
        int row = 0;
        for (ItemStack stack : stacks) {
            int itemid = Item.getRawId(stack.getItem());
            int inInventory = inventory.getOrDefault(itemid, 0);
            if (stack.getCount() <= inInventory){
                Result result = new Result();
                result.steps.add(new Step(col, row));
                result.ok = true;
                // ok, we are on the last ingredient
                if(col == ingredient_stacks.length - 1) {
                    return result;
                } else {
                    // need to recurse to the next column
                    Map<Integer, Integer> map1 = new HashMap<>(inventory);
                    int new_count = map1.getOrDefault(itemid, 0) - stack.getCount();
                    map1.put(itemid, new_count);
                    Result recursive = findRecipe(ingredient_stacks, map1, col + 1);
                    result.steps.addAll(recursive.steps);
                    result.ok = recursive.ok;
                    return result;
                }
            }
            row ++;
        }
        return new Result();
    }

    private static Map<Integer, Integer> getIngredientCount1(Map<Integer, Integer> inventory, Recipe recipe) {
        DefaultedList<Ingredient> ingredients = recipe.getIngredients();
        ItemStack[][] ingredient_stacks = new ItemStack[ingredients.size()][];
        int i = 0;

        for (Ingredient ingredient : ingredients) {
            ingredient_stacks[i++] = ingredient.getMatchingStacks();
        }

        Result result = findRecipe(ingredient_stacks, inventory, 0);
        // id -> count
        Map<Integer, Integer> ingredientCount = new HashMap<>();
        if (result.ok) {
            for(Step steps: result.steps){
                ItemStack stack = ingredient_stacks[steps.col][steps.row];
                int itemid = Item.getRawId(stack.getItem());
                ingredientCount.put(itemid, ingredientCount.getOrDefault(itemid, 0) + stack.getCount());
            }
            return ingredientCount;
        }
        return null;
    }

    private static Map<Item, Integer> getIngredientCount(Inventory inventory, Recipe recipe) {

        // List<ItemStack> ingredients = getIngredients(recipe);
        DefaultedList<Ingredient> ingredients = recipe.getIngredients();

        Map<Item, Integer> requiredCount = new HashMap<>();

        for (Ingredient ingredient : ingredients) {
            ItemStack[] stacks = ingredient.getMatchingStacks();
            boolean hasIngredient = false;
            // any stack from matching stacks suffices
            for (ItemStack stack : stacks) {
                int inInventory = inventory.count(stack.getItem());
                if (stack.getCount() <= inInventory){
                    assert !requiredCount.containsKey(stack.getItem());
                    requiredCount.put(stack.getItem(), stack.getCount());
                    hasIngredient = true;
                    break;
                }
            }
            if (!hasIngredient){
                return null;
            }
        }
        return requiredCount;
    }


    /**
     * Consume fuel from the player's inventory.<br>
     * Take it first from their cache, if present, and then from their inventory, starting
     * at the first slot and working upwards.
     *
     * @param player
     */
    public static void burnInventory(ServerPlayerEntity player, ItemStack itemStack) {

        for(ItemStack stack: player.getInventory().main){
            if(stack.isOf(itemStack.getItem())){
                stack.decrement(1);
            }
        }
    }

    /**
     * Attempt to smelt the given item.<br>
     * This returns instantly, callously disregarding such frivolous niceties as cooking times or the presence of a furnace.<br>
     * It will, however, consume fuel from the player's inventory.
     *
     * @param player
     * @param recipe we want to cook.
     * @return true if cooking was successful.
     */
    public static boolean attemptSmelting(ServerPlayerEntity player, Recipe recipe, String fuel_name) {
        assert recipe.getType() == RecipeType.SMELTING;
        if (player == null || recipe == null)
            return false;
        ItemStack itemStackFurnace = new ItemStack(Items.FURNACE);
        ItemStack blastStackFurnace = new ItemStack(Items.BLAST_FURNACE);
        if (!(player.getInventory().contains(itemStackFurnace) || player.getInventory().contains(blastStackFurnace))){
            return false;
        }

        Item fuel_item = Registries.ITEM.get(new Identifier(fuel_name));
        if(fuel_item == null){
            return false;
        }
        ItemStack fuelItemStack = new ItemStack(fuel_item, 1);
        if (!player.getInventory().contains(fuelItemStack)) {
            return false;
        }

        if (!AbstractFurnaceBlockEntity.canUseAsFuel(fuelItemStack)){
            return false;
        }
        Map<Integer, Integer> inventory = countInInventory(player.getInventory(), recipe);
        Map<Integer, Integer> requiredCount = getIngredientCount1(inventory, recipe);
        if (requiredCount != null) {
            removeIngredientsFromPlayer(player, requiredCount);
            burnInventory(player, fuelItemStack);

            ItemStack resultForInventory = recipe.getOutput().copy();
            LogManager.getLogger().info("adding to inventory " + resultForInventory.toString());
            player.getInventory().offerOrDrop(resultForInventory);
            return true;
        }
        return false;
    }

    public static boolean attemptCampfireCooking(ServerPlayerEntity player, Recipe input) {
        assert input.getType() == RecipeType.CAMPFIRE_COOKING;
        ItemStack itemStackCampfire = new ItemStack(Items.CAMPFIRE);
        if (!player.getInventory().contains(itemStackCampfire)) {
            return false;
        }
        return attemptCrafting(player, input);
    }


    /**
     * Inspect a player's inventory to see whether they have enough items to form the supplied list of ItemStacks.<br>
     * The ingredients list MUST be amalgamated such that no two ItemStacks contain the same type of item.
     *
     * @param player
     * @param requiredCount an Map<Item, required count> of ingredients
     * @return true if the player's inventory contains sufficient quantities of all the required items.
     */
    public static boolean playerHasIngredients(ServerPlayerEntity player, Map<Item, Integer> requiredCount) {
        PlayerInventory inventory = player.getInventory();
        for(Map.Entry<Item, Integer> entry: requiredCount.entrySet()) {
            int in_inventory = inventory.count(entry.getKey());
            if (in_inventory < entry.getValue()) {
                return false;
            }
        }
        return true;
    }


    /**
     * Manually attempt to remove ingredients from the player's inventory.<br>
     *
     * @param player
     * @param ingredients
     */
    public static void removeIngredientsFromPlayer(ServerPlayerEntity player, Map<Integer, Integer> ingredients) {
        PlayerInventory inventory = player.getInventory();
        for(Map.Entry<Integer, Integer> entry: ingredients.entrySet()){
            int entry_id = entry.getKey();
            Predicate<ItemStack> predicate = itemStack -> {
                int item_id = Item.getRawId(itemStack.getItem());
                if (item_id == entry_id)
                    return true;
                return false;
            };
            Inventories.remove(inventory, predicate, entry.getValue(), false);
        }
    }
}
