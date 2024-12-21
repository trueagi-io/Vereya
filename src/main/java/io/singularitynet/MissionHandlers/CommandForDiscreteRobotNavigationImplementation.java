package io.singularitynet.MissionHandlers;

import io.singularitynet.projectmalmo.MissionInit;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.apache.logging.log4j.LogManager;

import java.util.Map;


public class CommandForDiscreteRobotNavigationImplementation extends CommandBase {

    private boolean moveAgent(Vec3d movement){
        try {
            ClientPlayerEntity player = MinecraftClient.getInstance().player;
            Vec3d newPos = player.getPos().add(movement);
            player.setPosition(newPos);
            return true;
        }  catch (Exception e) {
            LogManager.getLogger().warn("Failed to increment player's pos to " + movement);
            LogManager.getLogger().warn(e);
            return false;
        }
    }


    @Override
    protected boolean onExecute(String verb, String parameter, MissionInit missionInit) {

        if (verb == null || verb.length() == 0)
        {
            return false;
        }
        Map<String, Vec3d> moveMap = Map.of(
                "movewest", new Vec3d(-1, 0, 0),
                "moveeast", new Vec3d(1, 0, 0),
                "movenorth", new Vec3d(0, 0, -1),
                "movesouth", new Vec3d(0, 0, 1)
        );
        String parameters[] = parameter.split(" ");
        if (parameters.length != 1) return false;
        // Now parse the command:
        String lowerVerb = verb.toLowerCase();
        if (!moveMap.containsKey(lowerVerb)){
            return false;
        }
        return moveAgent(moveMap.get(lowerVerb));
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
}
