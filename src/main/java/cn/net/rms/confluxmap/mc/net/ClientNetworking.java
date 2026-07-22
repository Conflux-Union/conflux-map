package cn.net.rms.confluxmap.mc.net;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.core.net.ErrorS2C;
import cn.net.rms.confluxmap.core.net.FlatBaselineS2C;
import cn.net.rms.confluxmap.core.net.HelloC2S;
import cn.net.rms.confluxmap.core.net.HelloPolicyS2C;
import cn.net.rms.confluxmap.core.net.MapPatchS2C;
import cn.net.rms.confluxmap.core.net.Message;
import cn.net.rms.confluxmap.core.net.MsgCodec;
import cn.net.rms.confluxmap.core.net.PolicyUpdateS2C;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.ProtoException;
import cn.net.rms.confluxmap.nativepredict.PredictorVersion;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

/**
 * Client-side wiring for the {@code confluxmap:map_sync} companion channel. Owns one global
 * receiver; on every S2C message it decodes the payload and dispatches to {@link CompanionSession}
 * (HELLO_POLICY), to the correction sync loop (MAP_PATCH), or logs
 * (POLICY_UPDATE / ERROR). On {@link ClientPlayConnectionEvents#JOIN} it sends a HELLO_C2S
 * immediately (fabric-api's JOIN fires at the RETURN of {@code onGameJoin} with the channel
 * ready - see the research report); on {@link ClientPlayConnectionEvents#DISCONNECT} it resets
 * the session.
 */
public final class ClientNetworking {
    public static final Identifier CHANNEL = new Identifier(Proto.CHANNEL_ID);

    private final CompanionSession session;
    private volatile MapSyncClient mapSync;

    public ClientNetworking(final CompanionSession session) {
        this.session = session;
    }

    public void register() {
        ClientPlayNetworking.registerGlobalReceiver(CHANNEL, this::onReceive);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> sendHello());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            session.reset();
            final MapSyncClient sync = mapSync;
            if (sync != null) {
                sync.reset();
            }
        });
    }

    public void bindMapSync(final MapSyncClient mapSync) {
        this.mapSync = mapSync;
    }

    private void onReceive(
        final net.minecraft.client.MinecraftClient client,
        final net.minecraft.client.network.ClientPlayNetworkHandler handler,
        final PacketByteBuf buf,
        final net.fabricmc.fabric.api.networking.v1.PacketSender responseSender
    ) {
        final byte[] payload;
        try {
            payload = readPayload(buf);
        } catch (final ProtoException e) {
            ConfluxMapMod.LOGGER.warn("companion: dropping malformed S2C payload ({})", e.getMessage());
            return;
        }
        try {
            final Message msg = MsgCodec.decode(payload);
            if (msg instanceof final HelloPolicyS2C p) {
                session.onPolicy(p);
            } else if (msg instanceof final FlatBaselineS2C f) {
                session.onFlatBaselines(f);
            } else if (msg instanceof final PolicyUpdateS2C u) {
                onPolicyUpdate(u);
            } else if (msg instanceof final MapPatchS2C p) {
                onMapPatch(p, payload.length);
            } else if (msg instanceof final ErrorS2C e) {
                onError(e, payload.length);
            } else {
                ConfluxMapMod.LOGGER.warn(
                    "companion: unexpected S2C {} from server",
                    msg.getClass().getSimpleName()
                );
            }
        } catch (final ProtoException e) {
            ConfluxMapMod.LOGGER.warn("companion: undecodable S2C payload ({} bytes): {}", payload.length, e.getMessage());
        }
    }

    /** Constructs and sends HELLO_C2S; called from JOIN and config-driven re-handshakes. */
    public void sendHello() {
        final HelloC2S hello = new HelloC2S(
            ConfluxMapMod.getVersion(),
            PredictorVersion.full()
        );
        if (sendMessage(hello) < 0) {
            return;
        }
        session.onHelloSent();
        ConfluxMapMod.LOGGER.info(
            "companion: HELLO_C2S sent (modVersion={} predictorVersion={})",
            hello.modVersion(), hello.predictorVersion()
        );
    }

    int sendMessage(final Message msg) {
        final byte[] payload;
        try {
            payload = MsgCodec.encode(msg);
        } catch (final ProtoException e) {
            ConfluxMapMod.LOGGER.error("companion: failed to serialize {}: {}", msg.getClass().getSimpleName(), e.getMessage());
            return -1;
        }
        final PacketByteBuf buf = new PacketByteBuf(Unpooled.wrappedBuffer(payload));
        try {
            ClientPlayNetworking.send(CHANNEL, buf);
            return payload.length;
        } catch (final IllegalStateException e) {
            // Channel not ready (e.g. fired before JOIN completes, or after DISCONNECT). Don't
            // crash the caller - WorldSessionTracker will retry on the next session.
            ConfluxMapMod.LOGGER.debug("companion: send failed for {}: {}", msg.getClass().getSimpleName(), e.getMessage());
            return -1;
        }
    }

    private void onPolicyUpdate(final PolicyUpdateS2C u) {
        final HelloPolicyS2C current = session.policy();
        if (current == null) {
            ConfluxMapMod.LOGGER.warn("companion: POLICY_UPDATE arrived before HELLO_POLICY, ignoring");
            return;
        }
        // Build a fresh HelloPolicyS2C keeping the connection-stable fields (worldId, dims, etc.)
        // and replacing only flags/budgets. This keeps the rest of the client reading one shape.
        final HelloPolicyS2C updated = new HelloPolicyS2C(
            u.flags(), current.worldId(), current.worldgenVersion(), u.budgets(), current.dims()
        );
        session.onPolicy(updated);
    }

    private void onMapPatch(final MapPatchS2C patch, final int payloadBytes) {
        final MapSyncClient sync = mapSync;
        if (sync != null) {
            sync.onPatch(patch, payloadBytes);
        }
        ConfluxMapMod.LOGGER.debug(
            "companion: MAP_PATCH mode={} tileX={} tileZ={} lod={} body={} bytes",
            patch.mode(), patch.tileX(), patch.tileZ(), patch.lod(), patch.body().length
        );
    }

    private void onError(final ErrorS2C err, final int payloadBytes) {
        final MapSyncClient sync = mapSync;
        if (sync != null) {
            sync.onError(payloadBytes);
        }
        ConfluxMapMod.LOGGER.warn("companion: server error code={} detail={}", err.code(), err.detail());
    }

    private static byte[] readPayload(final PacketByteBuf buf) throws ProtoException {
        final int readable = buf.readableBytes();
        if (readable < 1) {
            throw new ProtoException("empty payload");
        }
        if (readable > Proto.MAX_S2C_PAYLOAD) {
            throw new ProtoException("S2C payload " + readable + " above cap " + Proto.MAX_S2C_PAYLOAD);
        }
        final byte[] payload = new byte[readable];
        buf.readBytes(payload);
        return payload;
    }

    /** Convenience for the composition root. */
    public static ClientNetworking install(final ConfluxMapClient root) {
        return new ClientNetworking(root.companionSession());
    }
}
