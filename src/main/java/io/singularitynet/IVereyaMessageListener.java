package io.singularitynet;

import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Map;


public interface IVereyaMessageListener
{
    void onMessage(MalmoMessageType messageType, Map<String, String> data);
    void onMessage(MalmoMessageType messageType, Map<String, String> data, ServerPlayerEntity player);
}
