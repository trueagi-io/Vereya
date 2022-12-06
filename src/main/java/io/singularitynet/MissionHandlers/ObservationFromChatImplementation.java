package io.singularitynet.MissionHandlers;

import io.singularitynet.MissionHandlerInterfaces.IObservationProducer;
import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.singularitynet.MyChatHud;
import io.singularitynet.mixin.InGameHudMixin;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.InGameHud;
import io.singularitynet.projectmalmo.MissionInit;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.nbt.visitor.NbtTextFormatter;
import net.minecraft.network.message.MessageType;
import net.minecraft.text.Text;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;

public class ObservationFromChatImplementation extends HandlerBase implements IObservationProducer, io.singularitynet.MissionHandlerInterfaces.ClientChatListener {
    private static final Logger LOGGER = LogManager.getLogger();
    private ChatHud backup;
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
        ChatHud hud = MinecraftClient.getInstance().inGameHud.getChatHud();

        if (hud instanceof MyChatHud){
            MyChatHud hud1 = (MyChatHud) hud;
            hud1.getListeners().remove(this);
        }

        if (backup != null) {
            ((InGameHudMixin) (MinecraftClient.getInstance().inGameHud)).setChatHud(backup);
            backup = null;
        }
    }

    @Override
    public void prepare(MissionInit missionInit) {
        backup = MinecraftClient.getInstance().inGameHud.getChatHud();
        MyChatHud myHud = new MyChatHud(MinecraftClient.getInstance());
        myHud.getListeners().add(this);
        ((InGameHudMixin) (MinecraftClient.getInstance().inGameHud)).setChatHud(myHud);
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
    public void onChatMessage(Text text) {
        LOGGER.info("got chat message " + text.getString());
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player != null){
            String playerName = player.getName().getString();
            String msgString = text.getString().replaceAll("<" + playerName + ">", "").strip();
            this.chatMessagesReceived.add(new ChatMessage("Chat", msgString));
        }
    }
}
