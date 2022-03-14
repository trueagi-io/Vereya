package io.singularitynet.MissionHandlerInterfaces;

import io.singularitynet.projectmalmo.MissionInit;

public interface IWantToQuit {
    public void cleanup();

    boolean doIWantToQuit(MissionInit currentMissionInit);

    String getOutcome();

    /** Called once AFTER buildOnWorld but before the mission starts - use for any necessary mission initialisation.
     */
    public void prepare(MissionInit missionInit);
}
