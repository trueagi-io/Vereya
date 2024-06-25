package io.singularitynet;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

public record MessagePayloadS2C(VereyaMessage msg) implements CustomPayload {
    public static final PacketCodec<PacketByteBuf, VereyaMessage> PACKET_CODEC = new PacketCodec<PacketByteBuf, VereyaMessage>() {

        public VereyaMessage decode(PacketByteBuf byteBuf) {
            final VereyaMessage message = new VereyaMessage();
            message.fromBytes(byteBuf);
            return message;
        }

        public void encode(PacketByteBuf buf, VereyaMessage message) {
            message.toBytes(buf);
        }
    };

    public static final CustomPayload.Id<MessagePayloadC2S> ID = new CustomPayload.Id<>(NetworkConstants.SERVER2CLIENT);
    public static final PacketCodec<RegistryByteBuf, MessagePayloadC2S> CODEC = PacketCodec.tuple(PACKET_CODEC, MessagePayloadC2S::msg, MessagePayloadC2S::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
