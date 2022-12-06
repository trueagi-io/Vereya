package io.singularitynet;

import io.singularitynet.MissionHandlerInterfaces.ClientChatListener;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.network.message.MessageType;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class MyChatHud extends ChatHud {

    private List<ClientChatListener> listeners;

    public MyChatHud(MinecraftClient client) {
        super(client);
        this.listeners = new ArrayList<>();
    }

    public void addMessage(Text message, @Nullable MessageSignatureData signature, @Nullable MessageIndicator indicator) {
        super.addMessage(message, signature, indicator);
        for (ClientChatListener listener: listeners) {
            listener.onChatMessage(message);
        }
    }

    public List<ClientChatListener> getListeners(){
        return this.listeners;
    }

}
