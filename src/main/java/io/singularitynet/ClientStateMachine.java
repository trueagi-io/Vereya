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

import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.logging.Level;

import javax.xml.stream.XMLStreamException;


import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.TickEvent.ClientTickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.Options;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.xml.sax.SAXException;

import com.google.gson.JsonObject;
import com.mojang.authlib.properties.Property;

import io.singularitynet.utils.IScreenHelper;
import io.singularitynet.utils.TCPInputPoller;
import io.singularitynet.utils.TCPInputPoller.CommandAndIPAddress;
import io.singularitynet.utils.TCPSocketChannel;
import io.singularitynet.utils.TCPUtils;
import io.singularitynet.utils.SchemaHelper;
import io.singularitynet.utils.ScreenHelper;
import io.singularitynet.utils.ScreenHelper.TextCategory;
import io.singularitynet.Client.MalmoModClient;
import com.microsoft.projectmalmo.ClientAgentConnection;
import com.microsoft.projectmalmo.MinecraftServerConnection;
import com.microsoft.projectmalmo.Mission;
import com.microsoft.projectmalmo.MissionDiagnostics;
import com.microsoft.projectmalmo.MissionEnded;
import com.microsoft.projectmalmo.MissionInit;
import com.microsoft.projectmalmo.MissionResult;
import io.singularitynet.utils.AddressHelper;
import jakarta.xml.bind.JAXBException;

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
        MinecraftForge.EVENT_BUS.register(this);
        Vereya.MalmoMessageHandler.registerForMessage(this, MalmoMessageType.SERVER_TEXT);
    }

    @Override
    public void clearErrorDetails()
    {
        super.clearErrorDetails();
        this.missionQuitCode = "";
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent ev)
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
                System.out.println("Received from " + ipFrom + ":" +
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
            Options settings = Minecraft.getInstance().options;
            settings.pauseOnLostFocus = false;
            // And hook the screen helper into the ingame gui (which is responsible for overlaying chat, titles etc) -
            // this has to be done after Minecraft.init(), so we do it here.
            //ScreenHelper.hookIntoInGameGui();
        }

        @Override
        public void onRenderTick(TickEvent.RenderTickEvent ev)
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
        public void onClientTick(TickEvent.ClientTickEvent ev) throws Exception
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
}
