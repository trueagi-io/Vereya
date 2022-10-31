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
import io.singularitynet.projectmalmo.InventoryCommand;
import io.singularitynet.projectmalmo.InventoryCommands;
import io.singularitynet.projectmalmo.MissionInit;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Very basic control over inventory. Two commands are required: select and drop - each takes a slot.<br>
 * The effect is to swap the item stacks over - eg "select 10" followed by "drop 0" will swap the stacks
 * in slots 0 and 10.<br>
 * The hotbar slots are 0-8, so this mechanism allows an agent to move items in to/out of the hotbar.
 */
public class InventoryCommandsImplementation extends CommandGroup implements IMalmoMessageListener
{
    private static final Logger LOGGER = LogManager.getLogger();

    @Override
    public void onMessage(MalmoMessageType messageType, Map<String, String> data) {
        throw new RuntimeException("unexpected message from server " + messageType.toString());
    }

    @Override
    public void onMessage(MalmoMessageType messageType, Map<String, String> data, ServerPlayerEntity player) {
        InventoryMessage msg = new InventoryMessage(data);
        runCommand(msg, player);
    }

    public static class InventoryMessage extends MalmoMessage {
        String invA;
        String invB;
        int slotA;
        int slotB;
        boolean combine;
        BlockPos containerPos;

        public InventoryMessage(Map<String, String> data) {
            super(MalmoMessageType.CLIENT_INVENTORY_CHANGE, "inventory");
            this.getData().putAll(data);
            this.invA = data.get("invA");
            this.invB = data.get("invB");
            this.slotA = Integer.valueOf(data.get("slotA"));
            this.slotB = Integer.valueOf(data.get("slotB"));
            this.combine = Boolean.valueOf(data.get("combine"));
            this.containerPos = null;
            if (data.containsKey("containerPos"))
                this.containerPos = BlockPos.fromLong(Long.valueOf(data.get("containerPos")));
        }

        public InventoryMessage(List<Object> params, boolean combine) {
            super(MalmoMessageType.CLIENT_INVENTORY_CHANGE, "inventory");
            this.invA = (String) params.get(0);
            this.slotA = (Integer) params.get(1);
            this.invB = (String) params.get(2);
            this.slotB = (Integer) params.get(3);
            this.combine = combine;
            if (params.size() == 5) {
                this.containerPos = (BlockPos) params.get(4);
                this.getData().put("containerPos", String.valueOf(this.containerPos.asLong()));
            }
            this.getData().put("invA", this.invA);
            this.getData().put("slotA", String.valueOf(this.slotA));
            this.getData().put("slotB", String.valueOf(this.slotB));
            this.getData().put("invB", this.invB);
            this.getData().put("combine", String.valueOf(combine));
        }
    }

    public static class InventoryChangeMessage {
        public ItemStack itemsGained = null;
        public ItemStack itemsLost = null;

        public InventoryChangeMessage(ItemStack itemsGained, ItemStack itemsLost) {
            this.itemsGained = itemsGained;
            this.itemsLost = itemsLost;
        }
    }

