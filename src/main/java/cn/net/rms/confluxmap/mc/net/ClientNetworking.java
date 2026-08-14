package cn.net.rms.confluxmap.mc.net;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.compat.Ids;
import cn.net.rms.confluxmap.compat.PlayNetworking;
import cn.net.rms.confluxmap.core.net.ErrorS2C;
import cn.net.rms.confluxmap.core.net.FlatBaselineS2C;
import cn.net.rms.confluxmap.core.net.HelloC2S;
import cn.net.rms.confluxmap.core.net.HelloPolicyS2C;
import cn.net.rms.confluxmap.core.net.LoadStateDeltaS2C;
import cn.net.rms.confluxmap.core.net.MapCapabilitiesS2C;
import cn.net.rms.confluxmap.core.net.MapCompatibilityS2C;
import cn.net.rms.confluxmap.core.net.MapInvalidateS2C;
import cn.net.rms.confluxmap.core.net.MapPatchS2C;
import cn.net.rms.confluxmap.core.net.MapRegionInvalidateS2C;
import cn.net.rms.confluxmap.core.net.MapRegionPatchS2C;
import cn.net.rms.confluxmap.core.net.MapSyncProtocol;
import cn.net.rms.confluxmap.core.net.Message;
import cn.net.rms.confluxmap.core.net.PolicyUpdateS2C;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.ProtoException;
import cn.net.rms.confluxmap.core.net.ServerInstanceS2C;
import cn.net.rms.confluxmap.core.net.ServerViewDistanceS2C;
import cn.net.rms.confluxmap.nativepredict.PredictorVersion;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.util.Identifier;

/**
 * Client-side wiring for the {@code confluxmap:map_sync} companion channel. Owns one global
 * receiver; on every S2C message it decodes the payload and dispatches to {@link CompanionSession}
 * (HELLO_POLICY), to the correction sync loop (MAP_PATCH), or logs
 * (POLICY_UPDATE / ERROR). Network callbacks are decoded off-thread, then state changes are
 * marshalled to the client thread. On {@link ClientPlayConnectionEvents#JOIN} it sends a
 * HELLO_C2S immediately (fabric-api's JOIN fires at the RETURN of {@code onGameJoin} with the
 * channel ready - see the research report); on {@link ClientPlayConnectionEvents#DISCONNECT} it
 * resets the session on that same client thread.
 */
public final class ClientNetworking {
    public static final Identifier CHANNEL = Ids.of(Proto.CHANNEL_ID);

    private final CompanionSession session;
    private volatile MapSyncClient mapSync;
    private volatile ChunkLoadStateClient chunkLoadStates;

    public ClientNetworking(final CompanionSession session) {
        this.session = session;
    }

