package io.singularitynet;

import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.PacketByteBuf;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;



/** General purpose messaging class<br>
 * Used to pass messages from the server to the client.
 */
public class VereyaMessage implements SidesMessageHandler.IMessage
{
    private VereyaMessageType messageType = VereyaMessageType.SERVER_NULLMESSASGE;
    private int uid = 0;
    private Map<String, String> data = new HashMap<String, String>();

    public VereyaMessage()
    {}

    /** Construct a message for all listeners of that messageType
     * @param messageType
     * @param message
     */
    public VereyaMessage(VereyaMessageType messageType, String message)
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
    public VereyaMessage(VereyaMessageType messageType, int uid, Map<String, String> data)
    {
        this.messageType = messageType;
        this.uid = uid;
        if (data != null) {
            this.data = data;
        }
    }

    /** Read a UTF8 string that could potentially be larger than 64k<br>
     * The ByteBufInputStream.readUTF() and writeUTF() calls use the first two bytes of the message
     * to encode the length of the string, which limits the string length to 64k.
     * This method gets around that limitation by using a four byte header.
     * @param bbis ByteBufInputStream we are reading from
     * @return the (potentially large) string we read
     * @throws IOException
     */
    private String readLargeUTF(ByteBufInputStream bbis) throws IOException
    {
        int length = bbis.readInt();
        if (length == 0)
            return "";

        byte[] data = new byte[length];
        int length_read = bbis.read(data, 0, length);
        if (length_read != length)
            throw new IOException("Failed to read whole message");

        return new String(data, "utf-8");
    }

    /** Write a potentially long string as UTF8<br>
     * The ByteBufInputStream.readUTF() and writeUTF() calls use the first two bytes of the message
     * to encode the length of the string, which limits the string length to 64k.
     * This method gets around that limitation by using a four byte header.
     * @param s The string we are sending
     * @param bbos The ByteBufOutputStream we are writing to
     * @throws IOException
     */
    private void writeLargeUTF(String s, ByteBufOutputStream bbos) throws IOException
    {
        byte[] data = s.getBytes("utf-8");
        bbos.writeInt(data.length);
        bbos.write(data);
    }

    public void fromBytes(PacketByteBuf buf)
    {
        int i = buf.readVarInt();	// Read message type from first byte.
        if (i >= 0 && i <= VereyaMessageType.values().length)
            this.messageType = VereyaMessageType.values()[i];
        else
            this.messageType = VereyaMessageType.SERVER_NULLMESSASGE;

        // Now read the uid:
        this.uid = buf.readInt();

        // And the actual message content:
        // First, the number of entries in the map:
        int length = buf.readInt();
        this.data = new HashMap<String, String>();
        // Now read each key/value pair:
        ByteBufInputStream bbis = new ByteBufInputStream(buf);
        for (i = 0; i < length; i++)
        {
            String key;
            String value;
            try
            {
                key = bbis.readUTF();
                value = readLargeUTF(bbis);
                this.data.put(key, value);
            }
            catch (IOException e)
            {
                System.out.println("Warning - failed to read message data");
            }
        }
        try
        {
            bbis.close();
        }
        catch (IOException e)
        {
            System.out.println("Warning - failed to read message data");
        }
    }

    public void toBytes(PacketByteBuf buf)
    {
        buf.writeVarInt(this.messageType.ordinal()); // First byte is the message type.
        buf.writeInt(this.uid);
        // Now write the data as a set of string pairs:
        ByteBufOutputStream bbos = new ByteBufOutputStream(buf);
        buf.writeInt(this.data.size());
        for (Map.Entry<String, String> e : this.data.entrySet())
        {
            try
            {
                bbos.writeUTF(e.getKey());
                writeLargeUTF(e.getValue(), bbos);
            }
            catch (IOException e1)
            {
                System.out.println("Warning - failed to write message data");
            }
        }
        try
        {
            bbos.close();
        }
        catch (IOException e1)
        {
            System.out.println("Warning - failed to write message data");
        }
    }

    public PacketByteBuf toBytes(){
        PacketByteBuf buf = PacketByteBufs.create();
        toBytes(buf);
        return buf;
    }

    public VereyaMessageType getMessageType(){
        return this.messageType;
    }

    public Map<String, String> getData(){
        return this.data;
    }
}