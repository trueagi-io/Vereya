package io.singularitynet;

import java.util.HashMap;
import java.util.Map;


/** General purpose messaging class<br>
 * Used to pass messages from the server to the client.
 */
public class MalmoMessage
{
    private MalmoMessageType messageType = MalmoMessageType.SERVER_NULLMESSASGE;
    private int uid = 0;
    private Map<String, String> data = new HashMap<String, String>();

    public MalmoMessage()
    {
    }

    /** Construct a message for all listeners of that messageType
     * @param messageType
     * @param message
     */
    public MalmoMessage(MalmoMessageType messageType, String message)
    {
        this.messageType = messageType;
        this.uid = 0;
        this.data.put("message",  message);
    }

    /** Construct a message for the (hopefully) single listener that matches the uid
     * @param messageType
     * @param uid a hash code that (more or less) uniquely identifies the targeted listener
     * @param message
     */
    public MalmoMessage(MalmoMessageType messageType, int uid, Map<String, String> data)
    {
        this.messageType = messageType;
        this.uid = uid;
        this.data = data;
    }
}