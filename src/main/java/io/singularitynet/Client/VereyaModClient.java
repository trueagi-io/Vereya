package io.singularitynet.Client;

import io.singularitynet.MissionHandlers.MissionBehaviour;
import io.singularitynet.NetworkConstants;
import io.singularitynet.SidesMessageHandler;
import io.singularitynet.events.ScreenEvents;
import io.singularitynet.mixin.MinecraftClientMixin;
import io.singularitynet.mixin.MouseAccessorMixin;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Keyboard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.client.gui.screen.*;
import net.minecraft.text.Text;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;

public class VereyaModClient implements ClientModInitializer, IMalmoModClient, ScreenEvents
{
    public static final String CONTROLLABLE = "ControlledMobs";
    private long timeSwitchHybrid = 0;

    public static final String AGENT_DEAD_QUIT_CODE = "MALMO_AGENT_DIED";
    public static final String AGENT_UNRESPONSIVE_CODE = "MALMO_AGENT_NOT_RESPONDING";
    public static final String VIDEO_UNRESPONSIVE_CODE = "MALMO_VIDEO_NOT_RESPONDING";
    private static final Logger LOGGER = LogManager.getLogger(VereyaModClient.class.getName());

    private boolean wasNotNull = false;

    public interface MouseEventListener
    {
        public void onXYChange(double deltaX, double deltaY);
    }

    private InputType backupInputType = null;
    private Screen currentScreen = null;

    @Override
    public void interact(MinecraftClient client, @Nullable Screen screen) {
        if (this.currentScreen != null) return;  // avoid recursion
        LogManager.getLogger().debug("VereyaModClient: screen changed to " + screen);
        if (screen == null){
            if (this.backupInputType != null) {
                LOGGER.info("VereyaModClient: restoring input type to " + this.backupInputType);
                this.setInputType(this.backupInputType);
                this.backupInputType = null;
            }
            return;
        }
        if (screen instanceof GameMenuScreen){
            setInputOnScreen(screen);
        }
        if (screen instanceof TitleScreen){
            setInputOnScreen(screen);
            this.backupInputType = null;
        }
    }

    private void setInputOnScreen(@NotNull Screen screen) {
        LOGGER.info("VereyaModClient: switching to HUMAN input");
        this.backupInputType = this.inputType;
        this.currentScreen = screen;
        this.setInputType(InputType.HUMAN);  // this calls setScreen(null) which calls this function again
        this.currentScreen = null;
    }

    public class MyMouse extends Mouse {

        private MouseEventListener observer;

        public MyMouse(MinecraftClient client) {
            super(client);
        }

        @Override
        public void lockCursor(){
            if(VereyaModClient.this.inputType == InputType.AI) {
                return;
            }
            super.lockCursor();
        }

        @Override
        public boolean isCursorLocked() {
            MissionBehaviour behaviour = VereyaModClient.this.stateMachine.currentMissionBehaviour();

            // This stops Minecraft from doing the annoying thing of stealing your mouse.
            // do not let to lock if we are overriding inputs
            if (behaviour != null && behaviour.commandHandler.isOverriding()) {
                return true;
            }
            return super.isCursorLocked();
        }

        public void onMouseUsed(){
            if (VereyaModClient.this.inputType == InputType.HYBRID_MOUSE_KEYBOARD){
                setOverrideHybrid(false);
            }
        }

        public boolean shouldUpdate(){
            MissionBehaviour behaviour = VereyaModClient.this.stateMachine.currentMissionBehaviour();
            // If AI is not overriding we should update the mouse
            return behaviour == null || !behaviour.commandHandler.isOverriding();
        }

        @Override
        public void updateMouse() {
            if(MinecraftClient.getInstance().player == null){
                return;
            }
            if (this.observer != null){
                double dx = ((MouseAccessorMixin)this).getCursorDeltaX();
                double dy = ((MouseAccessorMixin)this).getCursorDeltaY();
                this.observer.onXYChange(dx, dy);
            }
            super.updateMouse();
        }

        public void setObserver(MouseEventListener obj){
            this.observer = obj;
        }
    }

    private class MyKeyboard extends Keyboard {
        public MyKeyboard(MinecraftClient client) {
            super(client);
        }

        @Override
        public void onKey(long window, int key, int scancode, int action, int modifiers) {
            VereyaModClient.this.onKey(window, key, scancode, action, modifiers);
            super.onKey(window, key, scancode, action, modifiers);
        }
    }

    @Override
    public void onInitializeClient() {
        this.stateMachine = new ClientStateMachine(ClientState.WAITING_FOR_MOD_READY, (IMalmoModClient) this);
        // subscribe to setScreen event
        ScreenEvents.SET_SCREEN.register(this);
        // register the instance for messages from Server to the Client
        ClientPlayNetworking.registerGlobalReceiver(NetworkConstants.SERVER2CLIENT,
                (client, handler, buf, responseSender) -> { SidesMessageHandler.server2client.onMessage(client, buf) ; });
    }

