package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.core.net.FlatBaselineS2C;
import cn.net.rms.confluxmap.core.net.HelloC2S;
import cn.net.rms.confluxmap.core.net.HelloPolicyS2C;
import cn.net.rms.confluxmap.core.net.ErrorS2C;
import cn.net.rms.confluxmap.core.net.MapViewReqC2S;
import cn.net.rms.confluxmap.core.net.Message;
import cn.net.rms.confluxmap.core.net.MsgCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.ProtoException;
import cn.net.rms.confluxmap.core.predict.WorldPreset;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.World;

/**
 * Server-side channel handler for {@link Proto#CHANNEL_ID}. Owns the receiver registrations and
 * the per-player state map; defers everything else to {@link ConfluxMapCompanion}.
 *
 * <p>HELLO_C2S is answered with HELLO_POLICY immediately. MAP_VIEW_REQ is registered so the
 * channel is wired, but its handler just logs-and-drops each packet in S3 - the patch-serving
 * implementation lands in S4 ({@code PatchBuilder}) and the request-planning client side in S5.
 */
public final class ServerNetworking {
    public static final Identifier CHANNEL = new Identifier(Proto.CHANNEL_ID);

    private final ConfluxMapCompanion companion;
    private final ConcurrentMap<UUID, Integer> malformedStrikes = new ConcurrentHashMap<>();
    private final Set<UUID> mutedPlayers = ConcurrentHashMap.newKeySet();
    private boolean registered;

    public ServerNetworking(final ConfluxMapCompanion companion) {
        this.companion = companion;
    }

