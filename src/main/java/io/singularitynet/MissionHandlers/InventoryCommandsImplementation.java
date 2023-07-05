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
public class InventoryCommandsImplementation extends CommandGroup implements IVereyaMessageListener
{
    private static final Logger LOGGER = LogManager.getLogger(InventoryCommandsImplementation.class.getName());

    @Override
    public void onMessage(VereyaMessageType messageType, Map<String, String> data) {
        throw new RuntimeException("unexpected message from server " + messageType.toString());
    }

    @Override
    public void onMessage(VereyaMessageType messageType, Map<String, String> data, ServerPlayerEntity player) {
        throw new RuntimeException("Calling server-side message handler on client " + messageType.toString());
    }


    @Override
    protected boolean onExecute(String verb, String parameter, MissionInit missionInit)
    {

        if (verb.equalsIgnoreCase(InventoryCommand.SWAP_INVENTORY_ITEMS.value()))
        {
            LOGGER.debug("SWAP_INVENTORY_ITEMS: " + parameter);
            if (parameter != null && parameter.length() != 0)
            {
                List<Object> params = new ArrayList<Object>();
                if (getParameters(parameter, params))
                {
                    // All okay, so create a swap message for the server:
                    // InventoryChangeMessage msg = runCommand(new InventoryMessage(params, false));
                    LOGGER.debug("Sending SWAP_INVENTORY_ITEMS message to server");
                    ClientPlayNetworking.send(NetworkConstants.CLIENT2SERVER, (new InventoryCommandsImplementationServer.InventoryMessage(params, false)).toBytes());
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
                    LOGGER.debug("Sending COMBINE_INVENTORY_ITEMS message to server");
                    ClientPlayNetworking.send(NetworkConstants.CLIENT2SERVER, (new InventoryCommandsImplementationServer.InventoryMessage(params, false)).toBytes());
                    return true;
                }
                else
                    return false;   // Duff parameters.
            }
        }
        else if (verb.equalsIgnoreCase(InventoryCommand.DISCARD_CURRENT_ITEM.value()))
        {
            LOGGER.debug("discarding currently selected item");
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

}