    public void setup(){
        Keyboard keyboard = new MyKeyboard(MinecraftClient.getInstance());
        keyboard.setup(MinecraftClient.getInstance().getWindow().getHandle());
        ((MinecraftClientMixin)MinecraftClient.getInstance()).setKeyboard(keyboard);
        Mouse mouse = new MyMouse(MinecraftClient.getInstance());
        mouse.setup(MinecraftClient.getInstance().getWindow().getHandle());
        ((MinecraftClientMixin)MinecraftClient.getInstance()).setMouse(mouse);
        // register callback to run each frame
        ClientTickEvents.END_CLIENT_TICK.register(client -> this.checkHybrideOverrides());
    }

    private void checkHybrideOverrides() {
        if (this.getInputType() == InputType.HYBRID_MOUSE_KEYBOARD) {
            MissionBehaviour behaviour = this.stateMachine.currentMissionBehaviour();
            if (behaviour != null && !behaviour.commandHandler.isOverriding()) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - timeSwitchHybrid > 1000)
                    setOverrideHybrid(true);
            }
        }
    }

    @Override
    public InputType getInputType() {
        return this.inputType;
    }

    // Control overriding:
    enum InputType
    {
        HUMAN, AI, HYBRID_KEYBOARD, HYBRID_MOUSE_KEYBOARD;
        private static final InputType[] rVals = {HUMAN, AI};
        private static final InputType[] hVals = {HYBRID_KEYBOARD, HYBRID_MOUSE_KEYBOARD};

        public InputType next() {
            return rVals[(this.ordinal() + 1) % rVals.length];
        }

        public InputType hNext() {
            return hVals[(this.ordinal() + 1) % hVals.length];
        }
    }

    protected InputType inputType = InputType.HUMAN;

    private static ClientStateMachine stateMachine;

    public static Map<String, MobEntity> getControllableEntities(){
        if (stateMachine == null)
            return new HashMap<>();
        return stateMachine.controllableEntities;
    }

    private static final String INFO_MOUSE_CONTROL = "mouse_control";

    /** Switch the input type between Human and AI.<br>
     * Will switch on/off the command overrides.
     * @param input type of control (Human/AI)
     */
    public void setInputType(InputType input)
    {
        LOGGER.debug("set input type to " + input.name());
        MissionBehaviour behaviour = this.stateMachine.currentMissionBehaviour();
        if (behaviour == null) {
            LOGGER.debug("current mission behaviour is null, returning");
            return;
        }
        if (behaviour.commandHandler == null){
            LOGGER.debug("commandHandler is null, returning");
            return;
        }

        this.stateMachine.currentMissionBehaviour().commandHandler.setOverriding(input == InputType.AI || input == InputType.HYBRID_MOUSE_KEYBOARD);

        this.inputType = input;
        // send chat message
        if ((MinecraftClient.getInstance().player != null) && (!wasNotNull))
            MinecraftClient.getInstance().player.sendMessage(Text.of("input type set to: " + input.name()), true);
        if (input == InputType.HUMAN || input == InputType.HYBRID_MOUSE_KEYBOARD)
        {
            MinecraftClient.getInstance().mouse.lockCursor();
        }
        else {
            MinecraftClient.getInstance().mouse.unlockCursor();
        }
        LogManager.getLogger().info("successfully set input type to: " + input);
    }

    private void onKey(long window, int key, int scancode, int action, int modifiers) {
        // do default thing if any screen is open
        Screen screen = MinecraftClient.getInstance().currentScreen;
        if (screen != null) {
            wasNotNull = true;
            return;
        }
        if (wasNotNull)
        {
            //since our agent can spam attack and therefore after opening inventory or chat or advancements attack won't work
            // we need to manually set attackCooldown to 0.
            MinecraftClient.getInstance().attackCooldown=0;
            wasNotNull = false;
        }

        if ((key == GLFW.GLFW_KEY_F6) && (action == GLFW.GLFW_PRESS))
        {
            setInputType(inputType.hNext());
        }

        if (((inputType == InputType.HYBRID_KEYBOARD))) {
            if ((action == GLFW.GLFW_PRESS)) {
                this.stateMachine.currentMissionBehaviour().commandHandler.setOverriding(false);
            } else if ((action == GLFW.GLFW_RELEASE)) {
                this.stateMachine.currentMissionBehaviour().commandHandler.setOverriding(true);
            }
        }

        if (inputType == InputType.HYBRID_MOUSE_KEYBOARD && action == GLFW.GLFW_PRESS) {
            // human controls the inputs
            setOverrideHybrid(false);
        }

        if ((key == GLFW.GLFW_KEY_ENTER) && (action != GLFW.GLFW_RELEASE))
            setInputType(inputType.next());
    }

    private void setOverrideHybrid(boolean override) {
        MissionBehaviour behaviour = this.stateMachine.currentMissionBehaviour();
        if (behaviour == null) {
            return;
        }
        timeSwitchHybrid = System.currentTimeMillis();
        behaviour.commandHandler.setOverriding(override);
    }
}