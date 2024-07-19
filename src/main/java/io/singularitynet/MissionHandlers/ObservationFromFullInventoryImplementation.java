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

import io.singularitynet.MissionHandlerInterfaces.IObservationProducer;
import io.singularitynet.projectmalmo.MissionInit;
import io.singularitynet.projectmalmo.ObservationFromFullInventory;
import io.singularitynet.utils.JSONWorldDataHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** Simple IObservationProducer class that returns a list of the full inventory, including the armour.
 */
public class ObservationFromFullInventoryImplementation extends HandlerBase implements IObservationProducer
{
    void buildJson(JsonObject json, PlayerEntity player)
    {
        // We want to output the inventory from:
        // a) the player
        // b) any chest-type objects the player is looking at. todo
        // Newer approach - an array of objects.
        JsonArray arr = new JsonArray();
        JSONWorldDataHelper.getInventoryJSON(arr, player.getInventory());
        json.add("inventory", arr);

        // Also add an entry for each type of inventory available.
        JsonArray arrInvs = new JsonArray();
        JsonObject jobjPlayer = new JsonObject();
        jobjPlayer.add("name", new JsonPrimitive(JSONWorldDataHelper.getInventoryName(player.getInventory())));
        jobjPlayer.add("size", new JsonPrimitive(player.getInventory().size()));
        arrInvs.add(jobjPlayer);

        json.add("inventoriesAvailable", arrInvs);
        // Also add a field to show which slot in the hotbar is currently selected.
        PlayerInventory inv = player.getInventory();
        json.add("currentItemIndex", new JsonPrimitive(inv.selectedSlot));
    }

    private boolean flat;

    @Override
    public boolean parseParameters(Object params)
    {
        if (params == null || !(params instanceof ObservationFromFullInventory))
            return false;

        this.flat = ((ObservationFromFullInventory)params).isFlat();
        return true;
    }

    public static void getInventoryJSON(JsonObject json, String prefix, Inventory inventory, int maxSlot)
    {
        int nSlots = Math.min(inventory.size(), maxSlot);
        for (int i = 0; i < nSlots; i++)
        {
            ItemStack is = inventory.getStack(i);
            if (is != null)
            {
                json.add(prefix + i + "_size", new JsonPrimitive(is.getCount()));
                json.add("type", new JsonPrimitive(is.getItem().getName().getString()));
                json.add("index", new JsonPrimitive(i));
                json.add("quantity", new JsonPrimitive(is.getCount()));
                json.add("inventory",  new JsonPrimitive("inventory"));
            }
        }
    }

    @Override
    public void prepare(MissionInit missionInit)
    {
        // super.prepare(missionInit);
    }

    @Override
    public void writeObservationsToJSON(JsonObject json, MissionInit missionInit)
    {
        buildJson(json, MinecraftClient.getInstance().player);
    }

    @Override
    public void cleanup()
    {
        // super.cleanup();
    }

}