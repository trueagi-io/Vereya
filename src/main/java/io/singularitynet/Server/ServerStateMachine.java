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

import io.singularitynet.MissionHandlers.MissionBehaviour;
import io.singularitynet.StateMachine;
import io.singularitynet.projectmalmo.MissionInit;

/**
 * Class designed to track and control the state of the mod, especially regarding mission launching/running.<br>
 * States are defined by the MissionState enum, and control is handled by MissionStateEpisode subclasses.
 * The ability to set the state directly is restricted, but hooks such as onPlayerReadyForMission etc are exposed to allow
 * subclasses to react to certain state changes.<br>
 * The ProjectMalmo mod app class inherits from this and uses these hooks to run missions.
 */
public class ServerStateMachine extends StateMachine {
    private MissionInit currentMissionInit = null;   	// The MissionInit object for the mission currently being loaded/run.
    private MissionInit queuedMissionInit = null;		// The MissionInit requested from elsewhere - dormant episode will check for its presence.
    private MissionBehaviour missionHandlers = null;	// The Mission handlers for the mission currently being loaded/run.
    protected String quitCode = "";						// Code detailing the reason for quitting this mission.
}