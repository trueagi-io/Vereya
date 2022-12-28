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

import io.singularitynet.MissionHandlerInterfaces.ICommandHandler;
import io.singularitynet.MissionHandlerInterfaces.IWantToQuit;
import io.singularitynet.projectmalmo.MissionInit;
import io.singularitynet.projectmalmo.MissionQuitCommand;
import io.singularitynet.projectmalmo.MissionQuitCommands;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;

/** Quit command allows for the agent to abort its mission at any time. */
public class MissionQuitCommandsImplementation  extends CommandBase implements ICommandHandler
{
    private boolean isOverriding;
    private boolean iWantToQuit;
    protected MissionQuitCommands quitcomParams;

    @Override
    protected boolean onExecute(String verb, String parameter, MissionInit missionInit)
    {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null)
        {
            return false;
        }

        if (!verb.equalsIgnoreCase(MissionQuitCommand.QUIT.value()))
        {
            return false;
        }

        player.sendChatMessage( "Quitting mission", null);
        this.iWantToQuit = true;
        return true;
    }

    @Override
    public boolean parseParameters(Object params)
    {
        if (params == null || !(params instanceof MissionQuitCommands))
            return false;

        this.quitcomParams = (MissionQuitCommands)params;
        setUpAllowAndDenyLists(this.quitcomParams.getModifierList());
        return true;
    }

    // ------------- ICommandHandler methods -----------
    @Override
    public void install(MissionInit missionInit)
    {
        // In order to trigger the end of the mission, we need to hook into the quit handlers.
        MissionBehaviour mb = parentBehaviour();
        mb.addQuitProducer(new IWantToQuit()
        {
            @Override
            public void prepare(MissionInit missionInit)
            {
            }

            @Override
            public String getOutcome()
            {
                return MissionQuitCommandsImplementation.this.quitcomParams.getQuitDescription();
            }

            @Override
            public boolean doIWantToQuit(MissionInit missionInit)
            {
                return MissionQuitCommandsImplementation.this.iWantToQuit;
            }

            @Override
            public void cleanup()
            {
            }
        });
    }

    @Override
    public void deinstall(MissionInit missionInit)
    {
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
}