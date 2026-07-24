package cn.net.rms.confluxmap.compat;

import io.netty.buffer.Unpooled;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
//#if MC>=12005
//$$ import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
//$$ import net.minecraft.network.RegistryByteBuf;
//$$ import net.minecraft.network.codec.PacketCodec;
//$$ import net.minecraft.network.packet.CustomPayload;
//#endif
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/** Preserves raw custom-channel bytes across Fabric's 1.20.5 CustomPayload API rewrite. */
public final class PlayNetworking {
    private PlayNetworking() {
    }

    @FunctionalInterface
    public interface ClientReceiver {
        void receive(MinecraftClient client, ClientPlayNetworkHandler handler, byte[] payload);
    }

    @FunctionalInterface
    public interface ServerReceiver {
        void receive(MinecraftServer server, ServerPlayerEntity player, byte[] payload);
    }

    public static void registerClient(final Identifier id, final ClientReceiver receiver) {
        //#if MC>=12005
        //$$ final Channel channel = channel(id);
        //$$ ClientPlayNetworking.registerGlobalReceiver(channel.type, (payload, context) ->
        //$$     receiver.receive(context.client(), context.player().networkHandler, payload.bytes)
        //$$ );
        //#else
        ClientPlayNetworking.registerGlobalReceiver(id, (client, handler, buf, sender) ->
            receiver.receive(client, handler, readRemaining(buf))
        );
        //#endif
    }

    public static void registerServer(final Identifier id, final ServerReceiver receiver) {
        //#if MC>=12005
        //$$ final Channel channel = channel(id);
        //$$ ServerPlayNetworking.registerGlobalReceiver(channel.type, (payload, context) ->
        //$$     receiver.receive(context.server(), context.player(), payload.bytes)
        //$$ );
        //#else
        ServerPlayNetworking.registerGlobalReceiver(id, (server, player, handler, buf, sender) ->
            receiver.receive(server, player, readRemaining(buf))
        );
        //#endif
    }

    public static void sendClient(final Identifier id, final byte[] payload) {
        //#if MC>=12005
        //$$ ClientPlayNetworking.send(new RawPayload(channel(id), payload));
        //#else
        ClientPlayNetworking.send(id, new PacketByteBuf(Unpooled.wrappedBuffer(payload)));
        //#endif
    }

    public static void sendServer(
        final ServerPlayerEntity player,
        final Identifier id,
        final byte[] payload
    ) {
        //#if MC>=12005
        //$$ ServerPlayNetworking.send(player, new RawPayload(channel(id), payload));
        //#else
        ServerPlayNetworking.send(player, id, new PacketByteBuf(Unpooled.wrappedBuffer(payload)));
        //#endif
    }

    private static byte[] readRemaining(final PacketByteBuf buf) {
        final byte[] payload = new byte[buf.readableBytes()];
        buf.readBytes(payload);
        return payload;
    }

    //#if MC>=12005
    //$$ private static final Map<Identifier, Channel> CHANNELS = new ConcurrentHashMap<>();
    //$$
    //$$ private static Channel channel(final Identifier id) {
    //$$     return CHANNELS.computeIfAbsent(id, Channel::new);
    //$$ }
    //$$
    //$$ private static final class Channel {
    //$$     private final CustomPayload.Id<RawPayload> type;
    //$$     private final PacketCodec<RegistryByteBuf, RawPayload> codec;
    //$$
    //$$     private Channel(final Identifier id) {
    //$$         type = new CustomPayload.Id<>(id);
    //$$         codec = CustomPayload.codecOf(
    //$$             (payload, buf) -> buf.writeBytes(payload.bytes),
    //$$             buf -> {
    //$$                 final byte[] bytes = new byte[buf.readableBytes()];
    //$$                 buf.readBytes(bytes);
    //$$                 return new RawPayload(this, bytes);
    //$$             }
    //$$         );
    //$$         PayloadTypeRegistry.playC2S().register(type, codec);
    //$$         PayloadTypeRegistry.playS2C().register(type, codec);
    //$$     }
    //$$ }
    //$$
    //$$ private static final class RawPayload implements CustomPayload {
    //$$     private final Channel channel;
    //$$     private final byte[] bytes;
    //$$
    //$$     private RawPayload(final Channel channel, final byte[] bytes) {
    //$$         this.channel = channel;
    //$$         this.bytes = bytes;
    //$$     }
    //$$
    //$$     @Override
    //$$     public Id<RawPayload> getId() {
    //$$         return channel.type;
    //$$     }
    //$$ }
    //#endif
}
