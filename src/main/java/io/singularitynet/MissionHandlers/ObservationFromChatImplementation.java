package io.singularitynet.MissionHandlers;

import io.singularitynet.MissionHandlerInterfaces.IObservationProducer;
import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.singularitynet.mixin.InGameHudMixin;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.ClientChatListener;
import net.minecraft.client.gui.hud.InGameHud;
import io.singularitynet.projectmalmo.MissionInit;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.nbt.visitor.NbtTextFormatter;
import net.minecraft.network.MessageType;
import net.minecraft.text.Text;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;

public class ObservationFromChatImplementation extends HandlerBase implements IObservationProducer, ClientChatListener
{
    private static final Logger LOGGER = LogManager.getLogger();

    private class ChatMessage
    {
        public String messageType;
        public String messageContent;
        public ChatMessage(String messageType, String messageContent)
        {
            this.messageType = messageType;
            this.messageContent = messageContent;
        }
    }

    private ArrayList<ChatMessage> chatMessagesReceived = new ArrayList<ChatMessage>();

    @Override
    public void cleanup() {
        InGameHud hud = MinecraftClient.getInstance().inGameHud;
        ((InGameHudMixin)hud).getListeners().get((Object) MessageType.CHAT).remove(this);
    }

    @Override
    public void prepare(MissionInit missionInit) {
        InGameHud hud = MinecraftClient.getInstance().inGameHud;
        ((InGameHudMixin)hud).getListeners().get((Object) MessageType.CHAT).add(this);
    }

    @Override
    public void writeObservationsToJSON(JsonObject json, MissionInit missionInit)
    {
        if (!this.chatMessagesReceived.isEmpty())
        {
            HashMap<String, ArrayList<String>> lists = new HashMap<String, ArrayList<String>>();
            for (ChatMessage message : this.chatMessagesReceived)
            {
                ArrayList<String> arr = lists.get(message.messageType);
                if (arr == null)
                {
                    arr = new ArrayList<String>();
                    lists.put(message.messageType, arr);
                }
                arr.add(message.messageContent);
            }
            for (String key : lists.keySet())
            {
                JsonArray jarr = new JsonArray();
                for (String message : lists.get(key))
                {
                    jarr.add(new JsonPrimitive(message));
                }
                json.add(key, jarr);
            }
            this.chatMessagesReceived.clear();
        }
    }

    @Override
    public void onChatMessage(MessageType messageType, Text text, UUID var3) {
        LOGGER.info("got chat message " + text.getString());
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player != null){
            String playerName = player.getName().getString();
            String msgString = text.getString().replaceAll("<" + playerName + ">", "").strip();
            this.chatMessagesReceived.add(new ChatMessage("Chat", msgString));
        }
    }
}
