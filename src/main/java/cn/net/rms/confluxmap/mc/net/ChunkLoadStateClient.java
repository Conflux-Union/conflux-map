package cn.net.rms.confluxmap.mc.net;

import cn.net.rms.confluxmap.core.export.MapExportLoadState;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.net.ChunkLoadStateSnapshot;
import cn.net.rms.confluxmap.core.net.LoadStateDeltaS2C;
import cn.net.rms.confluxmap.core.net.LoadStateSubscribeC2S;
import cn.net.rms.confluxmap.core.net.Message;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.util.TileMath;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.MinecraftClient;

/** Client subscription lifecycle for the fullscreen server chunk-load-state plane. */
public final class ChunkLoadStateClient {
    @FunctionalInterface
    interface Sender {
        int send(Message message);
    }

    private final CompanionSession companion;
    private final Sender sender;
    private final Executor clientThread;
    private final ChunkLoadStateSnapshot snapshot = new ChunkLoadStateSnapshot();
    private int nextSubscriptionId;
    private int requestedDimIndex = -1;
    private int requestedMinX;
    private int requestedMaxX;
    private int requestedMinZ;
    private int requestedMaxZ;
    private CompletableFuture<MapExportLoadState> exportCompletion;

    public ChunkLoadStateClient(final CompanionSession companion, final ClientNetworking networking) {
        this(
            companion,
            networking::sendMessage,
            runnable -> MinecraftClient.getInstance().execute(runnable)
        );
    }

    ChunkLoadStateClient(final CompanionSession companion, final Sender sender) {
        this(companion, sender, Runnable::run);
    }

    ChunkLoadStateClient(
        final CompanionSession companion,
        final Sender sender,
        final Executor clientThread
    ) {
        this.companion = companion;
        this.sender = sender;
        this.clientThread = clientThread;
    }

    public boolean available() {
        return companion.isActive()
            && companion.policy() != null
            && companion.policy().flags().chunkLoadStateEnabled();
    }

    /** Returns true only when a replacement subscription was sent. */
    public boolean reportViewport(
        final DimensionId dimension,
        final int minChunkX,
        final int maxChunkX,
        final int minChunkZ,
        final int maxChunkZ
    ) {
        if (exportCompletion != null) {
            return false;
        }
        if (!available()) {
            reset();
            return false;
        }
        final int dimIndex = dimensionIndex(dimension);
        if (dimIndex < 0 || minChunkX > maxChunkX || minChunkZ > maxChunkZ) {
            return false;
        }
        if (snapshot.active()
            && requestedDimIndex == dimIndex
            && minChunkX >= requestedMinX && maxChunkX <= requestedMaxX
            && minChunkZ >= requestedMinZ && maxChunkZ <= requestedMaxZ) {
            return false;
        }
        final int[] xBounds = expandedBounds(minChunkX, maxChunkX);
        final int[] zBounds = expandedBounds(minChunkZ, maxChunkZ);
        if (snapshot.active()
            && requestedDimIndex == dimIndex
            && xBounds[0] == requestedMinX && xBounds[1] == requestedMaxX
            && zBounds[0] == requestedMinZ && zBounds[1] == requestedMaxZ) {
            return false;
        }
        final int subscriptionId = nextSubscriptionId++;
        final LoadStateSubscribeC2S request = new LoadStateSubscribeC2S(
            subscriptionId,
            dimIndex,
            true,
            xBounds[0],
            zBounds[0],
            xBounds[1],
            zBounds[1]
        );
        if (sender.send(request) < 0) {
            return false;
        }
        requestedDimIndex = dimIndex;
        requestedMinX = xBounds[0];
        requestedMaxX = xBounds[1];
        requestedMinZ = zBounds[0];
        requestedMaxZ = zBounds[1];
        snapshot.begin(subscriptionId, dimIndex);
        return true;
    }

    public void onDelta(final LoadStateDeltaS2C delta) {
        if (!snapshot.apply(delta)) {
            return;
        }
        final CompletableFuture<MapExportLoadState> completion = exportCompletion;
        if (completion != null && delta.complete()) {
            exportCompletion = null;
            final MapExportLoadState page = new MapExportLoadState(snapshot.entries());
            sender.send(LoadStateSubscribeC2S.cancel(snapshot.subscriptionId()));
            snapshot.reset();
            requestedDimIndex = -1;
            completion.complete(page);
        }
    }

    /**
     * Requests the exact chunk rectangle covered by one export tile. Export rasterization is
     * serialized, so this pages arbitrarily large ranges without retaining every server entry.
     */
    public CompletableFuture<MapExportLoadState> requestExportTile(final TileKey tile) {
        final CompletableFuture<MapExportLoadState> completion = new CompletableFuture<>();
        clientThread.execute(() -> beginExportTile(tile, completion));
        completion.orTimeout(30L, TimeUnit.SECONDS).whenComplete((ignored, error) -> {
            if (error != null) {
                clientThread.execute(() -> cancelExport(completion));
            }
        });
        return completion;
    }