    public void register() {
        PlayNetworking.registerClient(CHANNEL, this::onReceive);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> sendHello());
        ClientPlayConnectionEvents.DISCONNECT.register(this::onDisconnect);
    }

    public void bindMapSync(final MapSyncClient mapSync) {
        this.mapSync = mapSync;
    }

    public void bindChunkLoadStates(final ChunkLoadStateClient chunkLoadStates) {
        this.chunkLoadStates = chunkLoadStates;
    }

    private void onReceive(
        final MinecraftClient client,
        final ClientPlayNetworkHandler handler,
        final byte[] payload
    ) {
        try {
            validatePayload(payload);
        } catch (final ProtoException e) {
            ConfluxMapMod.LOGGER.warn("companion: dropping malformed S2C payload ({})", e.getMessage());
            return;
        }
        final Message msg;
        try {
            msg = session.decodeInbound(payload);
        } catch (final ProtoException e) {
            ConfluxMapMod.LOGGER.warn("companion: undecodable S2C payload ({} bytes): {}", payload.length, e.getMessage());
            return;
        }
        executeForConnection(client, handler, () -> dispatch(msg, payload.length));
    }

    private void dispatch(final Message msg, final int payloadBytes) {
        if (msg instanceof final HelloPolicyS2C p) {
            session.onPolicy(p);
        } else if (msg instanceof MapCompatibilityS2C || msg instanceof MapCapabilitiesS2C) {
            session.onSelection(msg);
        } else if (msg instanceof final ServerViewDistanceS2C viewDistance) {
            session.onServerViewDistance(viewDistance);
        } else if (msg instanceof final ServerInstanceS2C serverInstance) {
            session.onServerInstance(serverInstance);
        } else if (msg instanceof final FlatBaselineS2C f) {
            session.onFlatBaselines(f);
        } else if (msg instanceof final PolicyUpdateS2C u) {
            onPolicyUpdate(u);
        } else if (msg instanceof final MapPatchS2C p) {
            onMapPatch(p, payloadBytes);
        } else if (msg instanceof final MapInvalidateS2C invalidation) {
            final MapSyncClient sync = mapSync;
            if (sync != null) {
                sync.onInvalidation(invalidation);
            }
        } else if (msg instanceof final MapRegionPatchS2C patch) {
            final MapSyncClient sync = mapSync;
            if (sync != null) {
                sync.onRegionPatch(patch, payloadBytes);
            }
        } else if (msg instanceof final MapRegionInvalidateS2C invalidation) {
            final MapSyncClient sync = mapSync;
            if (sync != null) {
                sync.onRegionInvalidation(invalidation);
            }
        } else if (msg instanceof final LoadStateDeltaS2C delta) {
            final ChunkLoadStateClient loadStates = chunkLoadStates;
            if (loadStates != null) {
                loadStates.onDelta(delta);
            }
        } else if (msg instanceof final ErrorS2C e) {
            onError(e, payloadBytes);
        } else {
            ConfluxMapMod.LOGGER.warn(
                "companion: unexpected S2C {} from server",
                msg.getClass().getSimpleName()
            );
        }
    }

    private void onDisconnect(
        final ClientPlayNetworkHandler handler,
        final MinecraftClient client
    ) {
        client.execute(() -> {
            final ClientPlayNetworkHandler current = client.getNetworkHandler();
            if (current != null && current != handler) {
                return;
            }
            session.reset();
            final MapSyncClient sync = mapSync;
            if (sync != null) {
                sync.reset();
            }
            final ChunkLoadStateClient loadStates = chunkLoadStates;
            if (loadStates != null) {
                loadStates.reset();
            }
        });
    }

    private static void executeForConnection(
        final MinecraftClient client,
        final ClientPlayNetworkHandler handler,
        final Runnable task
    ) {
        client.execute(() -> {
            if (client.getNetworkHandler() == handler) {
                task.run();
            }
        });
    }

    /** Constructs and sends HELLO_C2S; called from JOIN and config-driven re-handshakes. */
    public void sendHello() {
        final HelloC2S hello = MapSyncProtocol.clientHello(
            ConfluxMapMod.getVersion(), PredictorVersion.full()
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
            payload = session.encodeOutbound(msg);
        } catch (final ProtoException e) {
            ConfluxMapMod.LOGGER.error("companion: failed to serialize {}: {}", msg.getClass().getSimpleName(), e.getMessage());
            return -1;
        }
        try {
            PlayNetworking.sendClient(CHANNEL, payload);
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
        final ChunkLoadStateClient loadStates = chunkLoadStates;
        if (loadStates != null && !updated.flags().chunkLoadStateEnabled()) {
            loadStates.reset();
        }
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

    private static void validatePayload(final byte[] payload) throws ProtoException {
        final int readable = payload.length;
        if (readable < 1) {
            throw new ProtoException("empty payload");
        }
        if (readable > Proto.MAX_S2C_PAYLOAD) {
            throw new ProtoException("S2C payload " + readable + " above cap " + Proto.MAX_S2C_PAYLOAD);
        }
    }

    /** Convenience for the composition root. */
    public static ClientNetworking install(final ConfluxMapClient root) {
        return new ClientNetworking(root.companionSession());
    }
}
