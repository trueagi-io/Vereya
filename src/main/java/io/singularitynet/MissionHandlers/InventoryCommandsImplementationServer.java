package io.singularitynet.MissionHandlers;

import io.singularitynet.IMalmoMessageListener;
import io.singularitynet.MalmoMessage;
import io.singularitynet.MalmoMessageType;
import io.singularitynet.MissionHandlerInterfaces.ICommandHandler;
import io.singularitynet.SidesMessageHandler;
import io.singularitynet.projectmalmo.MissionInit;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;


/** Very basic control over inventory. Two commands are required: select and drop - each takes a slot.<br>
 * The effect is to swap the item stacks over - eg "select 10" followed by "drop 0" will swap the stacks
 * in slots 0 and 10.<br>
 * The hotbar slots are 0-8, so this mechanism allows an agent to move items in to/out of the hotbar.
 */
public class InventoryCommandsImplementationServer extends CommandBase implements IMalmoMessageListener
{
    private static final Logger LOGGER = LogManager.getLogger(InventoryCommandsImplementationServer.class.getName());

    @Override
    public void onMessage(MalmoMessageType messageType, Map<String, String> data) {
        throw new RuntimeException("calling client-side message handler on server " + messageType.toString());
    }

    @Override
    public void onMessage(MalmoMessageType messageType, Map<String, String> data, ServerPlayerEntity player) {
        InventoryMessage msg = new InventoryMessage(data);
        LOGGER.debug("InventoryCommandsImplementationServer.onMessage: " + msg);
        runCommand(msg, player);
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
        LOGGER.info("Installing InventoryCommandsImplementationServer");
        SidesMessageHandler.client2server.registerForMessage(this, MalmoMessageType.CLIENT_INVENTORY_CHANGE);
    }

    @Override
    public void deinstall(MissionInit missionInit) {
        LOGGER.info("Deinstalling InventoryCommandsImplementationServer");
        SidesMessageHandler.client2server.deregisterForMessage(this, MalmoMessageType.CLIENT_INVENTORY_CHANGE);
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

    private static InventoryCommandsImplementationServer.InventoryChangeMessage runCommand(InventoryMessage message, ServerPlayerEntity player){
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
        boolean tagsMatch = ItemStack.areEqual(dstStack, addStack);
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
    protected boolean onExecute(String verb, String parameter, MissionInit missionInit) {
        throw new RuntimeException("InventoryCommandsImplementationServer.onExecute() should never be called!");
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
}
