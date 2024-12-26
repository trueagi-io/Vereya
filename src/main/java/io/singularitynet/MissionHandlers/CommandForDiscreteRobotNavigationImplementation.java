package io.singularitynet.MissionHandlers;

import io.singularitynet.projectmalmo.MissionInit;
import net.minecraft.client.MinecraftClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;


public class CommandForDiscreteRobotNavigationImplementation extends CommandBase {

    private static final Logger LOGGER = LogManager.getLogger(CommandForDiscreteRobotNavigationImplementation.class.getName());

    private boolean moveAgent(String movement){
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            LOGGER.debug("moving agent to " + movement);
            client.player.networkHandler.sendCommand("tp " + movement);
            return true;
        }  catch (Exception e) {
            LogManager.getLogger().warn("Failed to move agent to " + movement);
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
        Map<String, String> moveMap = Map.of(
                "movewest", "~-1 ~ ~",
                "moveeast", "~1 ~ ~",
                "movenorth", "~ ~ ~-1",
                "movesouth", "~ ~ ~1"
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
