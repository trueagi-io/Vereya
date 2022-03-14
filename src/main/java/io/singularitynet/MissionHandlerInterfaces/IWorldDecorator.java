package io.singularitynet.MissionHandlerInterfaces;

import io.singularitynet.projectmalmo.MissionInit;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public interface IWorldDecorator {

    /** Gives the decorator a chance to add any client-side mission handlers that might be required - eg end-points for the maze generator, etc -
     * and to communicate (via the map) any data back to the client-side.
     * @param handlers A list of handlers to which the decorator can add
     * @param data A map which will be passed to the client
     * @return true if new decorators were added
     */
    public boolean getExtraAgentHandlersAndData(List<Object> handlers, Map<String, String> data);

    /** Used by the turn scheduler - if the decorator wants to be part of the turn schedule, it must add a name
     * and a requested slot (can be null) to these arrays.
     * @param participants
     * @param participantSlots
     */
    public void getTurnParticipants(ArrayList<String> participants, ArrayList<Integer> participantSlots);

    /** Used by the turn scheduler - if decorator matches this string, it must acknowledge and take its turn.
     * @param nextAgentName - string to match against
     * @return true if matching
     */
    public boolean targetedUpdate(String nextAgentName);

    /** Called once AFTER buildOnWorld but before the mission starts - use for any necessary mission initialisation.
     */
    public void prepare(MissionInit missionInit);

    /** Called periodically by the server, during the mission run. Use to provide dynamic behaviour.
     * @param world the World we are controlling.
     */
    void update(World world);

    /** Called once after the mission ends - use for any necessary mission cleanup.
     */
    public void cleanup();
}
