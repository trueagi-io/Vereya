package io.singularitynet.MissionHandlerInterfaces;

import net.minecraft.network.message.MessageType;
import net.minecraft.text.Text;

import java.util.UUID;

public interface ClientChatListener {
    void onChatMessage(Text message);
}
