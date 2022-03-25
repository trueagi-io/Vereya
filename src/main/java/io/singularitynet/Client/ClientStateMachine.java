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

import com.google.gson.JsonObject;
import io.singularitynet.*;
import io.singularitynet.MissionHandlerInterfaces.IVideoProducer;
import io.singularitynet.MissionHandlerInterfaces.IWantToQuit;
import io.singularitynet.MissionHandlers.MissionBehaviour;
import io.singularitynet.MissionHandlers.MultidimensionalReward;
import io.singularitynet.Server.VereyaModServer;
import io.singularitynet.mixin.SessionMixin;
import io.singularitynet.projectmalmo.*;
import io.singularitynet.utils.*;
import io.singularitynet.utils.TCPInputPoller.CommandAndIPAddress;
import jakarta.xml.bind.JAXBException;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.xml.sax.SAXException;

import javax.xml.stream.XMLStreamException;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.logging.Level;

/**
 * Class designed to track and control the state of the mod, especially regarding mission launching/running.<br>
 * States are defined by the MissionState enum, and control is handled by
 * MissionStateEpisode subclasses. The ability to set the state directly is
 * restricted, but hooks such as onPlayerReadyForMission etc are exposed to
 * allow subclasses to react to certain state changes.<br>
 * The ProjectMalmo mod app class inherits from this and uses these hooks to run missions.
 */
public class ClientStateMachine extends StateMachine implements IMalmoMessageListener
{
    private static final int WAIT_MAX_TICKS = 3000; // Over 3 minute and a half in client ticks.
    private static final int VIDEO_MAX_WAIT = 90 * 1000; // Max wait for video in ms.
    private static final String MISSING_MCP_PORT_ERROR = "no_mcp";
    private static final String INFO_MCP_PORT = "info_mcp";
    private static final String INFO_RESERVE_STATUS = "info_reservation";
    // Directly reference a log4j logger.
    private static final Logger LOGGER = LogManager.getLogger();
    private MissionInit currentMissionInit = null; // The MissionInit object for the mission currently being loaded/run.
    private MissionBehaviour missionBehaviour = null;// new MissionBehaviour();
    private MissionBehaviour serverHandlers = null;
    private String missionQuitCode = ""; // The reason why this mission ended.
    Map<RegistryKey<World>, Object> generatorProperties = new HashMap<>();