    private void beginExportTile(
        final TileKey tile,
        final CompletableFuture<MapExportLoadState> completion
    ) {
        if (completion.isDone()) {
            return;
        }
        if (!available()) {
            completion.completeExceptionally(new IllegalStateException("Chunk load state is unavailable"));
            return;
        }
        if (exportCompletion != null) {
            completion.completeExceptionally(new IllegalStateException("Another chunk load page is active"));
            return;
        }
        final int dimIndex = dimensionIndex(tile.dimension());
        if (dimIndex < 0) {
            completion.completeExceptionally(new IllegalArgumentException("Unsupported export dimension"));
            return;
        }
        final long tileSize = TileMath.blocksPerTile(tile.lod());
        final long originX = (long) tile.tileX() * tileSize;
        final long originZ = (long) tile.tileZ() * tileSize;
        final int minChunkX = (int) Math.floorDiv(Math.max(Integer.MIN_VALUE, originX), 16L);
        final int minChunkZ = (int) Math.floorDiv(Math.max(Integer.MIN_VALUE, originZ), 16L);
        final int maxChunkX = (int) Math.floorDiv(
            Math.min(Integer.MAX_VALUE, originX + tileSize - 1L), 16L
        );
        final int maxChunkZ = (int) Math.floorDiv(
            Math.min(Integer.MAX_VALUE, originZ + tileSize - 1L), 16L
        );
        final int subscriptionId = nextSubscriptionId++;
        final LoadStateSubscribeC2S request = new LoadStateSubscribeC2S(
            subscriptionId,
            dimIndex,
            true,
            minChunkX,
            minChunkZ,
            maxChunkX,
            maxChunkZ
        );
        if (sender.send(request) < 0) {
            completion.completeExceptionally(new IllegalStateException("Unable to request chunk load state"));
            return;
        }
        requestedDimIndex = dimIndex;
        requestedMinX = minChunkX;
        requestedMaxX = maxChunkX;
        requestedMinZ = minChunkZ;
        requestedMaxZ = maxChunkZ;
        snapshot.begin(subscriptionId, dimIndex);
        exportCompletion = completion;
    }

    public void deactivate() {
        if (snapshot.active()) {
            sender.send(LoadStateSubscribeC2S.cancel(snapshot.subscriptionId()));
        }
        final CompletableFuture<MapExportLoadState> completion = exportCompletion;
        exportCompletion = null;
        if (completion != null) {
            completion.completeExceptionally(new CancellationException("Chunk load export cancelled"));
        }
        reset();
    }

    public void reset() {
        final CompletableFuture<MapExportLoadState> completion = exportCompletion;
        exportCompletion = null;
        if (completion != null) {
            completion.completeExceptionally(new CancellationException("Chunk load state reset"));
        }
        snapshot.reset();
        requestedDimIndex = -1;
    }

    private void cancelExport(final CompletableFuture<MapExportLoadState> completion) {
        if (exportCompletion != completion) {
            return;
        }
        if (snapshot.active()) {
            sender.send(LoadStateSubscribeC2S.cancel(snapshot.subscriptionId()));
        }
        exportCompletion = null;
        snapshot.reset();
        requestedDimIndex = -1;
    }

    public ChunkLoadStateSnapshot snapshot() {
        return snapshot;
    }

    private int dimensionIndex(final DimensionId dimension) {
        for (int i = 0; i < companion.policy().dims().size(); i++) {
            if (dimension.toString().equals(companion.policy().dims().get(i).dimId())) {
                return i;
            }
        }
        return -1;
    }

    private static int[] expandedBounds(final int min, final int max) {
        final long visibleSpan = (long) max - min + 1L;
        final long desiredMargin = Math.max(2L, visibleSpan / 10L);
        final long desiredSpan = Math.min(
            Proto.MAX_LOAD_STATE_SPAN,
            Math.max(1L, visibleSpan + desiredMargin * 2L)
        );
        final long center = ((long) min + max) / 2L;
        long expandedMin = center - desiredSpan / 2L;
        long expandedMax = expandedMin + desiredSpan - 1L;
        if (expandedMin < Integer.MIN_VALUE) {
            expandedMin = Integer.MIN_VALUE;
            expandedMax = expandedMin + desiredSpan - 1L;
        } else if (expandedMax > Integer.MAX_VALUE) {
            expandedMax = Integer.MAX_VALUE;
            expandedMin = expandedMax - desiredSpan + 1L;
        }
        return new int[] {(int) expandedMin, (int) expandedMax};
    }
}