    static ItemStack[] swapSlots(ServerPlayerEntity player, String lhsInv, int lhs, String rhsInv, int rhs, BlockPos containerPos)
    {
        PlayerInventory container = null;
        String containerName = "";

        PlayerInventory lhsInventory = lhsInv.equals("inventory") ? player.getInventory() : (lhsInv.equals(containerName) ? container : null);
        PlayerInventory rhsInventory = rhsInv.equals("inventory") ? player.getInventory() : (rhsInv.equals(containerName) ? container : null);
        if (lhsInventory == null || rhsInventory == null)
            return null; // Source or dest container not available.
        if (rhs < 0 || lhs < 0)
            return null; // Out of bounds.
        if (lhs >= lhsInventory.size() || rhs >= rhsInventory.size())
            return null; // Out of bounds.

        ItemStack srcStack = lhsInventory.removeStack(lhs);
        ItemStack dstStack = rhsInventory.removeStack(rhs);
        LOGGER.info(String.format("setting %d to %s", lhs, dstStack.toString()));
        LOGGER.info(String.format("setting %d to %s", rhs, srcStack.toString()));
        lhsInventory.insertStack(lhs, dstStack);
        rhsInventory.insertStack(rhs, srcStack);
        lhsInventory.updateItems();
        rhsInventory.updateItems();
        if (lhsInventory.player instanceof ServerPlayerEntity) {
            ServerPlayerEntity entity = ((ServerPlayerEntity)(lhsInventory.player));
            entity.networkHandler.sendPacket(new ScreenHandlerSlotUpdateS2CPacket(-2, 0, lhs, lhsInventory.getStack(lhs)));
            LOGGER.info(String.format("have now %d to %s", lhs, lhsInventory.getStack(lhs).toString()));
        }
        if (rhsInventory.player instanceof ServerPlayerEntity) {
            ServerPlayerEntity entity = ((ServerPlayerEntity)(rhsInventory.player));
            entity.networkHandler.sendPacket(new ScreenHandlerSlotUpdateS2CPacket(-2, 0, rhs, rhsInventory.getStack(rhs)));
            LOGGER.info(String.format("have now %d to %s", rhs, rhsInventory.getStack(rhs).toString()));
        }
        if (lhsInventory != rhsInventory)
        {
            // Items have moved between our inventory and the foreign inventory - may need to trigger
            // rewards for collecting / discarding.
            ItemStack[] returnStacks = new ItemStack[2];
            ItemStack stackBeingLost = (lhsInventory == player.getInventory()) ? srcStack : dstStack;
            ItemStack stackBeingGained = (lhsInventory == player.getInventory()) ? dstStack : srcStack;
            if (stackBeingGained != null)
                returnStacks[0] = stackBeingGained.copy();
            if (stackBeingLost != null)
                returnStacks[1] = stackBeingLost.copy();
            return returnStacks;
        }
        return null;
    }

    private static InventoryChangeMessage runCommand(InventoryMessage message, ServerPlayerEntity player){
            ItemStack[] changes = null;
            if (message.combine) {
                LOGGER.info("combine");
                changes = combineSlots(player, message.invA, message.slotA, message.invB, message.slotB, message.containerPos);
            }
            else {
                LOGGER.info("swapSlots");
                changes = swapSlots(player, message.invA, message.slotA, message.invB, message.slotB, message.containerPos);
            }
            if (changes != null)
                return new InventoryChangeMessage(changes[0], changes[1]);
            return null;
    }

