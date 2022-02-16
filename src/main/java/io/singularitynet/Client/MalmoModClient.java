package io.singularitynet.Client;


import net.fabricmc.api.ClientModInitializer;

public class MalmoModClient implements ClientModInitializer, IMalmoModClient
{
    @Override
    public void onInitializeClient() {
        // Register for various events:
        // MinecraftForge.EVENT_BUS.register(this);
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

}
