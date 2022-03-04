package io.singularitynet.MissionHandlerInterfaces;

import com.google.gson.JsonObject;
import io.singularitynet.projectmalmo.MissionInit;

public interface IObservationProducer {
    public void cleanup();
    public void prepare(MissionInit missionInit);

    void writeObservationsToJSON(JsonObject json, MissionInit currentMissionInit);
}