    static ItemStack[] combineSlots(ServerPlayerEntity player, String invDst, int dst, String invAdd, int add, BlockPos containerPos)
    {
        Inventory container = null;
        String containerName = "";
        /*
        if (containerPos != null)
        {
            TileEntity te = player.world.getTileEntity(containerPos);
            if (te != null && te instanceof TileEntityLockableLoot)
            {
                containerName = ObservationFromFullInventoryImplementation.getInventoryName((IInventory)te);
                container = (IInventory)te;
            }
            else if (te != null && te instanceof TileEntityEnderChest)
            {
                containerName = ObservationFromFullInventoryImplementation.getInventoryName(player.getInventoryEnderChest());
                container = player.getInventoryEnderChest();
            }
        }*/
        Inventory dstInv = invDst.equals("inventory") ? player.getInventory() : (invDst.equals(containerName) ? container : null);
        Inventory addInv = invAdd.equals("inventory") ? player.getInventory() : (invAdd.equals(containerName) ? container : null);
        if (dstInv == null || addInv == null)
            return null; // Source or dest container not available.

        ItemStack dstStack = dstInv.getStack(dst);
        ItemStack addStack = addInv.getStack(add);

        if (addStack == null)
            return null; // Combination is a no-op.

        ItemStack[] returnStacks = null;

        if (dstStack == null) // Do a straight move - nothing to combine with.
        {
            if (dstInv != addInv)
            {
                // Items are moving between our inventory and the foreign inventory - may need to trigger
                // rewards for collecting / discarding.
                returnStacks = new ItemStack[2];
                ItemStack stackBeingLost = (addInv == player.getInventory()) ? addStack : null;
                ItemStack stackBeingGained = (dstInv == player.getInventory()) ? addStack : null;
                if (stackBeingGained != null)
                    returnStacks[0] = stackBeingGained.copy();
                if (stackBeingLost != null)
                    returnStacks[1] = stackBeingLost.copy();
            }
            dstInv.setStack(dst, addStack);
            addInv.setStack(add, null);
            return returnStacks;
        }

        // Check we can combine. This logic comes from InventoryPlayer.storeItemStack():
        boolean itemsMatch = dstStack.getItem() == addStack.getItem();
        boolean dstCanStack = dstStack.isStackable() && dstStack.getCount() < dstStack.getMaxCount() &&
                dstStack.getCount() < dstInv.getMaxCountPerStack();
        boolean subTypesMatch = true; //!dstStack.getHasSubtypes() || dstStack.getMetadata() == addStack.getMetadata();
        boolean tagsMatch = ItemStack.areItemsEqualIgnoreDamage(dstStack, addStack);
        if (itemsMatch && dstCanStack && subTypesMatch && tagsMatch)
        {
            // We can combine, so figure out how much we have room for:
            int limit = Math.min(dstStack.getMaxCount(), dstInv.getMaxCountPerStack());
            int room = limit - dstStack.getCount();
            ItemStack itemsTransferred = dstStack.copy();
            if (addStack.getCount() > room)
            {
                // Not room for all of it, so shift across as much as possible.
                addStack.decrement(room);
                dstStack.increment(room);
                itemsTransferred.setCount(room);
            }
            else
            {
                // Room for the whole lot, so empty out the add slot.
                dstStack.increment(addStack.getCount());
                itemsTransferred.setCount(addStack.getCount());
                addInv.removeStack(add);//setInventorySlotContents(add, null);
            }
            if (dstInv != addInv)
            {
                // Items are moving between our inventory and the foreign inventory - may need to trigger
                // rewards for collecting / discarding.
                returnStacks = new ItemStack[2];
                if (dstInv == player.getInventory())
                    returnStacks[0] = itemsTransferred; // We're gaining them
                else
                    returnStacks[1] = itemsTransferred; // We're losing them
            }
        }
        return returnStacks;
    }

    @Override
    protected boolean onExecute(String verb, String parameter, MissionInit missionInit)
    {

        if (verb.equalsIgnoreCase(InventoryCommand.SWAP_INVENTORY_ITEMS.value()))
        {
            if (parameter != null && parameter.length() != 0)
            {
                List<Object> params = new ArrayList<Object>();
                if (getParameters(parameter, params))
                {
                    // All okay, so create a swap message for the server:
                    // InventoryChangeMessage msg = runCommand(new InventoryMessage(params, false));
                    ClientPlayNetworking.send(NetworkConstants.CLIENT2SERVER, (new InventoryMessage(params, false)).toBytes());
                    return true;
                }
                else
                    return false;   // Duff parameters.
            }
        }
        else if (verb.equalsIgnoreCase(InventoryCommand.COMBINE_INVENTORY_ITEMS.value()))
        {
            if (parameter != null && parameter.length() != 0)
            {
                List<Object> params = new ArrayList<Object>();
                if (getParameters(parameter, params))
                {
                    // All okay, so create a combine message for the server:
                    // runCommand(new InventoryMessage(params, false));
                    ClientPlayNetworking.send(NetworkConstants.CLIENT2SERVER, (new InventoryMessage(params, false)).toBytes());
                    return true;
                }
                else
                    return false;   // Duff parameters.
            }
        }
        else if (verb.equalsIgnoreCase(InventoryCommand.DISCARD_CURRENT_ITEM.value()))
        {
            MinecraftClient.getInstance().player.dropSelectedItem(false);
            // This we can do on the client side:
            // Minecraft.getMinecraft().player.dropItem(false);  // false means just drop one item - true means drop everything in the current stack.
            return true;
        }
        return super.onExecute(verb, parameter, missionInit);
    }


