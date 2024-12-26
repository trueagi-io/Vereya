package io.singularitynet.MissionHandlers;

import io.singularitynet.MissionHandlerInterfaces.ICommandHandler;
import io.singularitynet.projectmalmo.DiscreteMovementCommands;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class DiscreteMovementCommandsImplementation extends CommandGroup implements ICommandHandler {

    private static final Logger LOGGER = LogManager.getLogger(DiscreteMovementCommandsImplementation.class.getName());

    public DiscreteMovementCommandsImplementation(){
        setShareParametersWithChildren(true);	// Pass our parameter block on to the following children:
        this.addCommandHandler(new CommandForAttackAndUseImplementation());
        this.addCommandHandler(new CommandForDiscreteRobotNavigationImplementation());
    }

    @Override
    public boolean parseParameters(Object params)
    {
        super.parseParameters(params);

        if (params == null || !(params instanceof DiscreteMovementCommands))
            return false;

        DiscreteMovementCommands dmparams = (DiscreteMovementCommands)params;
        setUpAllowAndDenyLists(dmparams.getModifierList());
        return true;
    }

    @Override
    public boolean isFixed() { return true; }

}
