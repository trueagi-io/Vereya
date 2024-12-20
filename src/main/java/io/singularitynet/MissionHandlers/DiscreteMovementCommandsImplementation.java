package io.singularitynet.MissionHandlers;

import io.singularitynet.MissionHandlerInterfaces.ICommandHandler;
import io.singularitynet.projectmalmo.DiscreteMovementCommand;
import io.singularitynet.projectmalmo.DiscreteMovementCommands;
import io.singularitynet.projectmalmo.MissionInit;
import net.minecraft.client.MinecraftClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;

public class DiscreteMovementCommandsImplementation extends CommandGroup implements ICommandHandler {

    private static final Logger LOGGER = LogManager.getLogger(DiscreteMovementCommandsImplementation.class.getName());


    private void moveAgent(String verb, String param){
        // use tp command
        // https://minecraft.wiki/w/Commands/teleport
        Map<String, String> moveAction = new HashMap<String, String>();
        moveAction.put("movesouth", "~ ~ ~1");
        moveAction.put("movenorth", "~ ~ ~-1");
        moveAction.put("moveeast", "~1 ~ ~");
        moveAction.put("movewest", "~-1 ~ ~");
        MinecraftClient client = MinecraftClient.getInstance();
//        LOGGER.debug("setting block at " + x + " " + y + " " + z + " " + placement);
        client.player.networkHandler.sendCommand("tp " + moveAction.get(verb));
    }


    public DiscreteMovementCommandsImplementation(){
        setShareParametersWithChildren(true);	// Pass our parameter block on to the following children:
        this.addCommandHandler(new CommandForAttackAndUseImplementation());
//        this.addCommandHandler(new CommandForWheeledRobotNavigationImplementation());
    }

    @Override
    public boolean parseParameters(Object params)
    {
        super.parseParameters(params);

        if (params == null || !(params instanceof DiscreteMovementCommands))
            return false;

        DiscreteMovementCommands cmparams = (DiscreteMovementCommands)params;
        setUpAllowAndDenyLists(cmparams.getModifierList());
        return true;
    }

    @Override
    public boolean isFixed() { return true; }

    @Override
    protected boolean onExecute(String verb, String parameter, MissionInit missionInit) {
        LOGGER.debug("move command " + parameter);
        moveAgent(verb, parameter);
        return true;
    }

}
