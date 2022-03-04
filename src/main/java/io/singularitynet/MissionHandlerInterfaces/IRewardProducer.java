package io.singularitynet.MissionHandlerInterfaces;

import io.singularitynet.projectmalmo.MissionInit;

public interface IRewardProducer {
    public void cleanup();
    public void prepare(MissionInit missionInit);
}
