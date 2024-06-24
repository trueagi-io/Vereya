// --------------------------------------------------------------------------------------------------
//  Copyright (c) 2016 Microsoft Corporation
//
//  Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
//  associated documentation files (the "Software"), to deal in the Software without restriction,
//  including without limitation the rights to use, copy, modify, merge, publish, distribute,
//  sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
//  furnished to do so, subject to the following conditions:
//
//  The above copyright notice and this permission notice shall be included in all copies or
//  substantial portions of the Software.
//
//  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
//  NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
//  NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
//  DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
//  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
// --------------------------------------------------------------------------------------------------

package io.singularitynet.Server;


import io.singularitynet.MessagePayloadC2S;
import io.singularitynet.MessagePayloadS2C;
import io.singularitynet.SidesMessageHandler;
import io.singularitynet.projectmalmo.MissionInit;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;

public class VereyaModServer implements ModInitializer {
    private ServerStateMachine stateMachine = null;
    private static VereyaModServer instance = null;

    public static VereyaModServer getInstance() {
        return instance;
    }

    public Map<String, MobEntity> getControlledMobs(){
        if (this.stateMachine == null){
            return new HashMap<>();
        }
        return this.stateMachine.controllableEntities;
    }
    public boolean hasServer(){
        return stateMachine != null;
    }

    @Override
    public void onInitialize() {
        instance = this;
        // register the instance for messages from Client to the Server

        ServerPlayNetworking.registerGlobalReceiver(MessagePayloadS2C.ID, (payload, context) -> {
            SidesMessageHandler.onMessage(payload, context);
        });
        ServerLifecycleEvents.SERVER_STARTED.register((MinecraftServer server) -> {
            Logger LOGGER = LogManager.getLogger();
            LOGGER.info("Server started");
            if (stateMachine == null ) {
                this.stateMachine = new ServerStateMachine(ServerState.WAITING_FOR_MOD_READY, null, server);
            } else {
                this.stateMachine.queueStateChange(ServerState.WAITING_FOR_MOD_READY);
            }
        });
    }

    public void sendMissionInitDirectToServer(MissionInit minit) throws Exception
    {
        if (this.stateMachine == null)
            throw new Exception("Trying to send a mission request directly when no server has been created!");
        this.stateMachine.setMissionInit(minit);
    }

    public void initServerStateMachine(MissionInit init, MinecraftServer server){
        server.execute(() -> {
            Logger LOGGER = LogManager.getLogger();
            LOGGER.info("Server initialized");
            if (stateMachine == null ) {
                stateMachine = new ServerStateMachine(ServerState.WAITING_FOR_MOD_READY, init, server);
            } else {
                this.stateMachine.setMissionInit(init);
            }
        });
    }
}
