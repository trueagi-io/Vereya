package io.singularitynet;

import java.util.Map;


public interface IMalmoMessageListener
{
    void onMessage(MalmoMessageType messageType, Map<String, String> data);
}
