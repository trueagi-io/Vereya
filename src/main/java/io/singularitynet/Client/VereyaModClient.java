package io.singularitynet.Client;


import io.singularitynet.utils.ScreenHelper;
import io.singularitynet.utils.TCPUtils;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;

public class VereyaModClient implements ClientModInitializer, IMalmoModClient
{
    public static final String AGENT_DEAD_QUIT_CODE = "MALMO_AGENT_DIED";
    public static final String AGENT_UNRESPONSIVE_CODE = "MALMO_AGENT_NOT_RESPONDING";
    public static final String VIDEO_UNRESPONSIVE_CODE = "MALMO_VIDEO_NOT_RESPONDING";

    @Override
    public void onInitializeClient() {
        // Register for various events:
        // MinecraftForge.EVENT_BUS.register(this);
        TCPUtils.setLogging(TCPUtils.SeverityLevel.LOG_DETAILED);
        this.stateMachine = new ClientStateMachine(ClientState.WAITING_FOR_MOD_READY, (IMalmoModClient) this);
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
        System.setProperty("fml.noGrab", input == InputType.AI ? "true" : "false");
        inputType = input;
        if (input == InputType.HUMAN)
        {
            MinecraftClient.getInstance().mouse.lockCursor();
            // Minecraft.getMinecraft().mouseHelper.grabMouseCursor();
        }
        else
        {
            // Minecraft.getMinecraft().mouseHelper.ungrabMouseCursor();
            MinecraftClient.getInstance().mouse.unlockCursor();
        }

        // this.stateMachine.getScreenHelper().addFragment("Mouse: " + input, ScreenHelper.TextCategory.TXT_INFO, INFO_MOUSE_CONTROL);
    }
}