    public synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        ServerPlayNetworking.registerGlobalReceiver(CHANNEL, this::onReceive);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            final UUID uuid = handler.getPlayer().getUuid();
            malformedStrikes.remove(uuid);
            mutedPlayers.remove(uuid);
            companion.summaries().remove(uuid);
        });
    }

    private void onReceive(
        final MinecraftServer server,
        final ServerPlayerEntity player,
        final net.minecraft.server.network.ServerPlayNetworkHandler handler,
        final PacketByteBuf buf,
        final net.fabricmc.fabric.api.networking.v1.PacketSender responseSender
    ) {
        if (!companion.isEnabled()) {
            return;
        }
        if (mutedPlayers.contains(player.getUuid())) {
            return;
        }
        final byte[] payload;
        try {
            payload = readPayload(buf);
        } catch (final ProtoException e) {
            ConfluxMapMod.LOGGER.warn("companion: dropping malformed payload from {} ({})", player.getEntityName(), e.getMessage());
            recordMalformed(player);
            return;
        }
        try {
            final Message msg = MsgCodec.decode(payload);
            if (msg instanceof final HelloC2S hello) {
                handleHello(server, player, hello);
            } else if (msg instanceof final MapViewReqC2S req) {
                handleMapViewReq(server, player, req);
            } else {
                ConfluxMapMod.LOGGER.warn(
                    "companion: unexpected {} from {} (server-side handlers expect C2S only)",
                    msg.getClass().getSimpleName(), player.getEntityName()
                );
            }
        } catch (final ProtoException e) {
            ConfluxMapMod.LOGGER.warn("companion: undecodable {}-byte payload from {} ({})",
                payload.length, player.getEntityName(), e.getMessage());
            recordMalformed(player);
        }
    }

    private void recordMalformed(final ServerPlayerEntity player) {
        final int strikes = malformedStrikes.merge(player.getUuid(), 1, Integer::sum);
        send(player, new ErrorS2C(ErrorS2C.ERR_MALFORMED_REQUEST, "malformed companion payload (strike " + strikes + ")"));
        if (strikes >= 3) {
            mutedPlayers.add(player.getUuid());
        }
    }

    private void handleHello(final MinecraftServer server, final ServerPlayerEntity player, final HelloC2S hello) {
        if (!companion.isEnabled()) {
            return;
        }
        // FLAT_BASELINE goes out first: the client activates its session on HELLO_POLICY, so the
        // flat surfaces must already be stored by then. Pre-minor-2 clients log and ignore it.
        final List<FlatBaselineS2C.Entry> flatEntries = buildFlatBaselines(server);
        if (!flatEntries.isEmpty()) {
            send(player, new FlatBaselineS2C(flatEntries));
        }
        final HelloPolicyS2C policy = buildPolicy(server);
        send(player, policy);
        ConfluxMapMod.LOGGER.info(
            "companion: replied HELLO_POLICY to {} (modVersion={} predictorVersion={} worldId={} seedGranted={})",
            player.getEntityName(), hello.modVersion(), hello.predictorVersion(),
            policy.worldId(), policy.flags().seedGranted()
        );
    }

    private void handleMapViewReq(final MinecraftServer server, final ServerPlayerEntity player, final MapViewReqC2S req) {
        if (!companion.config().shareCorrections) {
            send(player, new ErrorS2C(ErrorS2C.ERR_COMPANION_DISABLED, "map corrections are disabled"));
            return;
        }
        companion.summaries().request(server, player, req, msg -> send(player, msg));
    }

    private HelloPolicyS2C buildPolicy(final MinecraftServer server) {
        final ServerConfig cfg = companion.config();
        final boolean shareSeed = cfg.enabled && cfg.shareSeed;
        final boolean shareCorrections = cfg.enabled && cfg.shareCorrections;
        // The v1 frame reserves structure entries, but RegionSummaryService does not emit them yet.
        final boolean shareStructures = false;
        final HelloPolicyS2C.Flags flags = new HelloPolicyS2C.Flags(shareSeed, shareCorrections, shareStructures);
        final UUID worldId = companion.worldIds().get(server);
        final HelloPolicyS2C.Budgets budgets = new HelloPolicyS2C.Budgets(
            cfg.maxBytesPerSecondPerPlayer,
            cfg.maxTilesPerRequest,
            cfg.minRequestIntervalMs,
            cfg.maxPatchLod
        );
        final List<HelloPolicyS2C.DimDescriptor> dims = buildDimDescriptors(server, shareSeed);
        return new HelloPolicyS2C(flags, worldId.toString(), WORLDGEN_VERSION, budgets, dims);
    }

    private static List<HelloPolicyS2C.DimDescriptor> buildDimDescriptors(final MinecraftServer server, final boolean shareSeed) {
        // Vanilla seed is identical across dimensions (see research R6). Read once from the overworld.
        final long seed = server.getOverworld().getSeed();
        final List<HelloPolicyS2C.DimDescriptor> dims = new ArrayList<>(2);
        for (final ServerWorld sw : server.getWorlds()) {
            final RegistryKey<World> key = sw.getRegistryKey();
            final String dimId = key.getValue().toString();
            final String dimType = key.getValue().getPath();
            final WorldPreset preset = WorldPresetDetector.detect(sw);
            // predictable=false also withholds the seed on pre-preset clients (their seedFor
            // checks it), so superflat/custom dims degrade correctly across versions.
            final boolean predictable = isPredictable(key) && preset.predictable();
            // The server always knows the seed; we just don't always share it.
            final boolean hasSeed = shareSeed;
            final long seedToSend = shareSeed ? seed : 0L;
            dims.add(new HelloPolicyS2C.DimDescriptor(dimId, dimType, predictable, hasSeed, seedToSend, preset));
        }
        return dims;
    }

    private static boolean isPredictable(final RegistryKey<World> key) {
        return World.OVERWORLD.equals(key) || World.END.equals(key);
    }

    /** One entry per superflat dimension, indexed like {@link #buildDimDescriptors}'s list. */
    private static List<FlatBaselineS2C.Entry> buildFlatBaselines(final MinecraftServer server) {
        final List<FlatBaselineS2C.Entry> entries = new ArrayList<>(1);
        int dimIndex = 0;
        for (final ServerWorld sw : server.getWorlds()) {
            if (WorldPresetDetector.detect(sw) == WorldPreset.FLAT) {
                final int index = dimIndex;
                FlatWorldBaseline.of(sw).ifPresent(
                    baseline -> entries.add(new FlatBaselineS2C.Entry(index, baseline))
                );
            }
            dimIndex++;
        }
        return entries;
    }

    private static void send(final ServerPlayerEntity player, final Message msg) {
        final byte[] payload;
        try {
            payload = MsgCodec.encode(msg);
        } catch (final ProtoException e) {
            ConfluxMapMod.LOGGER.error("companion: failed to serialize {}: {}", msg.getClass().getSimpleName(), e.getMessage());
            return;
        }
        final PacketByteBuf buf = new PacketByteBuf(Unpooled.wrappedBuffer(payload));
        ServerPlayNetworking.send(player, CHANNEL, buf);
    }

    private static byte[] readPayload(final PacketByteBuf buf) throws ProtoException {
        final int readable = buf.readableBytes();
        if (readable < 1) {
            throw new ProtoException("empty payload");
        }
        if (readable > Proto.MAX_C2S_PAYLOAD) {
            throw new ProtoException("C2S payload " + readable + " above cap " + Proto.MAX_C2S_PAYLOAD);
        }
        final byte[] payload = new byte[readable];
        buf.readBytes(payload);
        return payload;
    }

    /** Vanilla worldgen version this server jar speaks; the client maps it to a cubiomes {@code MCVersion}. */
    private static final String WORLDGEN_VERSION = "1.17";
}
