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

package io.singularitynet.Client;

import java.io.IOException;
import java.util.ArrayList;

import org.lwjgl.input.Mouse;

import io.singularitynet.ClientState;
import io.singularitynet.ClientStateMachine;
import io.singularitynet.IMalmoModClient;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;


public class MalmoModClient implements IMalmoModClient
{
    public interface MouseEventListener
    {
        public void onXYZChange(int deltaX, int deltaY, int deltaZ);
    }
    
    // Control overriding:
    enum InputType
    {
        HUMAN, AI
    }

    protected InputType inputType = InputType.HUMAN;

	private ClientStateMachine stateMachine;
	private static final String INFO_MOUSE_CONTROL = "mouse_control";

	public void init()
	{
        // Register for various events:
        MinecraftForge.EVENT_BUS.register(this);
        this.stateMachine = new ClientStateMachine(ClientState.WAITING_FOR_MOD_READY, (IMalmoModClient) this);

    }

}
