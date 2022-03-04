package io.singularitynet.MissionHandlerInterfaces;

import io.singularitynet.projectmalmo.MissionInit;

public interface IWantToQuit {
    public void cleanup();
    public void prepare(MissionInit missionInit);

    boolean doIWantToQuit(MissionInit currentMissionInit);

    String getOutcome();
}
