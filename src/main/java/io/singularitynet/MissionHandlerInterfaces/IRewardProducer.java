package io.singularitynet.MissionHandlerInterfaces;

import io.singularitynet.MissionHandlers.MultidimensionalReward;
import io.singularitynet.projectmalmo.MissionInit;

public interface IRewardProducer {
    public void cleanup();
    public void prepare(MissionInit missionInit);
    public void getReward(MultidimensionalReward reward);
    public void trigger(Class<?> clazz);
}