    private MissionDiagnostics missionEndedData = new MissionDiagnostics();
    private IScreenHelper screenHelper = new ScreenHelper();
    protected IMalmoModClient inputController;
    private static String mod_version = "- 21";
    static {
    	Properties properties = new Properties();
        try {
			properties.load(ClientStateMachine.class.getClassLoader().getResourceAsStream("version.properties"));
	        mod_version = properties.getProperty("version");
        } catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			LOGGER.error("exception reading property file");
		}

    }
    
    // Env service:
    // protected MalmoEnvServer envServer;

    // Socket stuff:
    protected TCPInputPoller missionPoller;
    protected TCPInputPoller controlInputPoller;
    protected int integratedServerPort;
    String reservationID = "";   // empty if we are not reserved, otherwise "RESERVED" + the experiment ID we are reserved for.
    long reservationExpirationTime = 0;
    private TCPSocketChannel missionControlSocket;
    private MultidimensionalReward finalReward = new MultidimensionalReward(true); // The reward at the end of the mission, sent separately to ensure timely delivery.

    private void reserveClient(String id)
    {
        synchronized(this.reservationID)
        {
            //ClientStateMachine.this.getScreenHelper().clearFragment(INFO_RESERVE_STATUS);
        	System.out.println("reserving " + id);
            // id is in the form <long>:<expID>, where long is the length of time to keep the reservation for,
            // and expID is the experimentationID used to ensure the client is reserved for the correct experiment.
            int separator = id.indexOf(":");
            if (separator == -1)
            {
                System.out.println("Error - malformed reservation request - client will not be reserved.");
                this.reservationID = "";
            }
            else
            {
                long duration = Long.valueOf(id.substring(0, separator));
                String expID = id.substring(separator + 1);
                this.reservationExpirationTime = System.currentTimeMillis() + duration;
                // We don't just use the id, in case users have supplied a blank string as their experiment ID.
                this.reservationID = "RESERVED" + expID;
                //ClientStateMachine.this.getScreenHelper().addFragment("Reserved: " + expID, TextCategory.TXT_INFO,  (Integer.valueOf((int)duration)).toString());//INFO_RESERVE_STATUS);
            }
        }
    }

    private boolean isReserved()
    {
        synchronized(this.reservationID)
        {
            System.out.println("==== RES: " + this.reservationID + " - " + (this.reservationExpirationTime - System.currentTimeMillis()));
            return !this.reservationID.isEmpty() && this.reservationExpirationTime > System.currentTimeMillis();
        }
    }

    private boolean isAvailable(String id)
    {
        synchronized(this.reservationID)
        {
            return (this.reservationID.isEmpty() || this.reservationID.equals("RESERVED" + id) || System.currentTimeMillis() >= this.reservationExpirationTime);
        }
    }

    private void cancelReservation()
    {
        synchronized(this.reservationID)
        {
            this.reservationID = "";
            // ClientStateMachine.this.getScreenHelper().clearFragment(INFO_RESERVE_STATUS);
        }            
    }

    protected TCPSocketChannel getMissionControlSocket() { return this.missionControlSocket; }
    
    protected void createMissionControlSocket()
    {
        TCPUtils.LogSection ls = new TCPUtils.LogSection("Creating MissionControlSocket");
        // Set up a TCP connection to the agent:
        ClientAgentConnection cac = currentMissionInit().getClientAgentConnection();
        if (this.missionControlSocket == null ||
            this.missionControlSocket.getPort() != cac.getAgentMissionControlPort() ||
            this.missionControlSocket.getAddress() == null ||
            !this.missionControlSocket.isValid() ||
            !this.missionControlSocket.isOpen() ||
            !this.missionControlSocket.getAddress().equals(cac.getAgentIPAddress()))
        {
            if (this.missionControlSocket != null)
                this.missionControlSocket.close();
            this.missionControlSocket = new TCPSocketChannel(cac.getAgentIPAddress(), cac.getAgentMissionControlPort(), "mcp");
        }
        ls.close();
    }

    public ClientStateMachine(ClientState initialState, IMalmoModClient malmoModClient)
    {
        super(initialState);
        this.inputController = malmoModClient;

        // Register ourself on the event busses, so we can harness the client tick:
        ClientTickEvents.END_CLIENT_TICK.register(client -> this.onClientTick(client));
        SidesMessageHandler.server2client.registerForMessage(this, MalmoMessageType.SERVER_TEXT);
    }

    @Override
    public void clearErrorDetails()
    {
        super.clearErrorDetails();
        this.missionQuitCode = "";
    }

    public void onClientTick(MinecraftClient ev)
    {
        // Use the client tick to ensure we regularly update our state (from the client thread)
        updateState();
    }

    public IScreenHelper getScreenHelper()
    {
        return screenHelper;
    }

    @Override
    public void onMessage(MalmoMessageType messageType, Map<String, String> data)
    {
        LOGGER.info("got message type " + messageType.toString());
        if (messageType == MalmoMessageType.SERVER_TEXT)
        {
        	LOGGER.info("got server text message" + data.toString());
        }
    }

    @Override
    protected String getName()
    {
        return "CLIENT";
    }

    @Override
    protected void onPreStateChange(IState toState)
    {
        // this.getScreenHelper().addFragment("CLIENT: " + toState, ScreenHelper.TextCategory.TXT_CLIENT_STATE, "");
    }

    /**
     * Create the episode object for the requested state.
     * 
     * @param state the state the mod is entering
     * @return a MissionStateEpisode that localises all the logic required to run this state
     */
    @Override
    protected StateEpisode getStateEpisodeForState(IState state)
    {
        if (!(state instanceof ClientState))
            return null;
        ClientState cs = (ClientState) state;
        switch (cs) {
        	case WAITING_FOR_MOD_READY:
                return new InitialiseClientModEpisode(this);
        	case DORMANT:
        		return new DormantEpisode(this);
            case CREATING_HANDLERS:
                return new CreateHandlersEpisode(this);
            case CREATING_NEW_WORLD:
                return new CreateWorldEpisode(this);
            case EVALUATING_WORLD_REQUIREMENTS:
                return new EvaluateWorldRequirementsEpisode(this);
            case WAITING_FOR_SERVER_READY:
                return new WaitingForServerEpisode(this);
            case RUNNING:
                return new MissionRunningEpisode(this);
            case PAUSING_OLD_SERVER:
                return new PauseOldServerEpisode(this);
            case CLOSING_OLD_SERVER:
                return new CloseOldServerEpisode(this);
            case MISSION_ABORTED:
                return new MissionEndedEpisode(this, MissionResult.MOD_SERVER_ABORTED_MISSION, true, false, true);  // Don't inform the server - it already knows (we're acting on its notification)
            case WAITING_FOR_SERVER_MISSION_END:
                return new WaitingForServerMissionEndEpisode(this);
            case IDLING:
                return new MissionIdlingEpisode(this);
            case MISSION_ENDED:
                return new MissionEndedEpisode(this, MissionResult.ENDED, false, false, true);
            case ERROR_LOST_AGENT:
            case ERROR_LOST_VIDEO:
                return new MissionEndedEpisode(this, MissionResult.MOD_HAS_NO_AGENT_AVAILABLE, true, true, false);
            case ERROR_CANNOT_CREATE_WORLD:
                return new MissionEndedEpisode(this, MissionResult.MOD_FAILED_TO_CREATE_WORLD, true, true, true);
            default:
                break;
        }
        LOGGER.info("got state " + cs.toString());
        return null;
    }

    protected MissionInit currentMissionInit()
    {
        return this.currentMissionInit;
    }

    public MissionBehaviour currentMissionBehaviour()
    {
        return this.missionBehaviour;
    }

    protected class MissionInitResult
    {
        public MissionInit missionInit = null;
        public boolean wasMissionInit = false;
        public String error = null;
    }

    protected MissionInitResult decodeMissionInit(String command)
    {
        MissionInitResult result = new MissionInitResult();
        if (command == null)
        {
            result.error = "Null command passed.";
            return result;
        }

        String rootNodeName = SchemaHelper.getRootNodeName(command);
        if (rootNodeName != null && rootNodeName.equals("MissionInit"))
        {
            result.wasMissionInit = true;
            // Attempt to decode the MissionInit XML string.
            try
            {
                result.missionInit = (MissionInit) SchemaHelper.deserialiseObject(command, MissionInit.class);
            }
            catch (NullPointerException e){
                LOGGER.error("exception parsing xml", e);
            }
            catch (JAXBException e)
            {
                System.out.println("JAXB exception: " + e);
                e.printStackTrace(System.out);
                if (e.getMessage() != null)
                    result.error = e.getMessage();
                else if (e.getLinkedException() != null && e.getLinkedException().getMessage() != null)
                    result.error = e.getLinkedException().getMessage();
                else
                    result.error = "Unspecified problem parsing MissionInit - check your Mission xml.";
                LOGGER.error("exception parsing xml", e);
            }
            catch (SAXException e)
            {
                System.out.println("SAX exception: " + e);
                result.error = e.getMessage();
            }
            catch (XMLStreamException e)
            {
                System.out.println("XMLStreamException: " + e);
                result.error = e.getMessage();
            }
        }
        return result;
    }

    protected boolean areMissionsEqual(Mission m1, Mission m2)
    {
        return true;
        // FIX NEEDED - the following code fails because m1 may have been
        // modified since loading - eg the MazeDecorator writes directly to the XML,
        // and the use of some of the getters in the XSD-generated code can cause extra
        // (empty) nodes to be added to the resulting XML.
        // We need a more robust way of comparing two mission objects.
        // For now, simply return true, since a false positive is less dangerous
        // than a false negative.
        /*
        try {
            String s1 = SchemaHelper.serialiseObject(m1, Mission.class);
            String s2 = SchemaHelper.serialiseObject(m2, Mission.class);
            return s1.compareTo(s2) == 0;
        } catch( JAXBException e ) {
            System.out.println("JAXB exception: " + e);
            return false;
        }*/
    }

    /**
     * Set up the mission poller.<br>
     * This is called during the initialisation episode, but also needs to be
     * available for other episodes in case the configuration changes, resulting
     * in changes to the ports.
     * 
     * @throws UnknownHostException
     */
    protected void initialiseComms() throws UnknownHostException
    {
        // Start polling for missions:
        if (this.missionPoller != null)
        {
            this.missionPoller.stopServer();
        }

        this.missionPoller = new TCPInputPoller(AddressHelper.getMissionControlPortOverride(), AddressHelper.MIN_MISSION_CONTROL_PORT, AddressHelper.MAX_FREE_PORT, true, "mcp")
        {
            @Override
            public void onError(String error, DataOutputStream dos)
            {
                System.out.println("SENDING ERROR: " + error);
                try
                {
                    dos.writeInt(error.length());
                    dos.writeBytes(error);
                    dos.flush();
                }
                catch (IOException e)
                {
                }
            }

            private void reply(String reply, DataOutputStream dos)
            {
                System.out.println("REPLYING WITH: " + reply);
                try
                {
                    dos.writeInt(reply.length());
                    dos.writeBytes(reply);
                    dos.flush();
                }
                catch (IOException e)
                {
                    System.out.println("Failed to reply to message!");
                }
            }

            @Override
            public boolean onCommand(String command, String ipFrom, DataOutputStream dos)
            {
                LOGGER.info("Received from " + ipFrom + ":" +
                                    command.substring(0, Math.min(command.length(), 1024)));
                boolean keepProcessing = false;

                // Possible commands:
                // 1: MALMO_REQUEST_CLIENT:<malmo version>:<reservation_length(ms)><experiment_id>
                // 2: MALMO_CANCEL_REQUEST
                // 3: MALMO_FIND_SERVER<experiment_id>
                // 4: MALMO_KILL_CLIENT
                // 5: MissionInit

                String reservePrefixGeneral = "MALMO_REQUEST_CLIENT:";
                String reservePrefix = reservePrefixGeneral + mod_version + ":";
                String findServerPrefix = "MALMO_FIND_SERVER";
                String cancelRequestCommand = "MALMO_CANCEL_REQUEST";
                String killClientCommand = "MALMO_KILL_CLIENT";
                
                if (command.startsWith(reservePrefix))
                {
                    // Reservation request.
                    // We either reply with MALMOOK, if we are free, or MALMOBUSY if not.
                    IState currentState = getStableState();
                    if (currentState != null && currentState.equals(ClientState.DORMANT) && !isReserved())
                    {
                        reserveClient(command.substring(reservePrefix.length()));
                        reply("MALMOOK", dos);
                    }
                    else
                    {
                        // We're busy - we can't be reserved.
                        reply("MALMOBUSY", dos);
                    }
                }
                else if (command.startsWith(reservePrefixGeneral))
                {
                    // Reservation request, but it didn't match the request we expect, above.
                    // This happens if the agent sending the request is running a different version of Malmo -
                    // a version mismatch error.
                    reply("MALMOERRORVERSIONMISMATCH in reservation string (Got " + command + ", expected " + reservePrefix + " - check your path for old versions of MalmoPython/MalmoJava/Malmo.lib etc)", dos);
                }
                else if (command.equals(cancelRequestCommand))
                {
                    // If we've been reserved, cancel the reservation.
                    if (isReserved())
                    {
                        cancelReservation();
                        reply("MALMOOK", dos);
                    }
                    else
                    {
                        // We weren't reserved in the first place - something is odd.
                        reply("MALMOERRORAttempt to cancel a reservation that was never made.", dos);
                    }
                }
                else if (command.startsWith(findServerPrefix))
                {
                    // Request to find the server for the given experiment ID.
                    String expID = command.substring(findServerPrefix.length());
                    if (currentMissionInit() != null && currentMissionInit().getExperimentUID().equals(expID))
                    {
                        // Our Experiment IDs match, so we are running the same experiment.
                        // Return the port and server IP address to the caller:
                        MinecraftServerConnection msc = currentMissionInit().getMinecraftServerConnection();
                        if (msc == null)
                            reply("MALMONOSERVERYET", dos); // Mission might be starting up.
                        else
                            reply("MALMOS" + msc.getAddress().trim() + ":" + msc.getPort(), dos);
                    }
                    else
                    {
                        // We don't have a MissionInit ourselves, or we're running a different experiment,
                        // so we can't help.
                        reply("MALMONOSERVER", dos);
                    }
                }
                else if (command.equals(killClientCommand))
                {
                    // Kill switch provided in case AI takes over the world...
                    // Or, more likely, in case this Minecraft instance has become unreliable (eg if it's been running for several days)
                    // and needs to be replaced with a fresh instance.
                    // If we are currently running a mission, we gracefully decline, to prevent users from wiping out
                    // other users' experiments.
                    // We also decline unless we were launched in "replaceable" mode - a command-line switch that indicates we were
                    // launched by a script which is still running, and can therefore replace us when we terminate.
                    IState currentState = getStableState();
                    if (currentState != null && currentState.equals(ClientState.DORMANT) && !isReserved())
                    {
                        if (true)
                        {
                            reply("MALMOOK", dos);

                            missionPoller.stopServer();
                            exitJava();
                        }
                        else
                        {
                            reply("MALMOERRORNOTKILLABLE", dos);
                        }
                    }
                    else
                    {
                        // We're too busy and important to be killed.
                        reply("MALMOBUSY", dos);
                    }
                }
                else
                {
                    // See if we've been sent a MissionInit message:

                    MissionInitResult missionInitResult = decodeMissionInit(command);
                    LOGGER.info("decoded mission init " + missionInitResult.toString());
            

                    if (missionInitResult.wasMissionInit && missionInitResult.missionInit == null)
                    {
                        // Got sent a duff MissionInit xml - pass back the JAXB/SAXB errors.
                        reply("MALMOERROR" + missionInitResult.error, dos);
                    }
                    else if (missionInitResult.wasMissionInit && missionInitResult.missionInit != null)
                    {
                        MissionInit missionInit = missionInitResult.missionInit;
                        // We've been sent a MissionInit message.
                        // First, check the version number:
                        String platformVersion = missionInit.getPlatformVersion();
                        String ourVersion = mod_version;
                        if (platformVersion == null || !platformVersion.equals(ourVersion))
                        {
                            reply("MALMOERRORVERSIONMISMATCH (Got " + platformVersion + ", expected " + ourVersion + " - check your path for old versions of MalmoPython/MalmoJava/Malmo.lib etc)", dos);
                        }
                        else
                        {
                            // MissionInit passed to us - this is a request to launch this mission. Can we?
                            IState currentState = getStableState();
                            if (currentState != null && currentState.equals(ClientState.DORMANT) && isAvailable(missionInit.getExperimentUID()))
                            {
                                reply("MALMOOK", dos);
                                keepProcessing = true; // State machine will now process this MissionInit and start the mission.
                            }
                            else
                            {
                                // We're busy - we can't run this mission.
                                reply("MALMOBUSY", dos);
                            }
                        }
                    }
                }

                return keepProcessing;
            }
        };

        int mcPort = 0;

        // "Legacy" AgentHost api.
        this.missionPoller.start();
        mcPort = ClientStateMachine.this.missionPoller.getPortBlocking();
        

        // Tell the address helper what the actual port is:
        AddressHelper.setMissionControlPort(mcPort);
        if (AddressHelper.getMissionControlPort() == -1)
        {
            // Failed to create a mission control port - nothing will work!
            System.out.println("**** NO MISSION CONTROL SOCKET CREATED - WAS THE PORT IN USE? (Check Mod GUI options) ****");
            //ClientStateMachine.this.getScreenHelper().addFragment("ERROR: Could not open a Mission Control Port - check the Mod GUI options.", TextCategory.TXT_CLIENT_WARNING, MISSING_MCP_PORT_ERROR);
        }
        else
        {
            // Clear the error string, if there was one:
            // ClientStateMachine.this.getScreenHelper().clearFragment(MISSING_MCP_PORT_ERROR);
        }
        // Display the port number:
        // ClientStateMachine.this.getScreenHelper().clearFragment(INFO_MCP_PORT);
        if (AddressHelper.getMissionControlPort() != -1)
        	System.out.println("MCP: " + AddressHelper.getMissionControlPort());
        //ClientStateMachine.this.getScreenHelper().addFragment("MCP: " + AddressHelper.getMissionControlPort(), TextCategory.TXT_INFO, INFO_MCP_PORT);
    }

    public static void exitJava() {
        // Give non-hard exit 10 seconds to complete and force a hard exit.
        Thread deadMansHandle = new Thread(new Runnable() {
            @Override
            public void run() {
                MinecraftClient.getInstance().getServer().stop(true);
                MinecraftClient.getInstance().stop();
                for (int i = 10; i > 0; i--) {
                    try {
                        Thread.sleep(1000);
                        System.out.println("Waiting to exit " + i + "...");
                    } catch (InterruptedException e) {
                        System.out.println("Interrupted " + i + "...");
                    }
                }

                // Kill it with fire!!!
                System.out.println("giving up exit");
               // FMLCommonHandler.instance().exitJava(0, true);
            }
        });

        deadMansHandle.setDaemon(true);
        deadMansHandle.start();

        // Have to use FMLCommonHandler; direct calls to System.exit() are trapped and denied by the FML code.
       // FMLCommonHandler.instance().exitJava(0, false);
    }
    
    /** Initial episode - perform client setup */
    public class InitialiseClientModEpisode extends StateEpisode
    {
        InitialiseClientModEpisode(ClientStateMachine machine)
        {
            super(machine);
        }

        @Override
        protected void execute() throws Exception
        {
            ClientStateMachine.this.initialiseComms();

            // This is necessary in order to allow user to exit the Minecraft window without halting the experiment:
            // Options settings = Minecraft.getInstance().options;
            // settings.pauseOnLostFocus = false;
            // And hook the screen helper into the ingame gui (which is responsible for overlaying chat, titles etc) -
            // this has to be done after Minecraft.init(), so we do it here.
            //ScreenHelper.hookIntoInGameGui();
        }

        @Override
        public void onTitleScreen()
        {
            // We wait until we start to get render ticks, at which point we assume Minecraft has finished starting up.
            episodeHasCompleted(ClientState.DORMANT);
        }
    }
    
    /** Dormant state - receptive to new missions */
    public class DormantEpisode extends StateEpisode
    {
        private ClientStateMachine csMachine;

        protected DormantEpisode(ClientStateMachine machine)
        {
            super(machine);
            this.csMachine = machine;
        }

        @Override
        protected void execute()
        {
            // TextureHelper.init();

            // Clear our current MissionInit state:
            csMachine.currentMissionInit = null;
            // Clear our current error state:
            clearErrorDetails();
            // And clear out any stale commands left over from recent missions:
            if (ClientStateMachine.this.controlInputPoller != null)
                ClientStateMachine.this.controlInputPoller.clearCommands();
            // Finally, do some Java housekeeping:
            System.gc();
        }

        @Override
        public void onClientTick(MinecraftClient ev) throws Exception
        {
            checkForMissionCommand();
        }

        private void checkForMissionCommand() throws Exception
        {
            // Minecraft.getInstance().mcProfiler.endStartSection("malmoHandleMissionCommands");
            if (ClientStateMachine.this.missionPoller == null) {
            	LOGGER.warn("mission poller is null");
                return;
            }

            CommandAndIPAddress comip = missionPoller.getCommandAndIPAddress();
            if (comip == null)
                return;
            String missionMessage = comip.command;
            if (missionMessage == null || missionMessage.length() == 0)
                return;

            // Minecraft.getInstance().mcProfiler.startSection("malmoDecodeMissionInit");

            MissionInitResult missionInitResult = decodeMissionInit(missionMessage);
            // Minecraft.getInstance().mcProfiler.endSection();

            MissionInit missionInit = missionInitResult.missionInit;
            if (missionInit != null)
            {
                missionInit.getClientAgentConnection().setAgentIPAddress(comip.ipAddress);
                System.out.println("Mission received: " + missionInit.getMission().getAbout().getSummary());
                csMachine.currentMissionInit = missionInit;

               // ScoreHelper.logMissionInit(missionInit);

                ClientStateMachine.this.createMissionControlSocket();
                // Move on to next state:
                episodeHasCompleted(ClientState.CREATING_HANDLERS);
            }
            else
            {
                throw new Exception("Failed to get valid MissionInit object from SchemaHelper.");
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Episode helpers - each extends a MissionStateEpisode to encapsulate a certain state
    // ---------------------------------------------------------------------------------------------------------

    public abstract class ErrorAwareEpisode extends StateEpisode implements IMalmoMessageListener
    {
        protected Boolean errorFlag = false;
        protected Map<String, String> errorData = null;

        public ErrorAwareEpisode(ClientStateMachine machine)
        {
            super(machine);
            // MalmoMod.MalmoMessageHandler.registerForMessage(this, MalmoMessageType.SERVER_ABORT);
        }

        protected boolean pingAgent(boolean abortIfFailed)
        {
            if (AddressHelper.getMissionControlPort() == 0) {
                // MalmoEnvServer has no server to client ping.
                return true;
            }

            boolean sentOkay = ClientStateMachine.this.getMissionControlSocket().sendTCPString("<?xml version=\"1.0\" encoding=\"UTF-8\"?><ping/>", 1);
            if (!sentOkay)
            {
                // It's not available - bail.
                ClientStateMachine.this.getScreenHelper().addFragment("ERROR: Lost contact with agent - aborting mission",
                        ScreenHelper.TextCategory.TXT_CLIENT_WARNING,
                        10000);
                if (abortIfFailed)
                    episodeHasCompletedWithErrors(ClientState.ERROR_LOST_AGENT, "Lost contact with the agent");
            }
            return sentOkay;
        }

        @Override
        public void onMessage(MalmoMessageType messageType, Map<String, String> data)
        {
            if (messageType == MalmoMessageType.SERVER_ABORT)
            {
                synchronized (this)
                {
                    this.errorFlag = true;
                    this.errorData = data;
                    // Save the error message, if there is one:
                    if (data != null)
                    {
                        String message = data.get("message");
                        String user = data.get("username");
                        String error = data.get("error");
                        String report = "";
                        if (user != null)
                            report += "From " + user + ": ";
                        if (error != null)
                            report += error;
                        if (message != null)
                            report += " (" + message + ")";
                        ClientStateMachine.this.saveErrorDetails(report);
                    }
                    onAbort(data);
                }
            }
        }

        @Override
        public void cleanup()
        {
            super.cleanup();
            // MalmoMod.MalmoMessageHandler.deregisterForMessage(this, MalmoMessageType.SERVER_ABORT);
        }

        protected boolean inAbortState()
        {
            synchronized (this)
            {
                return this.errorFlag;
            }
        }

        protected Map<String, String> getErrorData()
        {
            synchronized (this)
            {
                return this.errorData;
            }
        }

        protected void onAbort(Map<String, String> errorData)
        {
            // Default does nothing, but can be overridden.
        }
    }

    /**
     * Now the MissionInit XML has been decoded, the client needs to create the
     * Mission Handlers.
     */
    public class CreateHandlersEpisode extends ErrorAwareEpisode
    {
        protected CreateHandlersEpisode(ClientStateMachine machine)
        {
            super(machine);
        }

        @Override
        protected void execute() throws Exception
        {
            // First, clear our reservation state, if we were reserved:
            ClientStateMachine.this.cancelReservation();

            // Now try creating the handlers:
            ClientStateMachine.this.missionBehaviour = MissionBehaviour.createAgentHandlersFromMissionInit(currentMissionInit());

            // Set up our command input poller. This is only checked during the MissionRunning episode, but
            // it needs to be started now, so we can report the port it's using back to the agent.
            TCPUtils.LogSection ls = new TCPUtils.LogSection("Initialise Command Input Poller");
            ClientAgentConnection cac = currentMissionInit().getClientAgentConnection();
            int requestedPort = cac.getClientCommandsPort();
            // If the requested port is 0, we dynamically allocate our own port, and feed that back to the agent.
            // If the requested port is non-zero, we have to use it.
            if (requestedPort != 0 && ClientStateMachine.this.controlInputPoller != null && ClientStateMachine.this.controlInputPoller.getPort() != requestedPort)
            {
                // A specific port has been requested, and it's not the one we are currently using,
                // so we need to recreate our poller.
                ClientStateMachine.this.controlInputPoller.stopServer();
                ClientStateMachine.this.controlInputPoller = null;
            }
            if (ClientStateMachine.this.controlInputPoller == null)
            {
                if (requestedPort == 0)
                    ClientStateMachine.this.controlInputPoller = new TCPInputPoller(AddressHelper.MIN_FREE_PORT, AddressHelper.MAX_FREE_PORT, true, "com");
                else
                    ClientStateMachine.this.controlInputPoller = new TCPInputPoller(requestedPort, "com");
                ClientStateMachine.this.controlInputPoller.start();
            }
            // Make sure the cac is up-to-date:
            cac.setClientCommandsPort(ClientStateMachine.this.controlInputPoller.getPortBlocking());
            ls.close();

            // Check to see whether anything has caused us to abort - if so, go to the abort state.
            if (inAbortState())
                episodeHasCompleted(ClientState.MISSION_ABORTED);

            // Set the agent's name as the current username:
            List<AgentSection> agents = currentMissionInit().getMission().getAgentSection();
            String agentName = agents.get(currentMissionInit().getClientRole()).getName();
            // AuthenticationHelper.setPlayerName(Minecraft.getMinecraft().getSession(), agentName);
            // If the player's profile properties are empty, MC will keep pinging the Minecraft session service
            // to fill them, resulting in multiple http requests and grumpy responses from the server
            // (see https://github.com/Microsoft/malmo/issues/568).
            // To prevent this, we add a dummy property.
            // Minecraft.getMinecraft().getProfileProperties().put("dummy", new Property("dummy", "property"));
            // Handlers and poller created successfully; proceed to next stage of loading.
            // We will either need to connect to an existing server, or to start
            // a new integrated server ourselves, depending on our role.
            // For now, assume that the mod with role 0 is responsible for the server.
            if (currentMissionInit().getClientRole() == 0)
            {
                // We are responsible for the server - investigate what needs to happen next:
                episodeHasCompleted(ClientState.EVALUATING_WORLD_REQUIREMENTS);
            }
            else
            {
                // We may need to connect to a server.
                episodeHasCompleted(ClientState.WAITING_FOR_SERVER_READY);
            }
        }
    }


    // ---------------------------------------------------------------------------------------------------------
    /**
     * Attempt to create a world.
     */
    public class CreateWorldEpisode extends ErrorAwareEpisode
    {
        boolean serverStarted = false;
        boolean worldCreated = false;
        int totalTicks = 0;

        CreateWorldEpisode(ClientStateMachine machine)
        {
            super(machine);
        }

        @Override
        protected void execute()
        {
            try
            {
                totalTicks = 0;

                // We need to use the server's MissionHandlers here:
                ClientStateMachine.this.serverHandlers = MissionBehaviour.createServerHandlersFromMissionInit(currentMissionInit());
                if (ClientStateMachine.this.serverHandlers != null && ClientStateMachine.this.serverHandlers.worldGenerator != null)
                {
                    if (ClientStateMachine.this.serverHandlers.worldGenerator.createWorld(currentMissionInit()))
                    {
                        ClientStateMachine.this.generatorProperties.clear();
                        this.worldCreated = true;
                        if (MinecraftClient.getInstance().getServer() != null) {
                            MinecraftClient.getInstance().getServer().setOnlineMode(false);
                        }
                    }
                    else
                    {
                        // World has not been created.
                        episodeHasCompletedWithErrors(ClientState.ERROR_CANNOT_CREATE_WORLD, "Server world-creation handler failed to create a world: " + serverHandlers.worldGenerator.getErrorDetails());
                    }
                }
            }
            catch (Exception e)
            {
                LOGGER.error("world creation failed:", e);
                episodeHasCompletedWithErrors(ClientState.ERROR_CANNOT_CREATE_WORLD, "Server world-creation handler failed to create a world: " + e.getMessage());
            }
        }

        @Override
        protected void onServerTick(MinecraftServer server) // called by Server thread
        {
            if (this.worldCreated && !this.serverStarted)
            {
                // The server has started ticking - we can set up its state machine,
                // and move on to the next state in our own machine.
                this.serverStarted = true;
                VereyaModServer.getInstance().initIntegratedServer(currentMissionInit(), server); // Needs to be done from the server thread.
                episodeHasCompleted(ClientState.WAITING_FOR_SERVER_READY);
            }
        }

        @Override
        public void onClientTick(MinecraftClient ev) // called in Render thread
        {
            // Check to see whether anything has caused us to abort - if so, go to the abort state.
            if (inAbortState())
                episodeHasCompleted(ClientState.MISSION_ABORTED);

            if (++totalTicks > WAIT_MAX_TICKS)
            {
                String msg = "Too long waiting for world to be created.";
                TCPUtils.Log(Level.SEVERE, msg);
                episodeHasCompletedWithErrors(ClientState.ERROR_TIMED_OUT_WAITING_FOR_WORLD_CREATE, msg);
            }
        }
    }

    /**
     * Depending on the basemap provided, either begin to perform a full world
     * load, or reset the current world
     */
    public class EvaluateWorldRequirementsEpisode extends StateEpisode {
        EvaluateWorldRequirementsEpisode(ClientStateMachine machine) {
            super(machine);
        }

        @Override
        protected void execute() {
            // We are responsible for creating the server, if required.
            // This means we need access to the server's MissionHandlers:
            MissionBehaviour serverHandlers = null;
            try {
                serverHandlers = MissionBehaviour.createServerHandlersFromMissionInit(currentMissionInit());
            } catch (Exception e) {
                episodeHasCompletedWithErrors(ClientState.ERROR_DUFF_HANDLERS, "Could not create server mission handlers: " + e.getMessage());
            }


            World world = MinecraftClient.getInstance().world;
            Object genOptions = null;
            if (world != null) {
                genOptions = generatorProperties.get(world.getRegistryKey());
            }
            boolean needsNewWorld = serverHandlers != null && serverHandlers.worldGenerator != null && serverHandlers.worldGenerator.shouldCreateWorld(currentMissionInit(), genOptions);
            boolean worldCurrentlyExists = world != null;
            List<AgentSection> agents = currentMissionInit().getMission().getAgentSection();
            String agentName = agents.get(currentMissionInit().getClientRole()).getName();
            if (worldCurrentlyExists) {
                // If a world already exists, we need to check that our requested agent name matches the name
                // of the player. If not, the safest thing to do is start a new server.
                // Get our name from the Mission:
                PlayerEntity player = MinecraftClient.getInstance().player;
                if (player != null) {
                    String playerName = player.getName().asString();
                    if (!playerName.equals(agentName))
                        needsNewWorld = true;
                }
            }
            if (needsNewWorld) {
                ((SessionMixin)MinecraftClient.getInstance().getSession()).setName(agentName);
            }
            if (needsNewWorld && worldCurrentlyExists) {
                // We want a new world, and there is currently a world running,
                // so we need to kill the current world.
                episodeHasCompleted(ClientState.PAUSING_OLD_SERVER);
            } else if (needsNewWorld && !worldCurrentlyExists) {
                // We want a new world, and there is currently nothing running,
                // so jump to world creation:
                episodeHasCompleted(ClientState.CREATING_NEW_WORLD);
            } else if (!needsNewWorld && worldCurrentlyExists) {
                // We don't want a new world, and we can use the current one -
                // but we own the server, so we need to pass it the new mission init:
                MinecraftClient.getInstance().getServer().execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            VereyaModServer.getInstance().sendMissionInitDirectToServer(currentMissionInit);
                            //MalmoMod.instance.sendMissionInitDirectToServer(currentMissionInit);
                        } catch (Exception e) {
                            episodeHasCompletedWithErrors(ClientState.ERROR_INTEGRATED_SERVER_UNREACHABLE, "Could not send MissionInit to our integrated server: " + e.getMessage());
                        }
                    }
                });
                // Skip all the map loading stuff and go straight to waiting for the server:
                episodeHasCompleted(ClientState.WAITING_FOR_SERVER_READY);
            } else if (!needsNewWorld && !worldCurrentlyExists) {
                // Mission has requested no new world, but there is no current world to play in - this is an error:
                episodeHasCompletedWithErrors(ClientState.ERROR_NO_WORLD, "We have no world to play in - check that your ServerHandlers section contains a world generator");
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    /**
     * Attempt to connect to a server. Wait until connection is established.
     */
    public class WaitingForServerEpisode extends ErrorAwareEpisode implements IMalmoMessageListener
    {
        String agentName;
        int ticksUntilNextPing = 0;
        int totalTicks = 0;
        boolean waitingForChunk = false;
        boolean waitingForPlayer = true;

        protected WaitingForServerEpisode(ClientStateMachine machine)
        {
            super(machine);
            SidesMessageHandler.server2client.registerForMessage(this, MalmoMessageType.SERVER_ALLPLAYERSJOINED);
        }

        private boolean isChunkReady()
        {/*
            // First, find the starting position we ought to have:
            List<AgentSection> agents = currentMissionInit().getMission().getAgentSection();
            if (agents == null || agents.size() <= currentMissionInit().getClientRole())
                return true;    // This should never happen.
            AgentSection as = agents.get(currentMissionInit().getClientRole());
            if (as.getAgentStart() != null && as.getAgentStart().getPlacement() != null)
            {
                PosAndDirection pos = as.getAgentStart().getPlacement();
                int x = MathHelper.floor(pos.getX().doubleValue()) >> 4;
                int z = MathHelper.floor(pos.getZ().doubleValue()) >> 4;

                // Now get the chunk we should be starting in:
                IChunkProvider chunkprov = Minecraft.getMinecraft().world.getChunkProvider();
                MinecraftClient.getInstance().player.setPos();
                EntityPlayerSP player = Minecraft.getMinecraft().player;
                if (player.addedToChunk)
                {
                    // Our player is already added to a chunk - is it the right one?
                    Chunk actualChunk = chunkprov.provideChunk(player.chunkCoordX, player.chunkCoordZ);
                    Chunk requestedChunk = chunkprov.provideChunk(x,  z);
                    if (actualChunk == requestedChunk && actualChunk != null && !actualChunk.isEmpty())
                    {
                        // We're in the right chunk, and it's not an empty chunk.
                        // We're ready to proceed, but first set our client positions to where we ought to be.
                        // The server should be doing this too, but there's no harm (probably) in doing it ourselves.
                        player.posX = pos.getX().doubleValue();
                        player.posY = pos.getY().doubleValue();
                        player.posZ = pos.getZ().doubleValue();
                        return true;
                    }
                }
                return false;   // Our starting position has been specified, but it's not yet ready.
            }*/
            return true;    // No starting position specified, so doesn't matter where we start.
        }

        private void setClientPos(){
            List<AgentSection> agents = currentMissionInit().getMission().getAgentSection();
            AgentSection as = agents.get(currentMissionInit().getClientRole());
            PosAndDirection pos = as.getAgentStart().getPlacement();
            LOGGER.info("Setting agent pos to: x(" + pos.getX() + ") z(" + pos.getZ()  + ") y(" + pos.getY() + ")");
            MinecraftClient.getInstance().player.setPos(
                    pos.getX().doubleValue(),
                    pos.getY().doubleValue(),
                    pos.getZ().doubleValue());
        }

        @Override
        protected void onClientTick(MinecraftClient client)
        {
            // Check to see whether anything has caused us to abort - if so, go to the abort state.
            if (inAbortState())
                episodeHasCompleted(ClientState.MISSION_ABORTED);

            if (this.waitingForPlayer)
            {
                if (client.player != null)
                {
                    setClientPos();
                    this.waitingForPlayer = false;
                    handleLan();
                }
                else
                    return;
            }

            totalTicks++;

            if (ticksUntilNextPing == 0)
            {
                // Tell the server what our agent name is.
                // We do this repeatedly, because the server might not yet be listening.
                if (client.player != null && !this.waitingForChunk)
                {
                    HashMap<String, String> map = new HashMap<String, String>();
                    map.put("agentname", agentName);
                    map.put("username", client.player.getName().asString());
                    currentMissionBehaviour().appendExtraServerInformation(map);
                    LOGGER.info("***Telling server we are ready - " + agentName);
                    ClientPlayNetworking.send(NetworkConstants.CLIENT2SERVER,
                            (new MalmoMessage(MalmoMessageType.CLIENT_AGENTREADY, 0, map)).toBytes());
                }

                // We also ping our agent, just to check it is still available:
                pingAgent(true);    // Will abort to an error state if client unavailable.

                ticksUntilNextPing = 10; // Try again in ten ticks.
            }
            else
            {
                ticksUntilNextPing--;
            }

            if (this.waitingForChunk)
            {
                // The server is ready, we're just waiting for our chunk to appear.
                if (isChunkReady())
                    proceed();
            }

            List<AgentSection> agents = currentMissionInit().getMission().getAgentSection();
            boolean completedWithErrors = false;

            if (agents.size() > 1 && currentMissionInit().getClientRole() != 0)
            {
                throw new RuntimeException("Not implemented");
                /*
                // We are waiting to join an out-of-process server. Need to pay attention to what happens -
                // if we can't join, for any reason, we should abort the mission.
                GuiScreen screen = Minecraft.getMinecraft().currentScreen;
                if (screen != null && screen instanceof GuiDisconnected) {
                    // Disconnected screen appears when something has gone wrong.
                    // Would be nice to grab the reason from the screen, but it's a private member.
                    // (Can always use reflection, but it's so inelegant.)
                    String msg = "Unable to connect to Minecraft server in multi-agent mission.";
                    TCPUtils.Log(Level.SEVERE, msg);
                    episodeHasCompletedWithErrors(ClientState.ERROR_CANNOT_CONNECT_TO_SERVER, msg);
                    completedWithErrors = true;
                }*/
            }

            if (!completedWithErrors && totalTicks > WAIT_MAX_TICKS)
            {
                String msg = "Too long waiting for server episode to start.";
                TCPUtils.Log(Level.SEVERE, msg);
                episodeHasCompletedWithErrors(ClientState.ERROR_TIMED_OUT_WAITING_FOR_EPISODE_START, msg);
            }
        }

        @Override
        protected void execute() throws Exception
        {
            totalTicks = 0;

            // Minecraft.getMinecraft().displayGuiScreen(null); // Clear any menu screen that might confuse things.
            // Get our name from the Mission:
            List<AgentSection> agents = currentMissionInit().getMission().getAgentSection();
            //if (agents == null || agents.size() <= currentMissionInit().getClientRole())
            //    throw new Exception("No agent section for us!"); // TODO
            this.agentName = agents.get(currentMissionInit().getClientRole()).getName();

            if (agents.size() > 1 && currentMissionInit().getClientRole() != 0)
            {
                // Multi-agent mission, we should be joining a server.
                // (Unless we are already on the correct server.)
                String address = currentMissionInit().getMinecraftServerConnection().getAddress().trim();
                int port = currentMissionInit().getMinecraftServerConnection().getPort();
                String targetIP = address + ":" + port;
                System.out.println("We should be joining " + targetIP);
                ClientPlayerEntity player = MinecraftClient.getInstance().player;
                boolean namesMatch = (player == null) || player.getName().equals(this.agentName);
                if (!namesMatch)
                {
                    // The name of our agent no longer matches the agent in our game profile -
                    // safest way to update is to log out and back in again.
                    // This hangs so just warn instead about the miss-match and proceed.
                    TCPUtils.Log(Level.WARNING,"Agent name does not match agent in game.");
                    // Minecraft.getMinecraft().world.sendQuittingDisconnectingPacket();
                    // Minecraft.getMinecraft().loadWorld((WorldClient)null);
                }
                this.waitingForPlayer = false;
            }
        }

        protected void handleLan()
        {
            // Get our name from the Mission:
            /*List<AgentSection> agents = currentMissionInit().getMission().getAgentSection();
            this.agentName = agents.get(currentMissionInit().getClientRole()).getName();

            if (agents.size() > 1 && currentMissionInit().getClientRole() == 0) // Multi-agent mission - make sure the server is open to the LAN:
            {
                MinecraftServerConnection msc = new MinecraftServerConnection();
                String address = currentMissionInit().getClientAgentConnection().getClientIPAddress();
                // Do we need to open to LAN?
                if (Minecraft.getMinecraft().isSingleplayer() && !Minecraft.getMinecraft().getIntegratedServer().getPublic())
                {
                    String portstr = Minecraft.getMinecraft().getIntegratedServer().shareToLAN(GameType.SURVIVAL, true); // Set to true to stop spam kicks.
                    ClientStateMachine.this.integratedServerPort = Integer.valueOf(portstr);
                }

                TCPUtils.Log(Level.INFO,"Integrated server port: " + ClientStateMachine.this.integratedServerPort);
                msc.setPort(ClientStateMachine.this.integratedServerPort);
                msc.setAddress(address);

                currentMissionInit().setMinecraftServerConnection(msc);
            }*/
        }

        @Override
        public void onMessage(MalmoMessageType messageType, Map<String, String> data)
        {
            super.onMessage(messageType, data);

            if (messageType != MalmoMessageType.SERVER_ALLPLAYERSJOINED)
                return;
            MinecraftClient client = MinecraftClient.getInstance();
            ClientStateMachine.this.generatorProperties.put(client.world.getRegistryKey(),
                    ClientStateMachine.this.serverHandlers.worldGenerator.getOptions());
            List<Object> handlers = new ArrayList<Object>();
            for (Map.Entry<String, String> entry : data.entrySet())
            {
                if (entry.getKey().equals("startPosition"))
                {
                    /*try
                    {
                        String[] parts = entry.getValue().split(":");
                        Float x = Float.valueOf(parts[0]);
                        Float y = Float.valueOf(parts[1]);
                        Float z = Float.valueOf(parts[2]);
                        // Find the starting position we ought to have:
                        List<AgentSection> agents = currentMissionInit().getMission().getAgentSection();
                        if (agents != null && agents.size() > currentMissionInit().getClientRole())
                        {
                            // And write this new position into it:
                            AgentSection as = agents.get(currentMissionInit().getClientRole());
                            AgentStart startSection = as.getAgentStart();
                            if (startSection != null)
                            {
                                PosAndDirection pos = startSection.getPlacement();
                                if (pos == null)
                                    pos = new PosAndDirection();
                                pos.setX(new BigDecimal(x));
                                pos.setY(new BigDecimal(y));
                                pos.setZ(new BigDecimal(z));
                                startSection.setPlacement(pos);
                                as.setAgentStart(startSection);
                            }
                        }
                    }
                    catch (Exception e)
                    {
                        System.out.println("Couldn't interpret position data");
                    }*/
                }
                else
                {
                    String extraHandler = entry.getValue();
                    if (extraHandler != null && extraHandler.length() > 0)
                    {
                        try
                        {
                            Class<?> handlerClass = Class.forName(entry.getKey());
                            Object handler = SchemaHelper.deserialiseObject(extraHandler, MissionInit.class);
                            // Object handler = SchemaHelper.deserialiseObject(extraHandler, "MissionInit.xsd", handlerClass);
                            handlers.add(handler);
                        }
                        catch (Exception e)
                        {
                            System.out.println("Error trying to create extra handlers: " + e);
                            // Do something... like episodeHasCompletedWithErrors(nextState, error)?
                        }
                    }
                }
            }
            if (!handlers.isEmpty())
                currentMissionBehaviour().addExtraHandlers(handlers);
            this.waitingForChunk = true;
        }

        private void proceed()
        {
            // The server is ready, so send our MissionInit back to the agent and go!
            // We launch the agent by sending it the MissionInit message we were sent
            // (but with the Launcher's IP address included)
            String xml = null;
            boolean sentOkay = false;
            String errorReport = "";
            try
            {
                xml = SchemaHelper.serialiseObject(currentMissionInit(), MissionInit.class);
                if (AddressHelper.getMissionControlPort() == 0) {
                    sentOkay = true;
                } else {
                    sentOkay = ClientStateMachine.this.getMissionControlSocket().sendTCPString(xml, 1);
                }
            }
            catch (JAXBException e)
            {
                errorReport = e.getMessage();
            }
            if (sentOkay)
                episodeHasCompleted(ClientState.RUNNING);
            else
            {
                ClientStateMachine.this.getScreenHelper().addFragment("ERROR: Could not contact agent to start mission - mission will abort.",
                        ScreenHelper.TextCategory.TXT_CLIENT_WARNING, 10000);
                if (!errorReport.isEmpty())
                {
                    ClientStateMachine.this.getScreenHelper().addFragment("ERROR DETAILS: " + errorReport, ScreenHelper.TextCategory.TXT_CLIENT_WARNING, 10000);
                    errorReport = ": " + errorReport;
                }
                episodeHasCompletedWithErrors(ClientState.ERROR_CANNOT_START_AGENT, "Failed to send MissionInit back to agent" + errorReport);
            }
        }

        @Override
        public void cleanup()
        {
            super.cleanup();
            SidesMessageHandler.server2client.deregisterForMessage(this, MalmoMessageType.SERVER_ALLPLAYERSJOINED);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    /**
     * State in which a mission is running.<br>
     * This state is ended by the death of the player or by the IWantToQuit
     * handler, or by the server declaring the mission is over.
     */
    public class MissionRunningEpisode extends ErrorAwareEpisode implements VideoProducedObserver
    {
        public static final int FailedTCPSendCountTolerance = 3; // Number of TCP timeouts before we cancel the mission

        protected MissionRunningEpisode(ClientStateMachine machine)
        {
            super(machine);
            SidesMessageHandler.server2client.registerForMessage(this, MalmoMessageType.SERVER_STOPAGENTS);
            SidesMessageHandler.server2client.registerForMessage(this, MalmoMessageType.SERVER_GO);
        }

        boolean serverHasFiredStartingPistol = false;
        boolean playerDied = false;
        private int failedTCPRewardSendCount = 0;
        private int failedTCPObservationSendCount = 0;
        private boolean wantsToQuit = false; // We have decided our mission is at an end
        private List<VideoHook> videoHooks = new ArrayList<VideoHook>();
        private String quitCode = "";
        private TCPSocketChannel observationSocket = null;
        private TCPSocketChannel rewardSocket = null;
        private long lastPingSent = 0;
        private long pingFrequencyMs = 1000;

        private long frameTimestamp = 0;

        public void frameProduced() {
            this.frameTimestamp = System.currentTimeMillis();
        }

        protected void onMissionStarted()
        {
            frameTimestamp = 0;

            // Open our communication channels:
            openSockets();

            // Tell the server we have started:
            HashMap<String, String> map = new HashMap<String, String>();
            map.put("username", MinecraftClient.getInstance().player.getName().asString());
            MalmoMessage msg = new MalmoMessage(MalmoMessageType.CLIENT_AGENTRUNNING, 0, map);
            ClientPlayNetworking.send(NetworkConstants.CLIENT2SERVER,  msg.toBytes());

            // Set up our mission handlers:
            if (currentMissionBehaviour().commandHandler != null)
            {
                currentMissionBehaviour().commandHandler.install(currentMissionInit());
                currentMissionBehaviour().commandHandler.setOverriding(true);
            }

            if (currentMissionBehaviour().observationProducer != null)
                currentMissionBehaviour().observationProducer.prepare(currentMissionInit());

            if (currentMissionBehaviour().quitProducer != null)
                currentMissionBehaviour().quitProducer.prepare(currentMissionInit());

            if (currentMissionBehaviour().rewardProducer != null)
                currentMissionBehaviour().rewardProducer.prepare(currentMissionInit());

            for (IVideoProducer videoProducer : currentMissionBehaviour().videoProducers)
            {
                VideoHook hook = new VideoHook();
                this.videoHooks.add(hook);
                frameProduced();
                hook.start(currentMissionInit(), videoProducer, this);
            }

            // Make sure we have mouse control:
            ClientStateMachine.this.inputController.setInputType(VereyaModClient.InputType.AI);
            /*
            Minecraft.getMinecraft().inGameHasFocus = true; // Otherwise auto-repeat won't work for mouse clicks.

            // Overclocking:
            ModSettings modsettings = currentMissionInit().getMission().getModSettings();
            if (modsettings != null && modsettings.getMsPerTick() != null)
                TimeHelper.setMinecraftClientClockSpeed(1000 / modsettings.getMsPerTick());
            if (modsettings != null && modsettings.isPrioritiseOffscreenRendering() == Boolean.TRUE)
                TimeHelper.displayGranularityMs = 1000;*/
        }

        protected void onMissionEnded(IState nextState, String errorReport)
        {
            // Tidy up our mission handlers:
            if (currentMissionBehaviour().rewardProducer != null)
                currentMissionBehaviour().rewardProducer.cleanup();

            if (currentMissionBehaviour().quitProducer != null)
                currentMissionBehaviour().quitProducer.cleanup();

            if (currentMissionBehaviour().observationProducer != null)
                currentMissionBehaviour().observationProducer.cleanup();

            if (currentMissionBehaviour().commandHandler != null)
            {
                currentMissionBehaviour().commandHandler.setOverriding(false);
                currentMissionBehaviour().commandHandler.deinstall(currentMissionInit());
            }

            // Close our communication channels:
            closeSockets();

            for (VideoHook hook : this.videoHooks)
                hook.stop(ClientStateMachine.this.missionEndedData);

            // Return Minecraft speed to "normal":
            // TimeHelper.setMinecraftClientClockSpeed(20);
            // TimeHelper.displayGranularityMs = 0;

            ClientStateMachine.this.missionQuitCode = this.quitCode;
            if (errorReport != null)
                episodeHasCompletedWithErrors(nextState, errorReport);
            else
                episodeHasCompleted(nextState);
        }

        @Override
        protected void execute()
        {
            onMissionStarted();
        }

        @Override
        public void onClientTick(MinecraftClient event)
        {
            // Check to see whether anything has caused us to abort - if so, go to the abort state.
            if (inAbortState())
                onMissionEnded(ClientState.MISSION_ABORTED, "Mission was aborted by server: " + ClientStateMachine.this.getErrorDetails());

            // Check to see whether we've been kicked from the server.
            /*
            NetworkManager netman = Minecraft.getMinecraft().getConnection().getNetworkManager();
            if (netman != null && !netman.hasNoChannel() && !netman.isChannelOpen())
            {
                // Connection has been lost.
                onMissionEnded(ClientState.ERROR_LOST_NETWORK_CONNECTION, "Client was kicked from server - " + netman.getExitMessage().getUnformattedText());
            }*/

            // Check we are still in touch with the agent:
            if (System.currentTimeMillis() > this.lastPingSent + this.pingFrequencyMs)
            {
                this.lastPingSent = System.currentTimeMillis();
                // Ping the agent - if serverHasFiredStartingPistol is true, we don't need to abort -
                // we can simply set the wantsToQuit flag and end the mission cleanly.
                // If serverHasFiredStartingPistol is false, then the mission isn't yet running, and
                // setting the quit flag will do nothing - so we need to abort.
                if (!pingAgent(false))
                {
                    if (!this.serverHasFiredStartingPistol)
                        onMissionEnded(ClientState.ERROR_LOST_AGENT, "Lost contact with the agent");
                    else
                    {
                        System.out.println("Error - agent is not responding to pings.");
                        this.wantsToQuit = true;
                        this.quitCode = VereyaModClient.AGENT_UNRESPONSIVE_CODE;
                    }
                }
            }

            if (this.frameTimestamp != 0 && (System.currentTimeMillis() - this.frameTimestamp >  VIDEO_MAX_WAIT)) {
                System.out.println("No video produced recently. Aborting mission.");
                if (!this.serverHasFiredStartingPistol)
                    onMissionEnded(ClientState.ERROR_LOST_VIDEO, "No video produced recently.");
                else
                {
                    System.out.println("Error - not receiving video.");
                    this.wantsToQuit = true;
                    this.quitCode = VereyaModClient.VIDEO_UNRESPONSIVE_CODE;
                }
            }

            // Check here to see whether the player has died or not:
            if (!this.playerDied && !MinecraftClient.getInstance().player.isAlive())
            {
                this.playerDied = true;
                this.quitCode = VereyaModClient.AGENT_DEAD_QUIT_CODE;
                LOGGER.info("player died!");
            }

            // Although we only arrive in this episode once the server has determined that all clients are ready to go,
            // the server itself waits for all clients to begin running before it enters the running state itself.
            // This creates a small vulnerability, since a running client could theoretically *finish* its mission
            // before the server manages to *start*.
            // (This has potentially disastrous effects for the state machine, and is easy to reproduce by,
            // for example, setting the start point and goal of the mission to the same coordinates.)

            // To guard against this happening, although we are running, we don't act on anything -
            // we don't check for commands, or send observations or rewards - until we get the SERVER_GO signal,
            // which is sent once the server's running episode has started.
            if (!this.serverHasFiredStartingPistol)
                return;

            // Check whether or not we want to quit:
            IWantToQuit quitHandler = (currentMissionBehaviour() != null) ? currentMissionBehaviour().quitProducer : null;
            boolean quitHandlerFired = (quitHandler != null && quitHandler.doIWantToQuit(currentMissionInit()));
            if (quitHandlerFired || this.wantsToQuit || this.playerDied)
            {
                if (quitHandlerFired)
                {
                    this.quitCode = quitHandler.getOutcome();
                }
                /*
                try
                {
                    // Save the quit code for anything that needs it:
                    MalmoMod.getPropertiesForCurrentThread().put("QuitCode", this.quitCode);
                }
                catch (Exception e)
                {
                    System.out.println("Failed to get properties - final reward may go missing.");
                }*/

                // Get the final reward data:
                ClientAgentConnection cac = currentMissionInit().getClientAgentConnection();
                /*if (currentMissionBehaviour() != null && currentMissionBehaviour().rewardProducer != null && cac != null)
                    currentMissionBehaviour().rewardProducer.getReward(currentMissionInit(), ClientStateMachine.this.finalReward);*/

                // Now send a message to the server saying that we have finished our mission:
                List<AgentSection> agents = currentMissionInit().getMission().getAgentSection();
                String agentName = agents.get(currentMissionInit().getClientRole()).getName();
                HashMap<String, String> map = new HashMap<String, String>();
                map.put("agentname", agentName);
                map.put("username", MinecraftClient.getInstance().player.getName().asString());
                map.put("quitcode", this.quitCode);
                LOGGER.info("informing server that player has quited");
                ClientPlayNetworking.send(NetworkConstants.CLIENT2SERVER, (new MalmoMessage(MalmoMessageType.CLIENT_AGENTFINISHEDMISSION, 0, map)).toBytes());
                onMissionEnded(ClientState.IDLING, null);
            }
            else
            {
                // Send off observation and reward data:
                sendData();
                // And see if we have any incoming commands to act upon:
                checkForControlCommand();
            }

        }

        private void openSockets()
        {
            ClientAgentConnection cac = currentMissionInit().getClientAgentConnection();
            this.observationSocket = new TCPSocketChannel(cac.getAgentIPAddress(), cac.getAgentObservationsPort(), "obs");
            this.rewardSocket = new TCPSocketChannel(cac.getAgentIPAddress(), cac.getAgentRewardsPort(), "rew");
        }

        private void closeSockets()
        {
            this.observationSocket.close();
            this.rewardSocket.close();
        }

        private void sendData()
        {
            TCPUtils.LogSection ls = new TCPUtils.LogSection("Sending data");
            //Minecraft.getMinecraft().mcProfiler.endStartSection("malmoSendData");
            // Create the observation data:
            String data = "";
           // Minecraft.getMinecraft().mcProfiler.startSection("malmoGatherObservationJSON");
            if (currentMissionBehaviour() != null && currentMissionBehaviour().observationProducer != null)
            {
                JsonObject json = new JsonObject();
                currentMissionBehaviour().observationProducer.writeObservationsToJSON(json, currentMissionInit());
                data = json.toString();
            }
            // Minecraft.getMinecraft().mcProfiler.endStartSection("malmoSendTCPObservations");

            ClientAgentConnection cac = currentMissionInit().getClientAgentConnection();

            if (data != null && data.length() > 2 && cac != null) // An empty json string will be "{}" (length 2) - don't send these.
            {
                if (AddressHelper.getMissionControlPort() == 0) {

                } else {
                    // Bung the whole shebang off via TCP:
                    if (this.observationSocket.sendTCPString(data)) {
                        this.failedTCPObservationSendCount = 0;
                    } else {
                        // Failed to send observation message.
                        this.failedTCPObservationSendCount++;
                        TCPUtils.Log(Level.WARNING, "Observation signal delivery failure count at " + this.failedTCPObservationSendCount);
                        ClientStateMachine.this.getScreenHelper().addFragment("ERROR: Agent missed observation signal", ScreenHelper.TextCategory.TXT_CLIENT_WARNING, 5000);
                    }
                }
            }

            // Minecraft.getMinecraft().mcProfiler.endStartSection("malmoGatherRewardSignal");
            // Now create the reward signal:
            /*
            if (currentMissionBehaviour() != null && currentMissionBehaviour().rewardProducer != null && cac != null)
            {
                MultidimensionalReward reward = new MultidimensionalReward();
                currentMissionBehaviour().rewardProducer.getReward(currentMissionInit(), reward);
                if (!reward.isEmpty())
                {
                    String strReward = reward.getAsSimpleString();
                    Minecraft.getMinecraft().mcProfiler.startSection("malmoSendTCPReward");

                    ScoreHelper.logReward(strReward);

                    if (AddressHelper.getMissionControlPort() == 0) {
                        // MalmoEnvServer - reward
                        if (envServer != null) {
                            envServer.addRewards(reward.getRewardTotal());
                        }
                    } else {
                        if (this.rewardSocket.sendTCPString(strReward)) {
                            this.failedTCPRewardSendCount = 0; // Reset the count of consecutive TCP failures.
                        } else {
                            // Failed to send TCP message - probably because the agent has quit under our feet.
                            // (This happens a lot when developing a Python agent - the developer has no easy way to quit
                            // the agent cleanly, so tends to kill the process.)
                            this.failedTCPRewardSendCount++;
                            TCPUtils.Log(Level.WARNING, "Reward signal delivery failure count at " + this.failedTCPRewardSendCount);
                            ClientStateMachine.this.getScreenHelper().addFragment("ERROR: Agent missed reward signal", TextCategory.TXT_CLIENT_WARNING, 5000);
                        }
                    }
                }
            }
            Minecraft.getMinecraft().mcProfiler.endSection();
            */
            int maxFailedTCPSendCount = 0;
            for (VideoHook hook : this.videoHooks)
            {
                if (hook.failedTCPSendCount > maxFailedTCPSendCount)
                    maxFailedTCPSendCount = hook.failedTCPSendCount;
            }
            if (maxFailedTCPSendCount > 0)
                TCPUtils.Log(Level.WARNING, "Video signal failure count at " + maxFailedTCPSendCount);
            // Check that our messages are getting through:
            int maxFailed = Math.max(this.failedTCPRewardSendCount, maxFailedTCPSendCount);
            maxFailed = Math.max(maxFailed, this.failedTCPObservationSendCount);
            if (maxFailed > FailedTCPSendCountTolerance)
            {
                // They're not - and we've exceeded the count of allowed TCP failures.
                System.out.println("ERROR: TCP messages are not getting through - quitting mission.");
                this.wantsToQuit = true;
                this.quitCode = VereyaModClient.AGENT_UNRESPONSIVE_CODE;
            }
            ls.close();
        }

        /**
         * Check to see if any control instructions have been received and act on them if so.
         */
        private void checkForControlCommand()
        {
            // Minecraft.getMinecraft().mcProfiler.endStartSection("malmoCommandHandling");
            String command;
            boolean quitHandlerFired = false;
            IWantToQuit quitHandler = (currentMissionBehaviour() != null) ? currentMissionBehaviour().quitProducer : null;
            command = ClientStateMachine.this.controlInputPoller.getCommand();

            while (command != null && command.length() > 0 && !quitHandlerFired)
            {
                // TCPUtils.Log(Level.INFO, "Act on " + command);
                // Pass the command to our various control overrides:
                // Minecraft.getMinecraft().mcProfiler.startSection("malmoCommandAct");
                boolean handled = handleCommand(command);
                // Get the next command:
                command = ClientStateMachine.this.controlInputPoller.getCommand();

                // If there *is* another command (commands came in faster than one per client tick),
                // then we should check our quit producer before deciding whether to execute it.
                // Minecraft.getMinecraft().mcProfiler.endStartSection("malmoCommandRecheckQuitHandlers");
                if (command != null && command.length() > 0 && handled)
                    quitHandlerFired = (quitHandler != null && quitHandler.doIWantToQuit(currentMissionInit()));
                // Minecraft.getMinecraft().mcProfiler.endSection();
            }
        }

        /**
         * Attempt to handle a command string by passing it to our various external controllers in turn.
         *
         * @param command the command string to be handled.
         * @return true if the command was handled.
         */
        private boolean handleCommand(String command)
        {
            if (currentMissionBehaviour() != null && currentMissionBehaviour().commandHandler != null)
            {
                return currentMissionBehaviour().commandHandler.execute(command, currentMissionInit());
            }
            return false;
        }

        @Override
        public void onMessage(MalmoMessageType messageType, Map<String, String> data)
        {
            super.onMessage(messageType, data);
            // This message will be sent to us once the server has decided the mission is over.
            if (messageType == MalmoMessageType.SERVER_STOPAGENTS)
            {
                this.quitCode = data.containsKey("QuitCode") ? data.get("QuitCode") : "";
                /*
                try
                {
                    // Save the quit code for anything that needs it:
                    MalmoMod.getPropertiesForCurrentThread().put("QuitCode", this.quitCode);
                }
                catch (Exception e)
                {
                    System.out.println("Failed to get properties - final reward may go missing.");
                }*/
                // Get the final reward data:
                ClientAgentConnection cac = currentMissionInit().getClientAgentConnection();/*
                if (currentMissionBehaviour() != null && currentMissionBehaviour().rewardProducer != null && cac != null)
                    currentMissionBehaviour().rewardProducer.getReward(currentMissionInit(), ClientStateMachine.this.finalReward);
                    */

                onMissionEnded(ClientState.MISSION_ENDED, null);
            }/*
            else if (messageType == MalmoMessageType.SERVER_GO)
            {
                // First, force all entities to get re-added to their chunks, clearing out any old entities in the process.
                // We need to do this because the process of teleporting all agents to their start positions, combined
                // with setting them to/from spectator mode, leaves the client chunk entity lists etc in a parlous state.

                List lel = Minecraft.getMinecraft().world.loadedEntityList;
                for (int i = 0; i < lel.size(); i++)
                {
                    Entity entity = (Entity)lel.get(i);
                    Chunk chunk = Minecraft.getMinecraft().world.getChunkFromChunkCoords(entity.chunkCoordX, entity.chunkCoordZ);
                    List<Entity> entitiesToRemove = new ArrayList<Entity>();
                    for (int k = 0; k < chunk.getEntityLists().length; k++)
                    {
                        Iterator iterator = chunk.getEntityLists()[k].iterator();
                        while (iterator.hasNext())
                        {
                            Entity chunkent = (Entity)iterator.next();
                            if (chunkent.getEntityId() == entity.getEntityId())
                            {
                                entitiesToRemove.add(chunkent);
                            }
                        }
                    }
                    for (Entity removeEnt : entitiesToRemove)
                    {
                        chunk.removeEntity(removeEnt);
                    }
                    entity.addedToChunk = false;    // Will force it to get re-added to the chunk list.
                    if (entity instanceof EntityLivingBase)
                    {
                        // If we want the entities to be rendered with the correct yaw from the outset,
                        // we need to set their render offset manually.
                        // (Set the offset from the outset to avoid the onset of upset.)
                        ((EntityLivingBase)entity).renderYawOffset = entity.rotationYaw;
                        ((EntityLivingBase)entity).prevRenderYawOffset = entity.rotationYaw;
                    }
                    if (entity instanceof EntityPlayerSP)
                    {
                        // Although the following call takes place on the server, and should have taken effect already,
                        // there is some discontinuity which is causing the effects to get lost, so we call it here too:
                        entity.setInvisible(false);
                    }
                }*/
                this.serverHasFiredStartingPistol = true; // GO GO GO!
            }

        @Override
        public void cleanup()
        {
            super.cleanup();
            SidesMessageHandler.server2client.deregisterForMessage(this, MalmoMessageType.SERVER_STOPAGENTS);
            SidesMessageHandler.server2client.deregisterForMessage(this, MalmoMessageType.SERVER_GO);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    /**
     * Pause the old server. It's vital that we do this, otherwise it will
     * respond to the quit disconnect package straight away and kill the server
     * thread, which means there will be no server to respond to the loadWorld
     * code. (This was the cause of the infamous "Holder Lookups" hang.)
     */
    public class PauseOldServerEpisode extends ErrorAwareEpisode
    {
        PauseOldServerEpisode(ClientStateMachine machine)
        {
            super(machine);
        }

        @Override
        protected void execute()
        {

            MinecraftServer server = MinecraftClient.getInstance().getServer();
            if (server != null && MinecraftClient.getInstance().world != null)
            {
                server.stop(true);
            }
            LOGGER.info("clean generator properties");
            ClientStateMachine.this.generatorProperties.clear();
            episodeHasCompleted(ClientState.CLOSING_OLD_SERVER);
        }
    }


    // ---------------------------------------------------------------------------------------------------------
    /**
     * Dummy state, just in case if we need to wait for something before creating a new world
     */
    public class CloseOldServerEpisode extends ErrorAwareEpisode
    {
        int totalTicks;

        CloseOldServerEpisode(ClientStateMachine machine)
        {
            super(machine);
        }

        @Override
        protected void execute() {}

        public void onClientTick(MinecraftClient ev)
        {
            // Check to see whether anything has caused us to abort - if so, go to the abort state.
            if (inAbortState())
                episodeHasCompleted(ClientState.MISSION_ABORTED);

            episodeHasCompleted(ClientState.CREATING_NEW_WORLD);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    /**
     * State that occurs at the end of the mission, whether due to death,
     * failure, success, error, or whatever.
     */
    public class MissionEndedEpisode extends ErrorAwareEpisode
    {
        private MissionResult result;
        private boolean aborting;
        private boolean informServer;
        private boolean informAgent;
        private int totalTicks = 0;

        public MissionEndedEpisode(ClientStateMachine machine, MissionResult mr, boolean aborting, boolean informServer, boolean informAgent)
        {
            super(machine);
            this.result = mr;
            this.aborting = aborting;
            this.informServer = informServer;
            this.informAgent = informAgent;
        }

        @Override
        protected void execute()
        {
            totalTicks = 0;

            // Get a text report:
            String errorFeedback = ClientStateMachine.this.getErrorDetails();
            String quitFeedback = ClientStateMachine.this.missionQuitCode;
            String concatenation = (errorFeedback != null && !errorFeedback.isEmpty() && quitFeedback != null && !quitFeedback.isEmpty()) ? ";\n" : "";
            String report = quitFeedback + concatenation + errorFeedback;

            if (this.informServer && (MinecraftClient.getInstance().getServer() != null))
            {
                // Inform the server of what has happened.
                HashMap<String, String> map = new HashMap<String, String>();
                PlayerEntity player = MinecraftClient.getInstance().player;
                if (player != null) // Might not be a player yet.
                    map.put("username", player.getName().asString());
                map.put("error", ClientStateMachine.this.getErrorDetails());
                PacketByteBuf buf = new MalmoMessage(MalmoMessageType.CLIENT_BAILED, 0, map).toBytes();
                LOGGER.debug("informing server of a failure with: " + map.toString());
                ClientPlayNetworking.send(NetworkConstants.CLIENT2SERVER, buf);
            }

            if (this.informAgent)
            {
                // Create a MissionEnded instance for this result:
                MissionEnded missionEnded = new MissionEnded();
                missionEnded.setStatus(this.result);
                if (ClientStateMachine.this.missionQuitCode != null &&
                        ClientStateMachine.this.missionQuitCode.equals(VereyaModClient.AGENT_DEAD_QUIT_CODE))
                    missionEnded.setStatus(MissionResult.PLAYER_DIED); // Need to do this manually.
                missionEnded.setHumanReadableStatus(report);
                if (!ClientStateMachine.this.finalReward.isEmpty())
                {
                    missionEnded.setReward(ClientStateMachine.this.finalReward.getAsReward());
                    ClientStateMachine.this.finalReward.clear();
                }
                missionEnded.setMissionDiagnostics(ClientStateMachine.this.missionEndedData);	// send our diagnostics
                ClientStateMachine.this.missionEndedData = new MissionDiagnostics();			// and clear them for the next mission
                // And send MissionEnded message to the agent to inform it that the mission has ended:
                sendMissionEnded(missionEnded);
            }

            if (this.aborting) // Take the shortest path back to dormant.
                episodeHasCompleted(ClientState.DORMANT);
        }

        private void sendMissionEnded(MissionEnded missionEnded)
        {
            // Send a MissionEnded message to the agent to inform it that the mission has ended.
            // Create a string XML representation:
            String missionEndedString = null;
            try
            {
                missionEndedString = SchemaHelper.serialiseObject(missionEnded, MissionEnded.class);
                /*
                if (ScoreHelper.isScoring()) {
                    Reward reward = missionEnded.getReward();
                    if (reward == null) {
                        reward = new Reward();
                    }
                    ScoreHelper.logMissionEndRewards(reward);
                } */
            }
            catch (JAXBException e)
            {
                TCPUtils.Log(Level.SEVERE, "Failed mission end XML serialization: " + e);
            }

            boolean sentOkay = false;
            if (missionEndedString != null)
            {
                if (AddressHelper.getMissionControlPort() == 0) {
                    sentOkay = true;
                } else {
                    TCPSocketChannel sender = ClientStateMachine.this.getMissionControlSocket();
                    System.out.println(String.format("Sending mission ended message to %s:%d.", sender.getAddress(), sender.getPort()));
                    sentOkay = sender.sendTCPString(missionEndedString);
                    sender.close();
                }
            }

            if (!sentOkay)
            {
                // Couldn't formulate a reply to the agent - bit of a problem.
                // Can't do much to alert the agent itself,
                // will have to settle for alerting anyone who is watching the mod:
                ClientStateMachine.this.getScreenHelper().addFragment("ERROR: Could not send mission ended message - agent may need manually resetting.", ScreenHelper.TextCategory.TXT_CLIENT_WARNING, 10000);
            }
        }

        @Override
        public void onClientTick(MinecraftClient event)
        {
            if (!this.aborting)
                episodeHasCompleted(ClientState.WAITING_FOR_SERVER_MISSION_END);

            if (++totalTicks > WAIT_MAX_TICKS)
            {
                String msg = "Too long waiting for server to end mission.";
                TCPUtils.Log(Level.SEVERE, msg);
                episodeHasCompletedWithErrors(ClientState.ERROR_TIMED_OUT_WAITING_FOR_MISSION_END, msg);
            }
        }
    }

    /**
     * Wait for the server to decide the mission has ended.<br>
     * We're not allowed to return to dormant until the server decides everyone can.
     */
    public class WaitingForServerMissionEndEpisode extends ErrorAwareEpisode
    {
        protected WaitingForServerMissionEndEpisode(ClientStateMachine machine)
        {
            super(machine);
            SidesMessageHandler.server2client.registerForMessage(this, MalmoMessageType.SERVER_MISSIONOVER);
        }

        @Override
        protected void execute() throws Exception
        {
            // Get our name from the Mission:
            List<AgentSection> agents = currentMissionInit().getMission().getAgentSection();
            if (agents == null || agents.size() <= currentMissionInit().getClientRole())
                throw new Exception("No agent section for us!"); // TODO
            String agentName = agents.get(currentMissionInit().getClientRole()).getName();

            // Now send a message to the server saying that we are ready:
            HashMap<String, String> map = new HashMap<String, String>();
            map.put("agentname", agentName);
            ClientPlayNetworking.send(NetworkConstants.CLIENT2SERVER,
                    new MalmoMessage(MalmoMessageType.CLIENT_AGENTSTOPPED, 0, map).toBytes());
        }

        @Override
        public void onMessage(MalmoMessageType messageType, Map<String, String> data)
        {
            super.onMessage(messageType, data);
            if (messageType == MalmoMessageType.SERVER_MISSIONOVER)
                episodeHasCompleted(ClientState.DORMANT);
        }

        @Override
        public void cleanup()
        {
            super.cleanup();
            SidesMessageHandler.server2client.deregisterForMessage(this, MalmoMessageType.SERVER_MISSIONOVER);
        }

        @Override
        protected void onAbort(Map<String, String> errorData)
        {
            episodeHasCompleted(ClientState.MISSION_ABORTED);
        }
    }


    /**
     * State in which an agent has finished the mission, but is waiting for the server to draw stumps.
     */
    public class MissionIdlingEpisode extends ErrorAwareEpisode
    {
        int totalTicks = 0;

        protected MissionIdlingEpisode(ClientStateMachine machine)
        {
            super(machine);
            SidesMessageHandler.server2client.registerForMessage(this, MalmoMessageType.SERVER_STOPAGENTS);
        }

        @Override
        protected void execute()
        {
            totalTicks = 0;
        }

        @Override
        public void onMessage(MalmoMessageType messageType, Map<String, String> data)
        {
            super.onMessage(messageType, data);
            // This message will be sent to us once the server has decided the mission is over.
            if (messageType == MalmoMessageType.SERVER_STOPAGENTS)
                episodeHasCompleted(ClientState.MISSION_ENDED);
        }

        @Override
        public void cleanup()
        {
            super.cleanup();
            SidesMessageHandler.server2client.deregisterForMessage(this, MalmoMessageType.SERVER_STOPAGENTS);
        }

        @Override
        public void onClientTick(MinecraftClient ev)
        {
            // Check to see whether anything has caused us to abort - if so, go to the abort state.
            if (inAbortState())
                episodeHasCompleted(ClientState.MISSION_ABORTED);

            ++totalTicks;
        }
    }
}
