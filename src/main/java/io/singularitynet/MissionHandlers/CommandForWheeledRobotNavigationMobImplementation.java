package io.singularitynet.MissionHandlers;

import io.singularitynet.MalmoMessage;
import io.singularitynet.NetworkConstants;
import io.singularitynet.projectmalmo.ContinuousMovementCommand;
import io.singularitynet.projectmalmo.MissionInit;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class CommandForWheeledRobotNavigationMobImplementation extends CommandBase {
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
        if (verb == null || verb.length() == 0) {
            return false;
        }

        String[] params = parameter.split(" ");
        if (params.length != 2) {
            return false;
        }

        String entity_uuid = params[0];
        parameter = params[1];


        MalmoMessage malmoMessage = new CommandForWheeledRobotNavigationMobImplementationServer.MotionMessage(verb, entity_uuid, parameter);

        ClientPlayNetworking.send(NetworkConstants.CLIENT2SERVER, malmoMessage.toBytes());
        return true;
    }
}
