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

package io.singularitynet;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.chunk.WorldChunk;

public abstract class StateEpisode
{
    /** Flag to indicate whether or not the episode is "live" - ie active and wanting to receive callbacks.
     */
    private boolean isLive = false;
    protected StateMachine machine = null;

    protected StateEpisode(StateMachine machine)
    {
        this.machine = machine;
    }

    /** Is the episode active?
     * @return true if the episode is currently running and requires notification of events.
     */
    public boolean isLive() { return this.isLive; }

    /** Called to kick off the episode - should be no need for subclasses to override.
     */
    public void start() {
        this.isLive = true; // This episode is now active.
        try {
            execute();
        } catch (Exception e) {
            System.out.println("State start - exception: " + e);
            e.printStackTrace();
            // TODO... what?
        }
    }

    /** Called after the episode has been retired - use this to clean up any resources.
     */
    public void cleanup()
    {
    }

    /** Subclass should call this when the state has been completed;
     *  it will advance the tracker into the next state.
     */
    protected void episodeHasCompleted(IState nextState)
    {
        this.isLive = false;    // Immediately stop acting on events.
        this.machine.queueStateChange(nextState);  // And queue up the next state.
    }

    protected void episodeHasCompletedWithErrors(IState nextState, String error)
    {
        this.machine.saveErrorDetails(error);
        episodeHasCompleted(nextState);
    }

    /** Subclass should override this to carry out whatever operations
     * were intended to be run at this stage in the mod state.
     * @throws Exception
     */
    protected abstract void execute() throws Exception;

    /** Subclass should overrride this to act on client ticks.
     * @throws Exception */
    public void onClientTick(MinecraftClient client) throws Exception {}
    /** Subclass should overrride this to act on server tick end.*/
    protected void onServerTick(MinecraftServer server) {}

    /** Subclass should overrride this to act on player ticks.*/
    // protected void onPlayerTick(PlayerTickEvent ev) {}
    /** Subclass should overrride this to act on render ticks.*/
    public void onRenderTickEnd(WorldRenderContext ev) {}
    public void onRenderTickStart(WorldRenderContext ev) {}
    public void onClientStarted(MinecraftClient ev) {}
    /** Subclass should overrride this to act on chunk load events.*/
    public void onChunkLoad(ClientWorld world, WorldChunk chunk) {}
    /** Subclass should overrride this to act on player death events.*/
    // protected void onPlayerDies(LivingDeathEvent event) {}
    public void onTitleScreen(){};

    /** Subclass should overrride this to act on server tick start.*/
    protected void onServerTickStart(MinecraftServer ev) {};
}
