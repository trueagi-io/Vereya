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

import io.singularitynet.*;
import io.singularitynet.MissionHandlers.MissionBehaviour;
import io.singularitynet.projectmalmo.*;
import io.singularitynet.utils.SchemaHelper;
import io.singularitynet.utils.ScreenHelper;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


import java.util.*;

import static io.singularitynet.MalmoMessageType.CLIENT_BAILED;

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
    private MinecraftServer server;
    private static final Logger LOGGER = LogManager.getLogger();

    // agentConnectionWatchList is used to keep track of the clients in a multi-agent mission. If, at any point, a username appears in
    // this list, but can't be found in the MinecraftServer.getServer().getAllUsernames(), that constitutes an error, and the mission will exit.
    private ArrayList<String> userConnectionWatchList = new ArrayList<String>();
    private ArrayList<String> userTurnSchedule = new ArrayList<String>();

    /** Called to initialise a state machine for a specific Mission request.<br>
     * Most likely caused by the client creating an integrated server.
     * @param initialState Initial state of the machine
     * @param minit The MissionInit object requested
     */
    public ServerStateMachine(ServerState initialState, MissionInit minit, MinecraftServer server)
    {
        super(initialState);
        this.currentMissionInit = minit;
        this.server = server;
        // Register ourself on the event busses, so we can harness the server tick:
        ServerTickEvents.END_SERVER_TICK.register(s -> this.onServerTick(s));
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> this.onServerStopped(s));
        ServerEntityEvents.ENTITY_LOAD.register((e, w) -> this.onGetPotentialSpawns(e, w));
    }

    /** Used to prevent spawning in our world.*/
    public void onGetPotentialSpawns(Entity entity, ServerWorld world)
    {
        // Decide whether or not to allow spawning.
        // We shouldn't allow spawning unless it has been specifically turned on - whether
        // a mission is running or not. (Otherwise spawning may happen in between missions.)
        EntityType type = entity.getType();
        String mobName = type.getUntranslatedName();
        boolean allowSpawning = false;
        if (currentMissionInit() != null && currentMissionInit().getMission() != null)
        {
            // There is a mission running - does it allow spawning?
            ServerSection ss = currentMissionInit().getMission().getServerSection();
            ServerInitialConditions sic = (ss != null) ? ss.getServerInitialConditions() : null;
            if (sic != null)
                allowSpawning = (sic.isAllowSpawning() == Boolean.TRUE);

            if (allowSpawning && sic.getAllowedMobs() != null && !sic.getAllowedMobs().isEmpty())
            {
                // Spawning is allowed, but restricted to our list:
                // Is this on our list?
                boolean allowed = false;
                for (EntityTypes mob : sic.getAllowedMobs())
                {
                    if (mob.value().toLowerCase().equals(mobName.toLowerCase())) { allowed = true; }
                }
                if (!allowed) {
                    if (entity.isPlayer()) return;
                    if (!entity.isLiving()) return;
                    LOGGER.trace("removing mob " + mobName + ": it's disabled");
                    entity.remove(Entity.RemovalReason.DISCARDED);
                    return;
                }
            }
        }
        // Cancel spawn event:
        if (!allowSpawning) {
            if (!entity.isLiving()) return;
            LOGGER.trace("removing mob "  + mobName + " : spawning is disabled");
            if (entity.isPlayer()) {
                LOGGER.info("not removing player " + ((ServerPlayerEntity)entity).getName().getString());
                return;
            }
            entity.remove(Entity.RemovalReason.DISCARDED);
        }
    }

    private void onServerStopped(MinecraftServer s){
        this.stop();
        this.releaseQueuedMissionInit();
        ServerStateMachine.this.currentMissionInit = null;
        LOGGER.info("server stopped");
    }

    public void setMissionInit(MissionInit minit)
    {
        this.queuedMissionInit = minit;
        LOGGER.info("set queuedMissionInit" + minit.toString());
    }

    protected void setUserTurnSchedule(ArrayList<String> schedule)
    {
        this.userTurnSchedule = schedule;
    }

    protected void addUsernameToWatchList(String username)
    {
        this.userConnectionWatchList.add(username); // Must be username, not agentname.
    }

    protected void clearUserConnectionWatchList()
    {
        this.userConnectionWatchList.clear();
    }

    protected void clearUserTurnSchedule()
    {
        this.userTurnSchedule.clear();
    }

    protected MissionInit currentMissionInit()
    {
        return this.currentMissionInit;
    }

    protected String getNextAgentInTurnSchedule(String currentAgent)
    {
        int i = this.userTurnSchedule.indexOf(currentAgent);
        if (i < 0)
            return null;    // Big problem!
        i += 1;
        return this.userTurnSchedule.get(i % this.userTurnSchedule.size());
    }


    protected boolean hasQueuedMissionInit()
    {
        return this.queuedMissionInit != null;
    }

    public void onServerTick(MinecraftServer ev)
    {
        // Use the server tick to ensure we regularly update our state (from the server thread)
        updateState();
    }

    protected void removeFromTurnSchedule(String agent)
    {
        this.userTurnSchedule.remove(agent);    // Does nothing if the agent wasn't in the list to begin with.
    }

    protected MissionBehaviour getHandlers()
    {
        return this.missionHandlers;
    }

    @Override
    protected String getName() { return "SERVER"; }

    protected void initialiseHandlers(MissionInit init) throws Exception
    {
        this.missionHandlers = MissionBehaviour.createServerHandlersFromMissionInit(init);
    }

    @Override
    protected void onPreStateChange(IState toState)
    {
        String text = "SERVER: " + toState;
        Map<String, String> data = new HashMap<String, String>();
        data.put("text", text);
        data.put("category", ScreenHelper.TextCategory.TXT_SERVER_STATE.name());
        sendToAll(new MalmoMessage(MalmoMessageType.SERVER_TEXT, 0, data));
    }

    private void sendToAll(MalmoMessage msg){
        MinecraftServer server = MinecraftClient.getInstance().getServer();
        if (server == null){
            LOGGER.error("server is null, msg type " + msg.getMessageType().toString());
            for (Map.Entry entry: msg.getData().entrySet()){
                LOGGER.debug(entry.getKey().toString() + ": " + entry.getValue().toString());
            }
            return;
        }
        for(ServerPlayerEntity player: server.getPlayerManager().getPlayerList()){
            ServerPlayNetworking.send(player, NetworkConstants.SERVER2CLIENT,
                    msg.toBytes());
        }
    }

    protected void setMissionWeather(MissionInit minit, ServerWorld world){
        ServerSection ss = minit.getMission().getServerSection();
        ServerInitialConditions sic = (ss != null) ? ss.getServerInitialConditions() : null;
        if (sic != null && sic.getWeather() != null && !sic.getWeather().equalsIgnoreCase("normal")){
            int maxtime = 1000000 * 20; // Max allowed by Minecraft's own Weather Command.
            int cleartime = (sic.getWeather().equalsIgnoreCase("clear")) ? maxtime : 0;
            int raintime = (sic.getWeather().equalsIgnoreCase("rain")) ? maxtime : 0;
            int thundertime = (sic.getWeather().equalsIgnoreCase("thunder")) ? maxtime : 0;
            world.setWeather(cleartime, raintime, raintime > 0,  thundertime > 0);
        }
    }

    protected boolean checkWatchList()
    {
        MinecraftServer server = MinecraftClient.getInstance().getServer();
        if (server == null) {
            return false;
        }
        String[] connected_users = server.getPlayerManager().getPlayerNames();
        // String[] connected_users = FMLCommonHandler.instance().getMinecraftServerInstance().getOnlinePlayerNames();
        if (connected_users.length < this.userConnectionWatchList.size())
            return false;

        // More detailed check (since there may be non-mission-required connections - eg a human spectator).
        for (String username : this.userConnectionWatchList)
        {
            boolean bFound = false;
            for (int i = 0; i < connected_users.length && !bFound; i++)
            {
                if (connected_users[i].equals(username))
                    bFound = true;
            }
            if (!bFound)
                return false;
        }
        return true;
    }

    protected MissionInit releaseQueuedMissionInit()
    {
        if (this.queuedMissionInit == null) return null;
        MissionInit minit = null;
        synchronized (this.queuedMissionInit)
        {
            minit = this.queuedMissionInit;
            this.queuedMissionInit = null;
        }
        LOGGER.debug("releasing queued mission init");
        return minit;
    }

    /** Create the episode object for the requested state.
     * @param state the state the mod is entering
     * @return a MissionStateEpisode that localises all the logic required to run this state
     */
    @Override
    protected StateEpisode getStateEpisodeForState(IState state)
    {
        if (!(state instanceof ServerState))
            return null;

        ServerState sstate = (ServerState)state;
        switch (sstate)
        {
            case WAITING_FOR_MOD_READY:
                return new InitialiseServerModEpisode(this);
            case DORMANT:
                return new DormantEpisode(this);
            case BUILDING_WORLD:
                return new BuildingWorldEpisode(this);
            case WAITING_FOR_AGENTS_TO_ASSEMBLE:
                return new WaitingForAgentsEpisode(this);
            case RUNNING:
                return new RunningEpisode(this);
            case WAITING_FOR_AGENTS_TO_QUIT:
                return new WaitingForAgentsToQuitEpisode(this);
            case ERROR:
                return new ErrorEpisode(this);
            case CLEAN_UP:
                return new CleanUpEpisode(this);
            case MISSION_ENDED:
                return null;//new MissionEndedEpisode(this, MissionResult.ENDED);
            case MISSION_ABORTED:
                return null;//new MissionEndedEpisode(this, MissionResult.AGENT_QUIT);
            default:
                break;
        }
        return null;
    }

    /** Initial episode - perform client setup */
    public class InitialiseServerModEpisode extends StateEpisode
    {
        ServerStateMachine ssmachine;

        protected InitialiseServerModEpisode(ServerStateMachine machine)
        {
            super(machine);
            this.ssmachine = machine;
        }

        @Override
        protected void execute() throws Exception
        {
        }

        @Override
        protected void onServerTick(MinecraftServer ev)
        {
            // We wait until we start to get server ticks, at which point we assume Minecraft has finished starting up.
            episodeHasCompleted(ServerState.DORMANT);
        }
    }

    //---------------------------------------------------------------------------------------------------------
    // Episode helpers - each extends a MissionStateEpisode to encapsulate a certain state
    //---------------------------------------------------------------------------------------------------------
    public abstract class ErrorAwareEpisode extends StateEpisode implements IMalmoMessageListener
    {
        protected Boolean errorFlag = false;
        protected Map<String, String> errorData = null;

        public ErrorAwareEpisode(ServerStateMachine machine)
        {
            super(machine);
            // MalmoMod.MalmoMessageHandler.registerForMessage(this, MalmoMessageType.CLIENT_BAILED);
            SidesMessageHandler.client2server.registerForMessage(this, CLIENT_BAILED);
        }

        @Override
        public void onMessage(MalmoMessageType messageType, Map<String, String> data, ServerPlayerEntity player)
        {
            LOGGER.info("Got message: " + messageType.name());
            LOGGER.info(data.toString());
            if (messageType == CLIENT_BAILED)
            {
                synchronized(this.errorFlag)
                {
                    this.errorFlag = true;
                    this.errorData = data;
                    onError(data);
                }
            }
        }

        @Override
        public void onMessage(MalmoMessageType messageType, Map<String, String> data){
            throw new RuntimeException("Got client message in Server side");
        }

        @Override
        public void cleanup()
        {
            super.cleanup();
            SidesMessageHandler.client2server.deregisterForMessage(this, CLIENT_BAILED);
            // MalmoMod.MalmoMessageHandler.deregisterForMessage(this, CLIENT_BAILED);
        }

        protected boolean inErrorState()
        {
            synchronized(this.errorFlag)
            {
                return this.errorFlag;
            }
        }

        protected Map<String, String> getErrorData()
        {
            synchronized(this.errorFlag)
            {
                return this.errorData;
            }
        }

        protected void onError(Map<String, String> errorData) {}	// Default does nothing, but can be overridden.
    }

    //---------------------------------------------------------------------------------------------------------
    /** Dormant state - receptive to new missions */
    public class DormantEpisode extends ErrorAwareEpisode
    {
        private ServerStateMachine ssmachine;

        protected DormantEpisode(ServerStateMachine machine)
        {
            super(machine);
            this.ssmachine = machine;
            if (machine.hasQueuedMissionInit())
            {
                // This is highly suspicious - the queued mission init is a mechanism whereby the client state machine can pass its mission init
                // on to the server - which should only happen if the client has accepted the mission init, which in turn should only happen if the
                // server is dormant.
                // If a mission is queued up now, as we enter the dormant state, that would indicate an error - we've seen this in cases where the client
                // has passed the mission across, then hit an error case and aborted. In such cases this mission is now stale, and should be abandoned.
                // To guard against errors of this sort, simply clear the mission now:
                LOGGER.warn("POSSIBLY STALE MISSIONINIT");
            }
        }

        @Override
        protected void execute()
        {
            // Clear out our error state:
            clearErrorDetails();

            // There are two ways we can receive a mission command. In order of priority, they are:
            // 1: Via a MissionInit object, passed directly in to the state machine's constructor.
            // 2: Requested directly - usually as a result of the client that owns the integrated server needing to pass on its MissionInit.
            // The first of these can be checked for here.
            // The second will be checked for repeatedly during server ticks.
            if (currentMissionInit() != null)
            {
                System.out.println("INCOMING MISSION: Received MissionInit directly through ServerStateMachine constructor.");
                onReceiveMissionInit(currentMissionInit());
            }
        }

        @Override
        protected void onServerTick(MinecraftServer ev)
        {
            try
            {
                checkForMissionCommand();
            }
            catch (Exception e)
            {
                // TODO: What now?
                e.printStackTrace();
            }
        }

        private void checkForMissionCommand() throws Exception
        {
            // Check whether a mission request has come in "directly":
            if (ssmachine.hasQueuedMissionInit())
            {
                System.out.println("INCOMING MISSION: Received MissionInit directly through queue.");
                onReceiveMissionInit(ssmachine.releaseQueuedMissionInit());
            }
        }

        protected void onReceiveMissionInit(MissionInit missionInit)
        {
            /*
            MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
            System.out.println("Mission received: " + missionInit.getMission().getAbout().getSummary());
            TextComponentString txtMission = new TextComponentString("Received mission: " + TextFormatting.BLUE + missionInit.getMission().getAbout().getSummary());
            TextComponentString txtSource = new TextComponentString("Source: " + TextFormatting.GREEN + missionInit.getClientAgentConnection().getAgentIPAddress());
            server.getPlayerList().sendMessage(txtMission);
            server.getPlayerList().sendMessage(txtSource);
            */
            ServerStateMachine.this.currentMissionInit = missionInit;
            // Create the Mission Handlers
            try
            {
                this.ssmachine.initialiseHandlers(missionInit);
            }
            catch (Exception e)
            {
                // TODO: What?
            }
            // Move on to next state:
            ServerSection ss = currentMissionInit().getMission().getServerSection();
            ServerInitialConditions sic = (ss != null) ? ss.getServerInitialConditions() : null;
            String allowed_mobs = (sic != null) ? sic.getAllowedMobs().toString() : "";
            LOGGER.info("setting mission init, allowed mobs:\n" + allowed_mobs);
            episodeHasCompleted(ServerState.BUILDING_WORLD);
        }
    }

    //---------------------------------------------------------------------------------------------------------
    /** Building world episode - assess world requirements and set up our server accordingly */
    public class BuildingWorldEpisode extends ErrorAwareEpisode
    {
        private ServerStateMachine ssmachine;

        protected BuildingWorldEpisode(ServerStateMachine machine)
        {
            super(machine);
            this.ssmachine = machine;
        }

        @Override
        protected void execute()
        {
            boolean builtOkay = true;
            /*
            MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
            World world = server.getEntityWorld();
            MissionBehaviour handlers = this.ssmachine.getHandlers();
            // Assume the world has been created correctly - now do the necessary building.
            if (handlers != null && handlers.worldDecorator != null)
            {
                try
                {
                    handlers.worldDecorator.buildOnWorld(this.ssmachine.currentMissionInit(), world);
                }
                catch (DecoratorException e)
                {
                    // Error attempting to decorate the world - abandon the mission.
                    builtOkay = false;
                    if (e.getMessage() != null)
                        saveErrorDetails(e.getMessage());
                    // Tell all the clients to abort:
                    Map<String, String>data = new HashMap<String, String>();
                    data.put("message", getErrorDetails());
                    MalmoMod.safeSendToAll(MalmoMessageType.SERVER_ABORT, data);
                    // And abort ourselves:
                    episodeHasCompleted(ServerState.ERROR);
                }
            }*/
            if (builtOkay)
            {
                // Now set up other attributes of the environment (eg weather)
                int worldCount = 0;
                ServerWorld world = null;
                for (ServerWorld w:  MinecraftClient.getInstance().getServer().getWorlds()) {
                    worldCount ++;
                    world = w;
                }
                assert (worldCount == 1);
                setMissionWeather(currentMissionInit(), world);
                episodeHasCompleted(ServerState.WAITING_FOR_AGENTS_TO_ASSEMBLE);
            }
        }

        @Override
        protected void onError(Map<String, String> errorData)
        {
            episodeHasCompleted(ServerState.ERROR);
        }
    }


    //---------------------------------------------------------------------------------------------------------
    /** Wait for all participants to join the game.*/
    public class WaitingForAgentsEpisode extends ErrorAwareEpisode implements IMalmoMessageListener
    {
        // pendingReadyAgents starts full - agent is removed when it joins the server. When list is empty, moves to next phase (waiting for running).
        private ArrayList<String> pendingReadyAgents = new ArrayList<String>();

        // pendingRunningAgents starts empty - agent is added when it joins the server, removed again when it starts running.
        private ArrayList<String> pendingRunningAgents = new ArrayList<String>();

        // Map between usernames and agent names.
        private HashMap<String, String> usernameToAgentnameMap = new HashMap<String, String>();

        // Map used to build turn schedule for turn-based agents.
        private Map<Integer, String> userTurnScheduleMap = new HashMap<Integer, String>();

        protected WaitingForAgentsEpisode(ServerStateMachine machine)
        {
            super(machine);
            SidesMessageHandler.client2server.registerForMessage(this, MalmoMessageType.CLIENT_AGENTREADY);
            SidesMessageHandler.client2server.registerForMessage(this, MalmoMessageType.CLIENT_AGENTRUNNING);

            ServerStateMachine.this.clearUserConnectionWatchList(); // We will build this up as agents join us.
            ServerStateMachine.this.clearUserTurnSchedule();        // We will build this up too, if needed.
        }

        @Override
        public void cleanup()
        {
            super.cleanup();
            SidesMessageHandler.client2server.deregisterForMessage(this, MalmoMessageType.CLIENT_AGENTREADY);
            SidesMessageHandler.client2server.deregisterForMessage(this, MalmoMessageType.CLIENT_AGENTRUNNING);
        }

        private void addUsernameToTurnSchedule(String username, Integer requestedPosition)
        {
            // Agent "username" has requested a certain position in the turn schedule.
            // Honour their request if possible.
            // If they selected a free slot, put them in it. Otherwise, or if they didn't specify,
            // give them an index which is guaranteed to be free, and which will be incorporated into
            // the order once all agents have been added.
            if (requestedPosition == null || this.userTurnScheduleMap.containsKey(requestedPosition))
                requestedPosition = -this.userTurnScheduleMap.size();
            this.userTurnScheduleMap.put(requestedPosition, username);
        }

        private void saveTurnSchedule()
        {
            if (this.userTurnScheduleMap.isEmpty())
                return;

            // Create an order from the map:
            List<Integer> keys = new ArrayList<Integer>(this.userTurnScheduleMap.keySet());
            Collections.sort(keys);
            ArrayList<String> schedule = new ArrayList<String>();
            // First add the agents with well-specified positions:
            for (Integer i : keys)
            {
                if (i >= 0)
                    schedule.add(this.userTurnScheduleMap.get(i));
            }
            // Now add the agents which didn't have well-specified positions.
            // Add them in reverse order:
            Collections.reverse(keys);
            for (Integer i : keys)
            {
                if (i < 0)
                    schedule.add(this.userTurnScheduleMap.get(i));
            }
            ServerStateMachine.this.setUserTurnSchedule(schedule);
        }

        @Override
        public void onMessage(MalmoMessageType messageType, Map<String, String> data) {
            throw new RuntimeException("Unexpected message to client: " + messageType.name());
        }

        @Override
        public void onMessage(MalmoMessageType messageType, Map<String, String> data, ServerPlayerEntity player)
        {
            super.onMessage(messageType, data, player);
            if (messageType == MalmoMessageType.CLIENT_AGENTREADY)
            {
                // A client has joined and is waiting for us to tell us it can proceed.
                // Initialise the player, and store a record mapping from the username to the agentname.
                String username = data.get("username");
                String agentname = data.get("agentname");
                if (username != null && agentname != null && this.pendingReadyAgents.contains(agentname))
                {
                    initialisePlayer(username, agentname);
                    this.pendingReadyAgents.remove(agentname);
                    this.usernameToAgentnameMap.put(username, agentname);
                    this.pendingRunningAgents.add(username);
                    ServerStateMachine.this.addUsernameToWatchList(username);   // Now we've got it, we need to watch it - if it disappears, that's an error.
                    // Does this client want to be added to the turn scheduler?
                    String requestedTurnPosition = data.get("turnPosition");
                    if (requestedTurnPosition != null)
                    {
                        Integer pos = Integer.valueOf(requestedTurnPosition);
                        addUsernameToTurnSchedule(username, pos);
                    }
                    // If all clients have now joined, we can tell them to go ahead.
                    if (this.pendingReadyAgents.isEmpty())
                        onCastAssembled();
                }
            }
            else if (messageType == MalmoMessageType.CLIENT_AGENTRUNNING)
            {
                // A client has entered the running state (only happens once all CLIENT_AGENTREADY messages have arrived).
                String username = data.get("username");
                String agentname = this.usernameToAgentnameMap.get(username);
                if (username != null && this.pendingRunningAgents.contains(username))
                {
                    // Reset their position once more. We need to do this because the player can easily sink into
                    // a chunk if it takes too long to load.
                    if (agentname != null && !agentname.isEmpty())
                    {
                        AgentSection as = getAgentSectionFromAgentName(agentname);
                        if (player != null && as != null)
                        {
                            // Set their initial position and speed:
                            PosAndDirection pos = as.getAgentStart().getPlacement();

                            if (pos != null) {
                                LOGGER.info("Setting agent pos to: x(" + pos.getX() + ") z(" + pos.getZ()  + ") y(" + pos.getY() + ")");
                                player.setPos(pos.getX().doubleValue(),
                                        pos.getY().doubleValue(),
                                        pos.getZ().doubleValue());
                            }
                            // And set their game type back now:
                            this.setGameType((ServerPlayerEntity) player, GameMode.byName(as.getMode().name().toLowerCase()));
                            // Also make sure we haven't accidentally left the player flying:
                            player.getAbilities().flying = false;
                            player.sendAbilitiesUpdate();
                        }
                    }
                    this.pendingRunningAgents.remove(username);
                    // If all clients are now running, we can finally enter the running state ourselves.
                    if (this.pendingRunningAgents.isEmpty())
                        episodeHasCompleted(ServerState.RUNNING);
                }
            }
        }

        private AgentSection getAgentSectionFromAgentName(String agentname)
        {
            List<AgentSection> agents = currentMissionInit().getMission().getAgentSection();
            if (agents != null && agents.size() > 0)
            {
                for (AgentSection ascandidate : agents)
                {
                    if (ascandidate.getName().equals(agentname))
                        return ascandidate;
                }
            }
            return null;
        }

        private ServerPlayerEntity getPlayerFromUsername(String username)
        {
            // username, not account name
            return MinecraftClient.getInstance().getServer().getPlayerManager().getPlayer(username);
        }

        private void initialisePlayer(String username, String agentname)
        {
            AgentSection as = getAgentSectionFromAgentName(agentname);
            ServerPlayerEntity player = getPlayerFromUsername(username);

            if (player != null && as != null)
            {
                /*
                if ((player.getHealth() <= 0 || player.isDead() || !player.isAlive()))
                {
                    player.markPlayerActive();
                    player = FMLCommonHandler.instance().getMinecraftServerInstance().getPlayerList().recreatePlayerEntity(player, player.dimension, false);
                    player.connection.playerEntity = player;
                }

                // Reset their food and health:
                player.setHealth(player.getMaxHealth());
                player.getFoodStats().addStats(20, 40);
                player.maxHurtResistantTime = 1; // Set this to a low value so that lava will kill the player straight away.

                disablePlayerGracePeriod(player);   // Otherwise player will be invulnerable for the first 60 ticks.
                 */
                player.extinguish();	// In case the player was left burning.

                // Set their initial position and speed:
                PosAndDirection pos = as.getAgentStart().getPlacement();
                if (pos != null) {
                    player.setYaw(pos.getYaw().floatValue());
                    player.setPitch(pos.getPitch().floatValue());
                    LOGGER.info("Setting agent pos to: x(" + pos.getX() + ") z(" + pos.getZ()  + ") y(" + pos.getY() + ")");
                    player.setPosition(pos.getX().doubleValue(),pos.getY().doubleValue(),pos.getZ().doubleValue());
                }
                player.setVelocity(0, 0, 0);	// Minimise chance of drift!

                // Set their inventory:
                if (as.getAgentStart().getInventory() != null)
                    initialiseInventory(player, as.getAgentStart().getInventory());
                // And their Ender inventory:
                if (as.getAgentStart().getEnderBoxInventory() != null)
                    initialiseEnderInventory(player, as.getAgentStart().getEnderBoxInventory());
                this.setGameType(player, GameMode.SPECTATOR);
                // Set their game mode to spectator for now, to protect them while we wait for the rest of the cast to assemble:
            }
        }

        private void setGameType(ServerPlayerEntity player, GameMode mode){
            NbtCompound ntb = new NbtCompound();
            ntb.putInt("playerGameType", mode.ordinal());
            player.setGameMode(ntb);
        }

        @Override
        protected void execute()
        {
            List<AgentSection> agents = currentMissionInit().getMission().getAgentSection();
            if (agents != null && agents.size() > 0)
            {
                System.out.println("Experiment requires: ");
                for (AgentSection as : agents)
                {
                    System.out.println(">>>> " + as.getName());
                    pendingReadyAgents.add(as.getName());
                }
            }
        }

        private void resetPlayerGameTypes()
        {
            // Go through and set all the players to their correct game type:
            for (Map.Entry<String, String> entry : this.usernameToAgentnameMap.entrySet())
            {
                AgentSection as = getAgentSectionFromAgentName(entry.getValue());
                ServerPlayerEntity player = getPlayerFromUsername(entry.getKey());
                if (as != null && player != null)
                {
                    this.setGameType(player, GameMode.byName(as.getMode().name().toLowerCase()));
                    // Also make sure we haven't accidentally left the player flying:
                    player.getAbilities().flying = false;
                    player.sendAbilitiesUpdate();
                }
            }
        }

        private void onCastAssembled()
        {
            // Build up any extra mission handlers required:
            MissionBehaviour handlers = getHandlers();
            List<Object> extraHandlers = new ArrayList<Object>();
            Map<String, String> data = new HashMap<String, String>();

            if (handlers.worldDecorator != null && handlers.worldDecorator.getExtraAgentHandlersAndData(extraHandlers, data))
            {
                for (Object handler : extraHandlers)
                {
                    String xml;
                    try
                    {
                        xml = SchemaHelper.serialiseObject(handler, MissionInit.class);
                        data.put(handler.getClass().getName(), xml);
                    }
                    catch (JAXBException e)
                    {
                        // TODO - is this worth aborting the mission for?
                        System.out.println("Exception trying to describe extra handlers: " + e);
                    }
                }
            }
            // Allow the world decorators to add themselves to the turn schedule if required.
            if (handlers.worldDecorator != null)
            {
                ArrayList<String> participants = new ArrayList<String>();
                ArrayList<Integer> participantSlots = new ArrayList<Integer>();
                handlers.worldDecorator.getTurnParticipants(participants, participantSlots);
                for (int i = 0; i < Math.min(participants.size(), participantSlots.size()); i++)
                {
                    addUsernameToTurnSchedule(participants.get(i), participantSlots.get(i));
                }
            }
            // Save the turn schedule, if there is one:
            saveTurnSchedule();

            // And tell them all they can proceed:
            sendToAll(new MalmoMessage(MalmoMessageType.SERVER_ALLPLAYERSJOINED, 0, data));
        }

        @Override
        protected void onError(Map<String, String> errorData)
        {
            // Something has gone wrong - one of the clients has been forced to bail.
            // Do some tidying:
            resetPlayerGameTypes();
            // And tell all the clients to abort:
            sendToAll(new MalmoMessage(MalmoMessageType.SERVER_ABORT, 0, errorData));
            // And abort ourselves:
            episodeHasCompleted(ServerState.ERROR);
        }

        @Override
        protected void onServerTick(MinecraftServer ev)
        {
            if (!ServerStateMachine.this.checkWatchList())
                onError(null);  // We've lost a connection - abort the mission.
        }
/*
        private ItemStack itemStackFromInventoryObject(InventoryObjectType obj)
        {
            DrawItem di = new DrawItem();
            di.setColour(obj.getColour());
            di.setVariant(obj.getVariant());
            di.setType(obj.getType());
            Item.Settings settings = new Item.Settings();
            Item item = new Item();
            ItemStack item = new ItemStack(di);//MinecraftTypeHelper.getItemStackFromDrawItem(di);
            if( item != null )
            {
                item.setCount(obj.getQuantity());
            }
            return item;
        }
*/
        private void initialiseInventory(PlayerEntity player, AgentStart.Inventory inventory)
        {
            // Clear inventory:
            player.getInventory().clear();

            // Now add specified items:
            for (JAXBElement<? extends InventoryObjectType> el : inventory.getInventoryObject())
            {
                InventoryObjectType obj = el.getValue();
                ItemStack item = null;
                // ItemStack item = itemStackFromInventoryObject(obj);
                if( item != null )
                {
                    player.getInventory().setStack(obj.getSlot(), item);
                }
            }
        }

        private void initialiseEnderInventory(PlayerEntity player, AgentStart.EnderBoxInventory inventory)
        {/*
            player.getInventoryEnderChest().clear();
            for (JAXBElement<? extends InventoryObjectType> el : inventory.getInventoryObject())
            {
                InventoryObjectType obj = el.getValue();
                ItemStack item = itemStackFromInventoryObject(obj);
                if( item != null )
                {
                    player.getInventoryEnderChest().setInventorySlotContents(obj.getSlot(), item);
                }
            }*/
        }
    }


    //---------------------------------------------------------------------------------------------------------
    /** Mission running state.
     */
    public class RunningEpisode extends ErrorAwareEpisode
    {
        ArrayList<String> runningAgents = new ArrayList<String>();
        boolean missionHasEnded = false;
        long tickCount = 0;
        long secondStartTimeMs = 0;

        protected RunningEpisode(ServerStateMachine machine)
        {
            super(machine);

            // Build up list of running agents:
            List<AgentSection> agents = currentMissionInit().getMission().getAgentSection();
            if (agents != null && agents.size() > 0)
            {
                for (AgentSection as : agents)
                {
                    runningAgents.add(as.getName());
                }
            }
            // And register for the agent-finished message:
            SidesMessageHandler.client2server.registerForMessage(this, MalmoMessageType.CLIENT_AGENTFINISHEDMISSION);
            SidesMessageHandler.client2server.registerForMessage(this, MalmoMessageType.CLIENT_SHARE_REWARD);
            SidesMessageHandler.client2server.registerForMessage(this, MalmoMessageType.CLIENT_TURN_TAKEN);
        }

        @Override
        public void cleanup()
        {
            super.cleanup();
            SidesMessageHandler.client2server.deregisterForMessage(this, MalmoMessageType.CLIENT_AGENTFINISHEDMISSION);
            SidesMessageHandler.client2server.deregisterForMessage(this, MalmoMessageType.CLIENT_SHARE_REWARD);
            SidesMessageHandler.client2server.deregisterForMessage(this, MalmoMessageType.CLIENT_TURN_TAKEN);
        }

        @Override
        public void onMessage(MalmoMessageType messageType, Map<String, String> data, ServerPlayerEntity player)
        {
            super.onMessage(messageType, data, player);
            if (messageType == MalmoMessageType.CLIENT_AGENTFINISHEDMISSION)
            {
                // this agentname is legacy from old malmo code
                // we can use player object passed from messaging code
                String agentName = data.get("agentname");
                if (agentName != null)
                {
                    this.runningAgents.remove(agentName);
                    LOGGER.info("Removed agent " + agentName);
                    LOGGER.info("agents left" + this.runningAgents.toString());
                    // If this agent is part of a turn-based scenario, it no longer needs
                    // to take its turn - we must remove it from the schedule or everything
                    // else will stall waiting for it.
                    ServerStateMachine.this.removeFromTurnSchedule(agentName);
                }
            }
            else if (messageType == MalmoMessageType.CLIENT_SHARE_REWARD)
            {
                ServerStateMachine.this.sendToAll(new MalmoMessage(MalmoMessageType.SERVER_SHARE_REWARD, 0, data));
            }
            else if (messageType == MalmoMessageType.CLIENT_TURN_TAKEN)
            {
                String agentName = data.get("agentname");
                //String userName = data.get("username");
                String nextAgentName = ServerStateMachine.this.getNextAgentInTurnSchedule(agentName);
                if (nextAgentName == null)
                {
                    // Couldn't find the next agent in the turn schedule. Abort!
                    String error = "ERROR IN TURN SCHEDULER - cannot find the successor to " +  agentName;
                    saveErrorDetails(error);
                    System.out.println(error);
                    ServerStateMachine.this.sendToAll(new MalmoMessage(MalmoMessageType.SERVER_ABORT, 0, data));
                    episodeHasCompleted(ServerState.ERROR);
                }
                else
                {
                    // Find the relevant agent; send a message to it.
                    if (player != null)
                    {
                        MalmoMessage msg = new MalmoMessage(MalmoMessageType.SERVER_YOUR_TURN, 0, null);
                        ServerPlayNetworking.send(player, NetworkConstants.SERVER2CLIENT, msg.toBytes());
                    }
                    else if (getHandlers().worldDecorator != null)
                    {
                        // Not a player - is it a world decorator?
                        boolean handled = getHandlers().worldDecorator.targetedUpdate(nextAgentName);
                        if (!handled)
                        {
                            // Couldn't reach the client whose turn it is, and doesn't seem to be a decorator's turn - abort!
                            String error = "ERROR IN TURN SCHEDULER - could not find client for user " + nextAgentName;
                            saveErrorDetails(error);
                            System.out.println(error);
                            ServerStateMachine.this.sendToAll(new MalmoMessage(MalmoMessageType.SERVER_ABORT,
                                    0, null));
                            episodeHasCompleted(ServerState.ERROR);
                        }
                    }
                }
            }
        }

        @Override
        protected void execute()
        {
            // Set up some initial conditions:
            ServerSection ss = currentMissionInit().getMission().getServerSection();
            ServerInitialConditions sic = (ss != null) ? ss.getServerInitialConditions() : null;
            if (sic != null && sic.getTime() != null)
            {
                boolean allowTimeToPass = (sic.getTime().isAllowPassageOfTime() != Boolean.FALSE);  // Defaults to true if unspecified.
                MinecraftServer server = MinecraftClient.getInstance().getServer();
                for (ServerWorld world: server.getWorlds())
                {
                    world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(allowTimeToPass ? true : false, null);
                    if (sic.getTime().getStartTime() != null)
                        world.setTimeOfDay(sic.getTime().getStartTime());
                }
            }
            ModSettings modsettings = currentMissionInit().getMission().getModSettings();
            /*if (modsettings != null && modsettings.getMsPerTick() != null)
                TimeHelper.serverTickLength = (long)(modsettings.getMsPerTick());
                */

            if (getHandlers().quitProducer != null)
                getHandlers().quitProducer.prepare(currentMissionInit());

            if (getHandlers().worldDecorator != null)
                getHandlers().worldDecorator.prepare(currentMissionInit());

            // Fire the starting pistol:
            ServerStateMachine.this.sendToAll(new MalmoMessage(MalmoMessageType.SERVER_GO, 0, null));
            // And start the turn schedule turning, if there is one:
            if (!ServerStateMachine.this.userTurnSchedule.isEmpty())
            {
                String agentName = ServerStateMachine.this.userTurnSchedule.get(0);
                ServerPlayerEntity player = MinecraftClient.getInstance().getServer().getPlayerManager().getPlayer(agentName);
                if (player != null)
                {
                    ServerPlayNetworking.send(player, NetworkConstants.SERVER2CLIENT,
                            new MalmoMessage(MalmoMessageType.SERVER_YOUR_TURN, 0, null).toBytes());
                }
                else if (getHandlers().worldDecorator != null)
                {
                    // Not a player - is it a world decorator?
                    getHandlers().worldDecorator.targetedUpdate(agentName);
                }
            }
        }

        @Override
        protected void onServerTickStart(MinecraftServer ev) {
            if (this.missionHasEnded)
                return;    // In case we get in here after deciding the mission is over.

            if (!ServerStateMachine.this.checkWatchList())
                onError(null);  // We've lost a connection - abort the mission.


            // Measure our performance - especially useful if we've been overclocked.
            if (this.secondStartTimeMs == 0)
                this.secondStartTimeMs = System.currentTimeMillis();

            long timeNow = System.currentTimeMillis();
            if (timeNow - this.secondStartTimeMs > 1000) {
                long targetTicks = 1000 / TimeHelper.serverTickLength;
                if (this.tickCount < targetTicks)
                    System.out.println("Warning: managed " + this.tickCount + "/" + targetTicks + " ticks this second.");
                this.secondStartTimeMs = timeNow;
                this.tickCount = 0;
            }
            this.tickCount++;
        }

        @Override
        protected void onServerTick(MinecraftServer ev) {
            if (!ServerStateMachine.this.checkWatchList())
                onError(null);  // We've lost a connection - abort the mission.

            if (getHandlers() != null && getHandlers().worldDecorator != null)
            {
                for(World world: server.getWorlds()) {
                    getHandlers().worldDecorator.update(world);
                }
            }

            if (getHandlers() != null && getHandlers().quitProducer != null && getHandlers().quitProducer.doIWantToQuit(currentMissionInit()))
            {
                ServerStateMachine.this.quitCode = getHandlers().quitProducer.getOutcome();
                onMissionEnded(true);
            }
            else if (this.runningAgents.isEmpty())
            {
                LOGGER.info("ALL agents finished");
                ServerStateMachine.this.quitCode = "All agents finished";
                onMissionEnded(true);
            }
            // We need to make sure we keep the weather within mission parameters.
            // We set the weather just after building the world, but it's not a permanent setting,
            // and apparently there is a known bug in Minecraft that means the weather sometimes changes early.
            // To get around this, we reset it periodically.
            if (server.getTicks() % 500 == 0)
            {
                for(ServerWorld world: ev.getWorlds()) {
                    setMissionWeather(currentMissionInit(), world);
                }
            }
        }

        private void onMissionEnded(boolean success)
        {
            this.missionHasEnded = true;

            if (getHandlers().quitProducer != null)
                getHandlers().quitProducer.cleanup();

            if (getHandlers().worldDecorator != null)
                getHandlers().worldDecorator.cleanup();

            TimeHelper.serverTickLength = 50;   // Return tick length to 50ms default.

            if (success)
            {
                // Mission is over - wait for all agents to stop.
                episodeHasCompleted(ServerState.WAITING_FOR_AGENTS_TO_QUIT);
            }
        }

        @Override
        protected void onError(Map<String, String> errorData)
        {
            // Something has gone wrong - one of the clients has been forced to bail.
            // Do some tidying:
            onMissionEnded(false);
            // Tell all the clients to abort:
            ServerStateMachine.this.sendToAll(new MalmoMessage(MalmoMessageType.SERVER_ABORT, 0, null));
            // And abort ourselves:
            episodeHasCompleted(ServerState.ERROR);
        }
    }

    //---------------------------------------------------------------------------------------------------------
    /** Wait for all agents to stop running and get themselves into a ready state.*/
    public class WaitingForAgentsToQuitEpisode extends ErrorAwareEpisode implements IMalmoMessageListener
    {
        private HashMap<String, Boolean> agentsStopped = new HashMap<String, Boolean>();

        protected WaitingForAgentsToQuitEpisode(ServerStateMachine machine)
        {
            super(machine);
            SidesMessageHandler.client2server.registerForMessage(this, MalmoMessageType.CLIENT_AGENTSTOPPED);
        }

        @Override
        protected void execute()
        {
            // Get ready to track agent responses:
            List<AgentSection> agents = currentMissionInit().getMission().getAgentSection();
            for (AgentSection as : agents)
                this.agentsStopped.put(as.getName(), false);

            // Now tell all the agents to stop what they are doing:
            Map<String, String>data = new HashMap<String, String>();
            data.put("QuitCode", ServerStateMachine.this.quitCode);
            ServerStateMachine.this.sendToAll(new MalmoMessage(MalmoMessageType.SERVER_STOPAGENTS, 0, data));
        }

        @Override
        public void onMessage(MalmoMessageType messageType, Map<String, String> data) {
            throw new RuntimeException("Unexpected message to client");
        }

        @Override
        public void onMessage(MalmoMessageType messageType, Map<String, String> data, ServerPlayerEntity player)
        {
            super.onMessage(messageType, data, player);
            if (messageType == MalmoMessageType.CLIENT_AGENTSTOPPED)
            {
                String name = data.get("agentname");
                this.agentsStopped.put(name, true);
                if (!this.agentsStopped.containsValue(false))
                {
                    // Agents are all finished and awaiting our message.
                    ServerStateMachine.this.sendToAll(new MalmoMessage(MalmoMessageType.SERVER_MISSIONOVER, 0, null));
                    episodeHasCompleted(ServerState.CLEAN_UP);
                }
            }
        }

        @Override
        public void cleanup()
        {
            super.cleanup();
            SidesMessageHandler.client2server.deregisterForMessage(this, MalmoMessageType.CLIENT_AGENTSTOPPED);
        }

        @Override
        protected void onServerTick(MinecraftServer ev)
        {
            if (!ServerStateMachine.this.checkWatchList())
            {
                // Something has gone wrong - we've lost a connection.
                // Need to respond to this, otherwise we'll sit here forever waiting for a client that no longer exists
                // to tell us it's finished its mission.
                ServerStateMachine.this.sendToAll(new MalmoMessage(MalmoMessageType.SERVER_ABORT, 0, null));
                episodeHasCompleted(ServerState.ERROR);
            }
        }
    }

    //---------------------------------------------------------------------------------------------------------
    /** Generic error state */
    public class ErrorEpisode extends StateEpisode {
        public ErrorEpisode(StateMachine machine) {
            super(machine);
        }

        @Override
        protected void execute() {
            //TODO - tidy up.
            episodeHasCompleted(ServerState.CLEAN_UP);
        }
    }

    //---------------------------------------------------------------------------------------------------------
    public class CleanUpEpisode extends StateEpisode
    {
        public CleanUpEpisode(StateMachine machine)
        {
            super(machine);
        }
        @Override
        protected void execute()
        {
            // Put in all cleanup code here.
            // ServerStateMachine.this.currentMissionInit = null;
            episodeHasCompleted(ServerState.DORMANT);
        }
    }
}
