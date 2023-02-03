package io.singularitynet.Client;

import io.singularitynet.mixin.MinecraftClientMixin;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Keyboard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.client.util.InputUtil;
import org.apache.logging.log4j.LogManager;
import org.lwjgl.glfw.GLFW;

public class VereyaModClient implements ClientModInitializer, IMalmoModClient
{
    public static final String AGENT_DEAD_QUIT_CODE = "MALMO_AGENT_DIED";
    public static final String AGENT_UNRESPONSIVE_CODE = "MALMO_AGENT_NOT_RESPONDING";
    public static final String VIDEO_UNRESPONSIVE_CODE = "MALMO_VIDEO_NOT_RESPONDING";

    public class MyMouse extends Mouse {

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
            if(VereyaModClient.this.inputType == InputType.AI) {
                return true;
            }
            return super.isCursorLocked();
        }

        public boolean shouldUpdate(){
            return VereyaModClient.this.inputType == InputType.HUMAN;
        }
    }

    private class MyKeyboard extends Keyboard {
        public MyKeyboard(MinecraftClient client) {
            super(client);
        }

        @Override
        public void onKey(long window, int key, int scancode, int action, int modifiers) {
            super.onKey(window, key, scancode, action, modifiers);
            VereyaModClient.this.onKey(window, key, scancode, action, modifiers);
        }
    }

    @Override
    public void onInitializeClient() {
        // Register for various events:
        // MinecraftForge.EVENT_BUS.register(this);
        // TCPUtils.setLogging(TCPUtils.SeverityLevel.LOG_DETAILED);
        this.stateMachine = new ClientStateMachine(ClientState.WAITING_FOR_MOD_READY, (IMalmoModClient) this);
    }

    public void setup(){
        Keyboard keyboard = new MyKeyboard(MinecraftClient.getInstance());
        keyboard.setup(MinecraftClient.getInstance().getWindow().getHandle());
        ((MinecraftClientMixin)MinecraftClient.getInstance()).setKeyboard(keyboard);
        Mouse mouse = new MyMouse(MinecraftClient.getInstance());
        mouse.setup(MinecraftClient.getInstance().getWindow().getHandle());
        ((MinecraftClientMixin)MinecraftClient.getInstance()).setMouse(mouse);
    }

    @Override
    public InputType getInputType() {
        return this.inputType;
    }

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
    /** Switch the input type between Human and AI.<br>
     * Will switch on/off the command overrides.
     * @param input type of control (Human/AI)
     */
    public void setInputType(InputType input)
    {
        if (this.stateMachine.currentMissionBehaviour() != null && this.stateMachine.currentMissionBehaviour().commandHandler != null)
            this.stateMachine.currentMissionBehaviour().commandHandler.setOverriding(input == InputType.AI);

        // This stops Minecraft from doing the annoying thing of stealing your mouse.
        // System.setProperty("fml.noGrab", input == InputType.AI ? "true" : "false");
        inputType = input;
        if (input == InputType.HUMAN)
        {
            MinecraftClient.getInstance().mouse.lockCursor();
            // Minecraft.getMinecraft().mouseHelper.grabMouseCursor();
        }
        else {
            // Minecraft.getMinecraft().mouseHelper.ungrabMouseCursor();
            MinecraftClient.getInstance().mouse.unlockCursor();
        }
        LogManager.getLogger().info("Mouse: " + input);
        // this.stateMachine.getScreenHelper().addFragment("Mouse: " + input, ScreenHelper.TextCategory.TXT_INFO, INFO_MOUSE_CONTROL);
    }

    private void onKey(long window, int key, int scancode, int action, int modifiers) {
        boolean change = false;
        if (key == GLFW.GLFW_KEY_ENTER && action == GLFW.GLFW_RELEASE) change = true;
        if (!change) return;
        if (inputType == InputType.AI) {
            setInputType(InputType.HUMAN);
        } else {
            setInputType(InputType.AI);
        }
    }
}
