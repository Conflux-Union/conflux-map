package cn.net.rms.confluxmap.mc.portal;

import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.compat.Ids;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.Regs;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.portal.PortalKind;
import cn.net.rms.confluxmap.core.portal.PortalMarker;
import cn.net.rms.confluxmap.core.portal.PortalService;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Client-side detection of activated portal blocks (nether portals, end
 * portals, end gateways) feeding {@link PortalService}. Detection is
 * chunk-granular with a palette prefilter: a section whose palette cannot
 * contain a tracked block is skipped without touching its 4096 cells, so a
 * chunk-load scan costs a handful of palette lookups for ordinary terrain.
 *
 * <p>Three triggers, all on the main thread: a chunk arriving from the server
 * (via {@code PortalScanHandler}), a block update inside a chunk that either
 * introduces a portal block or sits in an already-tracked chunk (same
 * handler), and a one-shot pass over the loaded chunks when a session starts
 * or the setting is switched on mid-session. Block updates never edit
 * markers incrementally - they schedule a full rescan of that one chunk, which
 * keeps anchors and multi-kind chunks correct in both directions (a portal
 * lit by another player appears; a dismantled one disappears).
 */
public final class PortalScanService {
    private final MinecraftClient client;
    private final ConfluxConfig config;
    private final GameBridge gameBridge;
    private final PortalService portals;

    private Map<Block, PortalKind> portalBlocks;
    private boolean pendingInitialScan;

    public PortalScanService(
        final MinecraftClient client,
        final ConfluxConfig config,
        final GameBridge gameBridge,
        final PortalService portals
    ) {
        this.client = client;
        this.config = config;
        this.gameBridge = gameBridge;
        this.portals = portals;
    }

    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ignored -> tick());
    }

    /** Main thread, from the session tracker: rescan loaded chunks once the world is up. */
    public void onSessionChanged(final SessionGuard.Session session) {
        pendingInitialScan = session.active();
    }

    /** The setting was switched on mid-session; catch up with what is already loaded. */
    public void requestLoadedChunkScan() {
        scanLoadedChunks();
    }

    /** Mixin entry: a whole chunk just arrived from the server. */
    public void chunkLoaded(final WorldChunk chunk) {
        if (config.portalMarkersEnabled) {
            scanChunk(chunk);
        }
    }

    /** Mixin entry: one block changed (single update or section-delta batch member). */
    public void blockUpdated(final int x, final int y, final int z, final BlockState state) {
        if (!config.portalMarkersEnabled || client.world == null) {
            return;
        }
        if (portalBlocks().containsKey(state.getBlock())
            || portals.hasMarkersInChunk(gameBridge.session().dimension(), x >> 4, z >> 4)) {
            rescanChunkAt(x >> 4, z >> 4);
        }
    }

    private void tick() {
        if (pendingInitialScan && client.world != null && client.player != null) {
            pendingInitialScan = false;
            if (config.portalMarkersEnabled) {
                scanLoadedChunks();
            }
        }
    }

    private void scanLoadedChunks() {
        final ClientWorld world = client.world;
        if (world == null || client.player == null) {
            return;
        }
        final int radius = MinecraftAccess.viewDistance(client);
        final int playerChunkX = client.player.getBlockX() >> 4;
        final int playerChunkZ = client.player.getBlockZ() >> 4;
        for (int chunkX = playerChunkX - radius; chunkX <= playerChunkX + radius; chunkX++) {
            for (int chunkZ = playerChunkZ - radius; chunkZ <= playerChunkZ + radius; chunkZ++) {
                final WorldChunk chunk = world.getChunkManager()
                    .getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                if (chunk != null) {
                    scanChunk(chunk);
                }
            }
        }
    }

    private void rescanChunkAt(final int chunkX, final int chunkZ) {
        final ClientWorld world = client.world;
        if (world == null) {
            return;
        }
        final WorldChunk chunk = world.getChunkManager()
            .getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (chunk != null) {
            scanChunk(chunk);
        }
    }

    private void scanChunk(final WorldChunk chunk) {
        final DimensionId dimension = gameBridge.session().dimension();
        //#if MC>=260100
        //$$ final int chunkX = chunk.getPos().x();
        //$$ final int chunkZ = chunk.getPos().z();
        //#else
        final int chunkX = chunk.getPos().x;
        final int chunkZ = chunk.getPos().z;
        //#endif
        portals.applyChunkScan(dimension, chunkX, chunkZ, findPortals(chunk, dimension, chunkX, chunkZ));
    }

    /** First tracked block per kind in bottom-up section order; empty when the chunk holds none. */
    private List<PortalMarker> findPortals(
        final WorldChunk chunk,
        final DimensionId dimension,
        final int chunkX,
        final int chunkZ
    ) {
        final Map<Block, PortalKind> tracked = portalBlocks();
        if (tracked.isEmpty()) {
            return List.of();
        }
        final Map<PortalKind, PortalMarker> found = new HashMap<>();
        final ChunkSection[] sections = chunk.getSectionArray();
        for (final ChunkSection section : sections) {
            if (found.size() == tracked.size()) {
                break;
            }
            //#if MC>=11800
            //$$ if (section.isEmpty()) {
            //#else
            if (ChunkSection.isEmpty(section)) {
            //#endif
                continue;
            }
            //#if MC>=11800
            //$$ final PalettedContainer<BlockState> states = section.getBlockStateContainer();
            //#else
            final PalettedContainer<BlockState> states = section.getContainer();
            //#endif
            if (!states.hasAny(state -> tracked.containsKey(state.getBlock()))) {
                continue;
            }
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        final PortalKind kind = tracked.get(states.get(x, y, z).getBlock());
                        if (kind == null || found.containsKey(kind)) {
                            continue;
                        }
                        found.put(kind, new PortalMarker(
                            dimension, chunkX, chunkZ, kind,
                            (chunkX << 4) + x, (chunkZ << 4) + z
                        ));
                        if (found.size() == tracked.size()) {
                            return new ArrayList<>(found.values());
                        }
                    }
                }
            }
        }
        return new ArrayList<>(found.values());
    }

    private Map<Block, PortalKind> portalBlocks() {
        Map<Block, PortalKind> resolved = portalBlocks;
        if (resolved == null) {
            final Map<Block, PortalKind> fresh = new HashMap<>();
            for (final PortalKind kind : PortalKind.values()) {
                Regs.block(Ids.of(kind.blockId())).ifPresent(block -> fresh.put(block, kind));
            }
            portalBlocks = fresh;
            resolved = fresh;
        }
        return resolved;
    }
}
