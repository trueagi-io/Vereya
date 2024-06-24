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
package io.singularitynet.MissionHandlers;


import io.singularitynet.*;

import io.singularitynet.projectmalmo.MissionInit;
import io.singularitynet.projectmalmo.SimpleCraftCommand;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;


public class SimpleCraftCommandsImplementation extends CommandBase  implements IVereyaMessageListener {
    private boolean isOverriding;
    // Directly reference a log4j logger.
    private static final Logger LOGGER = LogManager.getLogger(SimpleCraftCommandsImplementation.class.getName());


    @Override
    protected boolean onExecute(String verb, String parameter, MissionInit missionInit)
    {
        if (verb.equalsIgnoreCase(SimpleCraftCommand.CRAFT.value()))
        {
            LOGGER.info("Sending crafting message " + verb);
            ClientPlayNetworking.send(new MessagePayloadC2S(new SimpleCraftCommandsImplementationServer.CraftMessage(parameter)));
            return true;
        }
        return false;
    }

    @Override
    public void onMessage(VereyaMessageType messageType, Map<String, String> data) {
        throw new RuntimeException("Unexpected message to client");
    }

    @Override
    public void onMessage(VereyaMessageType messageType, Map<String, String> data, ServerPlayerEntity player) {
        throw new RuntimeException("calling server-side message handler on client");
    }

    @Override
    public boolean isOverriding()
    {
        return this.isOverriding;
    }

    @Override
    public void setOverriding(boolean b)
    {
        this.isOverriding = b;
    }

    @Override
    public void install(MissionInit currentMissionInit) {
        LOGGER.debug("Installing SimpleCraftCommandsImplementation");
    }

    @Override
    public void deinstall(MissionInit currentMissionInit) {
        LOGGER.debug("Denstalling SimpleCraftCommandsImplementation");
    }

}
