package io.singularitynet.MissionHandlers;

import io.singularitynet.projectmalmo.BlockPlaceCommand;
import io.singularitynet.projectmalmo.MissionInit;
import io.singularitynet.projectmalmo.MobPlaceCommand;
import net.minecraft.client.MinecraftClient;

public class MobPlaceCommandsImplementation  extends CommandBase {
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
        if (!verb.equalsIgnoreCase(MobPlaceCommand.PLACE_MOB.value()))
        {
            return false;
        }
        String params[] = parameter.strip().split("\s+");
        if (params.length == 2){ // interpret as mob name, controllable pair
            if (params[0].equals("Chicken")) {
                MinecraftClient client = MinecraftClient.getInstance();
                client.player.networkHandler.sendCommand("summon chicken");
            }
        }
        return false;
    }
}
