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

import io.singularitynet.*;
import io.singularitynet.MissionHandlers.MissionBehaviour;
import io.singularitynet.projectmalmo.*;
import io.singularitynet.utils.*;
import io.singularitynet.utils.TCPInputPoller.CommandAndIPAddress;
import jakarta.xml.bind.JAXBException;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.xml.sax.SAXException;

import javax.xml.stream.XMLStreamException;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
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
    private static final int WAIT_MAX_TICKS = 2000; // Over 1 minute and a half in client ticks.
    private static final int VIDEO_MAX_WAIT = 90 * 1000; // Max wait for video in ms.
    private static final String MISSING_MCP_PORT_ERROR = "no_mcp";
    private static final String INFO_MCP_PORT = "info_mcp";
    private static final String INFO_RESERVE_STATUS = "info_reservation";
    // Directly reference a log4j logger.
    private static final Logger LOGGER = LogManager.getLogger();
    private MissionInit currentMissionInit = null; // The MissionInit object for the mission currently being loaded/run.
    private IMissionBehaviour missionBehaviour = null;// new MissionBehaviour();
    private String missionQuitCode = ""; // The reason why this mission ended.

    private MissionDiagnostics missionEndedData = new MissionDiagnostics();
    private IScreenHelper screenHelper = null; // new ScreenHelper();
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
        // Vereya.MalmoMessageHandler.registerForMessage(this, MalmoMessageType.SERVER_TEXT);
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
        if (messageType == MalmoMessageType.SERVER_TEXT)
        {
        	System.out.println("got server message");
              
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

    public IMissionBehaviour currentMissionBehaviour()
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
                for (int i = 10; i > 0; i--) {
                    try {
                        Thread.sleep(1000);
                        System.out.println("Waiting to exit " + i + "...");
                    } catch (InterruptedException e) {
                        System.out.println("Interrupted " + i + "...");
                    }
                }

                // Kill it with fire!!!
                System.out.println("Attempting hard exit");
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
                        "10000");
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
            try
            {
                ClientStateMachine.this.missionBehaviour = MissionBehaviour.createAgentHandlersFromMissionInit(currentMissionInit());
            }
            catch (Exception e)
            {
                // TODO
            }
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
                MissionBehaviour serverHandlers = MissionBehaviour.createServerHandlersFromMissionInit(currentMissionInit());
                if (serverHandlers != null && serverHandlers.worldGenerator != null)
                {
                    if (serverHandlers.worldGenerator.createWorld(currentMissionInit()))
                    {
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
                // MalmoMod.instance.initIntegratedServer(currentMissionInit()); // Needs to be done from the server thread.
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

            World world = null;
            if (MinecraftClient.getInstance().getServer() != null) {
                // alternative: there's getWorld which accepts key
                for (World w : MinecraftClient.getInstance().getServer().getWorlds()) {
                    world = w;
                }
            }

            boolean needsNewWorld = serverHandlers != null && serverHandlers.worldGenerator != null && serverHandlers.worldGenerator.shouldCreateWorld(currentMissionInit(), world);
            boolean worldCurrentlyExists = world != null;
            if (worldCurrentlyExists) {
                // If a world already exists, we need to check that our requested agent name matches the name
                // of the player. If not, the safest thing to do is start a new server.
                // Get our name from the Mission:
                List<AgentSection> agents = currentMissionInit().getMission().getAgentSection();
                String agentName = agents.get(currentMissionInit().getClientRole()).getName();
                if (MinecraftClient.getInstance().player != null) {
                    if (!MinecraftClient.getInstance().player.getName().equals(agentName))
                        needsNewWorld = true;
                }
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
}