    private boolean getParameters(String parameter, List<Object> parsedParams)
    {
        String[] params = parameter.split(" ");
        if (params.length != 2)
        {
            System.out.println("Malformed parameter string (" + parameter + ") - expected <x> <y>");
            return false;   // Error - incorrect number of parameters.
        }
        String[] lhsParams = params[0].split(":");
        String[] rhsParams = params[1].split(":");
        Integer lhsIndex, rhsIndex;
        String lhsName, rhsName, lhsStrIndex, rhsStrIndex;
        boolean checkContainers = false;
        if (lhsParams.length == 2)
        {
            lhsName = lhsParams[0];
            lhsStrIndex = lhsParams[1];
            checkContainers = true;
        }
        else if (lhsParams.length == 1)
        {
            lhsName = "inventory";
            lhsStrIndex = lhsParams[0];
        }
        else
        {
            System.out.println("Malformed parameter string (" + params[0] + ")");
            return false;
        }
        if (rhsParams.length == 2)
        {
            rhsName = rhsParams[0];
            rhsStrIndex = rhsParams[1];
            checkContainers = true;
        }
        else if (rhsParams.length == 1)
        {
            rhsName = "inventory";
            rhsStrIndex = rhsParams[0];
        }
        else
        {
            System.out.println("Malformed parameter string (" + params[1] + ")");
            return false;
        }

        try
        {
            lhsIndex = Integer.valueOf(lhsStrIndex);
            rhsIndex = Integer.valueOf(rhsStrIndex);
        }
        catch (NumberFormatException e)
        {
            System.out.println("Malformed parameter string (" + parameter + ") - " + e.getMessage());
            return false;
        }
        if (lhsIndex == null || rhsIndex == null)
        {
            System.out.println("Malformed parameter string (" + parameter + ")");
            return false;   // Error - incorrect parameters.
        }
        BlockPos containerPos = null;
        /*
        if (checkContainers)
        {
            String containerName = "";
            RayTraceResult rtr = Minecraft.getMinecraft().objectMouseOver;
            if (rtr != null && rtr.typeOfHit == RayTraceResult.Type.BLOCK)
            {
                containerPos = rtr.getBlockPos();
                TileEntity te = Minecraft.getMinecraft().world.getTileEntity(containerPos);
                if (te instanceof TileEntityLockableLoot)
                    containerName = ObservationFromFullInventoryImplementation.getInventoryName((IInventory)te);
                else if (te instanceof TileEntityEnderChest)
                    containerName = ObservationFromFullInventoryImplementation.getInventoryName(Minecraft.getMinecraft().player.getInventoryEnderChest());
            }
            boolean containerMatches = (lhsName.equals("inventory") || lhsName.equals(containerName)) && (rhsName.equals("inventory") || rhsName.equals(containerName));
            if (!containerMatches)
            {
                System.out.println("Missing container requested in parameter string (" + parameter + ")");
                return false;
            }
        }*/

        parsedParams.add(lhsName);
        parsedParams.add(lhsIndex);
        parsedParams.add(rhsName);
        parsedParams.add(rhsIndex);
        if (containerPos != null)
            parsedParams.add(containerPos);
        return true;
    }

    @Override
    public boolean parseParameters(Object params)
    {
        super.parseParameters(params);

        if (params == null || !(params instanceof InventoryCommands))
            return false;

        InventoryCommands iparams = (InventoryCommands) params;
        setUpAllowAndDenyLists(iparams.getModifierList());
        return true;
    }

    @Override
    public void install(MissionInit missionInit)
    {
        SidesMessageHandler.client2server.registerForMessage(this, MalmoMessageType.CLIENT_INVENTORY_CHANGE);
    }

    @Override
    public void deinstall(MissionInit missionInit) {
        SidesMessageHandler.client2server.deregisterForMessage(this, MalmoMessageType.CLIENT_INVENTORY_CHANGE);
    }

}
