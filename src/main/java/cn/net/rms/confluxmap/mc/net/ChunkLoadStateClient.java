package cn.net.rms.confluxmap.mc.net;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.net.ChunkLoadStateSnapshot;
import cn.net.rms.confluxmap.core.net.LoadStateDeltaS2C;
import cn.net.rms.confluxmap.core.net.LoadStateSubscribeC2S;
import cn.net.rms.confluxmap.core.net.Message;
import cn.net.rms.confluxmap.core.net.Proto;

/** Client subscription lifecycle for the fullscreen server chunk-load-state plane. */
public final class ChunkLoadStateClient {
    @FunctionalInterface
    interface Sender {
        int send(Message message);
    }

    private final CompanionSession companion;
    private final Sender sender;
    private final ChunkLoadStateSnapshot snapshot = new ChunkLoadStateSnapshot();
    private int nextSubscriptionId;
    private int requestedDimIndex = -1;
    private int requestedMinX;
    private int requestedMaxX;
    private int requestedMinZ;
    private int requestedMaxZ;

    public ChunkLoadStateClient(final CompanionSession companion, final ClientNetworking networking) {
        this(companion, networking::sendMessage);
    }

    ChunkLoadStateClient(final CompanionSession companion, final Sender sender) {
        this.companion = companion;
        this.sender = sender;
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
        snapshot.apply(delta);
    }

    public void deactivate() {
        if (snapshot.active()) {
            sender.send(LoadStateSubscribeC2S.cancel(snapshot.subscriptionId()));
        }
        reset();
    }

    public void reset() {
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
