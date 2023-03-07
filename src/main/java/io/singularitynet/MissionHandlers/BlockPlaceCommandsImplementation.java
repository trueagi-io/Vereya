package io.singularitynet.MissionHandlers;

import io.singularitynet.projectmalmo.BlockPlaceCommand;
import io.singularitynet.projectmalmo.MissionInit;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

public class BlockPlaceCommandsImplementation extends CommandBase {

    /*
        place block at x,y,z
        placement is replace, destroy, keep
        blockType is block name like minecraft:stone
    */
    private void placeBlock(int x, int y, int z, String blockType, String placement){
        // use SetBlock command
        // https://minecraft.gamepedia.com/Commands/setblock
        MinecraftClient client = MinecraftClient.getInstance();
        client.player.networkHandler.sendCommand("setblock " + x + " " + y + " " + z + " " + blockType + " " + placement);
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

    }

    @Override
    public void deinstall(MissionInit currentMissionInit) {

    }

    @Override
    protected boolean onExecute(String verb, String parameter, MissionInit missionInit) {
        if (!verb.equalsIgnoreCase(BlockPlaceCommand.PLACE_BLOCK.value()))
        {
            return false;
        }
        // parse parameters
        String[] params = parameter.split(" ");
        if (params.length != 5)
        {
            return false;
        }
        int x = Integer.parseInt(params[0]);
        int y = Integer.parseInt(params[1]);
        int z = Integer.parseInt(params[2]);
        String blockType = params[3];
        String placement = params[4];
        if (!(placement != "replace" || placement != "destroy" || placement != "keep"))
        {
            return false;
        }
        placeBlock(x, y, z, blockType, placement);
        return true;
    }
}
