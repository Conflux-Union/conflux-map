package cn.net.rms.confluxmap.mc.net;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.net.MapPatchS2C;
import cn.net.rms.confluxmap.core.net.MapViewReqC2S;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.ProtoException;
import cn.net.rms.confluxmap.core.predict.CorrectionStore;
import cn.net.rms.confluxmap.core.predict.PredictionTileService;
import cn.net.rms.confluxmap.core.predict.ViewRequestPlanner;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.MinecraftClient;

/** Client-side viewport debounce, request planning, and correction application. */
public final class MapSyncClient {
    private final MinecraftClient client;
    private final CompanionSession companion;
    private final ClientNetworking networking;
    private final CorrectionStore corrections;
    private final PredictionTileService predictionTiles;
    private final ConfluxConfig config;
    private int nextReqId;
    private long stableSince = Long.MIN_VALUE;
    private long lastSent;
    private int lastLod = -1;
    private int lastMinX;
    private int lastMaxX;
    private int lastMinZ;
    private int lastMaxZ;
    private final Map<Long, Long> lastRequestNanos = new HashMap<>();

    public MapSyncClient(
        final MinecraftClient client,
        final CompanionSession companion,
        final ClientNetworking networking,
        final CorrectionStore corrections,
        final PredictionTileService predictionTiles,
        final ConfluxConfig config
    ) {
        this.client = client;
        this.companion = companion;
        this.networking = networking;
        this.corrections = corrections;
        this.predictionTiles = predictionTiles;
        this.config = config;
    }

    public void reportViewport(
        final DimensionId dimension, final int lod, final int minX, final int maxX, final int minZ, final int maxZ
    ) {
        if (!config.predictionNetworkSync || !companion.isActive() || !companion.policy().flags().correctionsEnabled()
            || lod > companion.policy().budgets().maxPatchLod()) {
            return;
        }
        // Integrated servers also speak the companion channel, but their cache must not share a
        // namespace with a remote server that happens to advertise the same fallback world id.
        final String serverNamespace = client.isInSingleplayer() ? "singleplayer" : "multiplayer";
        corrections.setNamespace(serverNamespace, companion.worldIdOverride().orElse("world"));
        final long now = System.currentTimeMillis();
        corrections.flushIfDue(now);
        final boolean changed = lod != lastLod || minX != lastMinX || maxX != lastMaxX || minZ != lastMinZ || maxZ != lastMaxZ;
        if (changed) {
            stableSince = now;
            lastLod = lod;
            lastMinX = minX;
            lastMaxX = maxX;
            lastMinZ = minZ;
            lastMaxZ = maxZ;
            return;
        }
        final long debounce = Math.max(100L, Math.min(2000L, config.predictionDebounceMs));
        final long minInterval = companion.policy().budgets().minReqIntervalMs();
        if (stableSince == Long.MIN_VALUE || now - stableSince < debounce || now - lastSent < minInterval) {
            return;
        }
        final List<ViewRequestPlanner.Tile> tiles = new ArrayList<>();
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                final CorrectionStore.Key key = new CorrectionStore.Key(dimension.toString(), lod, x, z);
                final cn.net.rms.confluxmap.core.predict.CorrectionTile tile = corrections.get(key);
                final byte[] presence = tile.presence();
                boolean empty = true;
                for (final byte value : presence) {
                    empty &= value == 0;
                }
                final Long previous = lastRequestNanos.get(ViewRequestPlanner.key(x, z));
                tiles.add(new ViewRequestPlanner.Tile(x, z, tile.revision(), previous == null ? Long.MIN_VALUE : previous, empty));
            }
        }
        final int centerX = (minX + maxX) / 2;
        final int centerZ = (minZ + maxZ) / 2;
        final List<MapViewReqC2S.TileReq> planned = ViewRequestPlanner.plan(
            new ViewRequestPlanner.Viewport(minX, maxX, minZ, maxZ, centerX, centerZ), tiles,
            Math.min(Proto.MAX_TILES_PER_REQ, companion.policy().budgets().maxTilesPerReq()), now * 1_000_000L,
            60_000_000_000L, 600_000_000_000L
        );
        if (planned.isEmpty()) {
            return;
        }
        final int dimIndex = dimensionIndex(dimension);
        if (dimIndex < 0) {
            return;
        }
        if (networking.sendMessage(new MapViewReqC2S(nextReqId++ & 0x7FFF, dimIndex, lod, planned))) {
            lastSent = now;
            final long requestNanos = now * 1_000_000L;
            for (final MapViewReqC2S.TileReq tile : planned) {
                lastRequestNanos.put(ViewRequestPlanner.key(tile.tileX(), tile.tileZ()), requestNanos);
            }
        }
    }

    public void onPatch(final MapPatchS2C patch) {
        if (patch.mode() == Proto.PATCH_MODE_UNAVAILABLE || patch.mode() == Proto.PATCH_MODE_UNCHANGED) {
            final CorrectionStore.Key key = keyFor(patch);
            if (key != null) {
                corrections.apply(key, patch.tileRevision(), patch.presence(), new PatchCodec.Patch(List.of()));
            }
            return;
        }
        try {
            final PatchCodec.Patch decoded = PatchCodec.decode(patch.body());
            final CorrectionStore.Key key = keyFor(patch);
            if (key != null && predictionTiles.applyCorrection(key, patch.tileRevision(), patch.presence(), decoded)) {
                corrections.flush();
            }
        } catch (final ProtoException e) {
            ConfluxMapMod.LOGGER.warn("companion: malformed MAP_PATCH body ({})", e.getMessage());
        }
    }

    public void reset() {
        lastRequestNanos.clear();
        stableSince = Long.MIN_VALUE;
        lastSent = 0L;
        lastLod = -1;
    }

    private CorrectionStore.Key keyFor(final MapPatchS2C patch) {
        final CompanionSession session = companion;
        if (!session.isActive() || patch.dimIndex() < 0 || patch.dimIndex() >= session.policy().dims().size()) {
            return null;
        }
        return new CorrectionStore.Key(session.policy().dims().get(patch.dimIndex()).dimId(), patch.lod(), patch.tileX(), patch.tileZ());
    }

    private int dimensionIndex(final DimensionId dimension) {
        for (int i = 0; i < companion.policy().dims().size(); i++) {
            if (dimension.toString().equals(companion.policy().dims().get(i).dimId())) {
                return i;
            }
        }
        return -1;
    }
}
