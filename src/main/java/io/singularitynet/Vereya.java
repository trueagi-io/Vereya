package io.singularitynet;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.InterModComms;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.event.lifecycle.InterModEnqueueEvent;
import net.minecraftforge.fml.event.lifecycle.InterModProcessEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.singularitynet.Client.MalmoModClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// The value here should match an entry in the META-INF/mods.toml file
@Mod("vereya")
public class Vereya
{    
	public static final String AGENT_DEAD_QUIT_CODE = "MALMO_AGENT_DIED";
	private static final String PROTOCOL_VERSION = "1";
    // Directly reference a log4j logger.
    private static final Logger LOGGER = LogManager.getLogger();
    public static final SimpleChannel network = NetworkRegistry.newSimpleChannel(
    	    new ResourceLocation("vereya", "main"),
    	    () -> PROTOCOL_VERSION,
    	    PROTOCOL_VERSION::equals,
    	    PROTOCOL_VERSION::equals
    	);
	private IMalmoModClient client;
    
    public Vereya() {
        // Register the setup method for modloading
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
        // Register the enqueueIMC method for modloading
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::enqueueIMC);
        // Register the processIMC method for modloading
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::processIMC);
        // Register the client setup method
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onClientSetup);

        // Register ourselves for server and other game events we are interested in
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void setup(final FMLCommonSetupEvent event)
    {
        // some preinit code
        LOGGER.info("HELLO FROM PREINIT");
        LOGGER.info("DIRT BLOCK >> {}", Blocks.DIRT.getRegistryName());
        // network communication between logical client and logical server
        /*
        int id = 0;
        network.registerMessage(id++, ObservationFromFullStatsImplementation.FullStatsRequestMessage.class, 1, Side.SERVER);
        network.registerMessage(id++, ObservationFromGridImplementation.GridRequestMessage.class, 2, Side.SERVER);
        network.registerMessage(id++, MalmoMessage.class, 3, Side.CLIENT);	// Malmo messages from server to client
        network.registerMessage(id++, SimpleCraftCommandsImplementation.CraftMessage.class, 4, Side.SERVER);
        network.registerMessage(id++, NearbyCraftCommandsImplementation.CraftNearbyMessage.class, 13, Side.SERVER);
        network.registerMessage(id++, NearbySmeltCommandsImplementation.SmeltNearbyMessage.class, 14, Side.SERVER);
        network.registerMessage(id++, AbsoluteMovementCommandsImplementation.TeleportMessage.class, 5, Side.SERVER);
        network.registerMessage(id++, MalmoMessage.class, 6, Side.SERVER);	// Malmo messages from client to server
        network.registerMessage(id++, InventoryCommandsImplementation.InventoryMessage.class, 7, Side.SERVER);
        network.registerMessage(id++, DiscreteMovementCommandsImplementation.UseActionMessage.class, 8, Side.SERVER);
        network.registerMessage(id++, DiscreteMovementCommandsImplementation.AttackActionMessage.class, 9, Side.SERVER);
        network.registerMessage(id++, ObservationFromFullInventoryImplementation.InventoryRequestMessage.class, 10, Side.SERVER);
        network.registerMessage(id++, InventoryCommandsImplementation.InventoryChangeMessage.class, 11, Side.CLIENT);
        network.registerMessage(id++, ObservationFromSystemImplementation.SystemRequestMessage.class, 12, Side.SERVER);
        */
    }

    private void enqueueIMC(final InterModEnqueueEvent event)
    {
        // some example code to dispatch IMC to another mod
        InterModComms.sendTo("examplemod", "helloworld", () -> { LOGGER.info("Hello world from the MDK"); return "Hello world";});
    }

    private void processIMC(final InterModProcessEvent event)
    {
        // some example code to receive and process InterModComms from other mods
        LOGGER.info("Got IMC {}", event.getIMCStream().
                map(m->m.messageSupplier().get()).
                collect(Collectors.toList()));
    }
    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // do something when the server starts
        LOGGER.info("HELLO from server starting");
    }

    private void onClientSetup(final FMLClientSetupEvent event) {
        // do something that can only be done on the client
        LOGGER.info("HELLO CLIENT SETUP");
        this.client = new MalmoModClient();
        this.client.init();
    }
    
    // You can use EventBusSubscriber to automatically subscribe events on the contained class (this is subscribing to the MOD
    // Event bus for receiving Registry Events)
    @Mod.EventBusSubscriber(bus=Mod.EventBusSubscriber.Bus.MOD)
    public static class RegistryEvents {
        @SubscribeEvent
        public static void onBlocksRegistry(final RegistryEvent.Register<Block> blockRegistryEvent) {
            // register a new block here
            LOGGER.info("HELLO from Register Block");
        }
    }
    
    /** Handler for messages from the server to the clients. Register with this to receive specific messages.
     */
     public static class MalmoMessageHandler
     {
         static private Map<MalmoMessageType, List<IMalmoMessageListener>> listeners = new HashMap<MalmoMessageType, List<IMalmoMessageListener>>();
         public MalmoMessageHandler()
         {
         }

         public static boolean registerForMessage(IMalmoMessageListener listener, MalmoMessageType messageType)
         {
             if (!listeners.containsKey(messageType))
                 listeners.put(messageType,  new ArrayList<IMalmoMessageListener>());

             if (listeners.get(messageType).contains(listener))
                 return false;	// Already registered.

             listeners.get(messageType).add(listener);
             return true;
         }
     }
}
