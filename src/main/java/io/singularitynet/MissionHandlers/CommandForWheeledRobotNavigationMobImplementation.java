package io.singularitynet.MissionHandlers;

import io.singularitynet.VereyaMessage;
import io.singularitynet.NetworkConstants;
import io.singularitynet.VereyaMessageType;
import io.singularitynet.projectmalmo.MissionInit;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.Map;

public class CommandForWheeledRobotNavigationMobImplementation extends CommandBase {


    public static class MotionMessage extends VereyaMessage {

        public MotionMessage(String parameters, String uuid, String value){
            super(VereyaMessageType.CLIENT_MOVE, parameters);
            this.getData().put("uuid", uuid);
            this.getData().put("value", value);
        }

        public MotionMessage(Map<String, String> data) {
            super(VereyaMessageType.CLIENT_MOVE, data.get("message"));
            this.getData().put("uuid", data.get("uuid"));
            this.getData().put("value", data.get("value"));
        }

        public String getUuid(){
            return this.getData().get("uuid");
        }

        public String getValue(){
            return this.getData().get("value");
        }

        public String getVerb(){
            return this.getData().get("message");
        }
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


        VereyaMessage vereyaMessage = new MotionMessage(verb, entity_uuid, parameter);

        ClientPlayNetworking.send(NetworkConstants.CLIENT2SERVER, vereyaMessage.toBytes());
        return true;
    }
}
