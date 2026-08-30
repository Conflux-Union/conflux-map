package cn.net.rms.confluxmap.terrain;

import cn.net.rms.confluxmap.terrain.protocol.EncodedChunk;
import cn.net.rms.confluxmap.terrain.protocol.TerrainDelta;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Compressed chunk state retained between calculations. */
final class CachedChunk {
    private static final long DELTA_BYTES = 64L;

    private EncodedChunk encoded;
    private final Map<Integer, DeltaState> deltas = new HashMap<>();
    private boolean valid = true;

    CachedChunk(final EncodedChunk encoded) {
        this.encoded = encoded;
    }

    synchronized void replace(final EncodedChunk next) {
        if (next.revision() < encoded.revision()) {
            return;
        }
        encoded = next;
        final Iterator<DeltaState> iterator = deltas.values().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().revision() <= next.revision()) {
                iterator.remove();
            }
        }
    }

    synchronized boolean update(final TerrainDelta delta) {
        final int minY = encoded.minSectionY() * 16;
        final int maxY = (encoded.maxSectionY() + 1) * 16;
        if (delta.localX() < 0 || delta.localX() >= 16
            || delta.localZ() < 0 || delta.localZ() >= 16
            || delta.y() < minY || delta.y() >= maxY
            || delta.revision() < encoded.revision()) {
            return false;
        }
        final int key = ((delta.y() - minY) * 256) + delta.localZ() * 16 + delta.localX();
        final DeltaState previous = deltas.get(key);
        if (previous != null && previous.revision() > delta.revision()) {
            return false;
        }
        deltas.put(key, new DeltaState(delta.revision(), delta.stateId()));
        return true;
    }

    synchronized ChunkVolume decode() throws IOException {
        if (!valid) {
            return null;
        }
        final ChunkVolume volume = EncodedChunkDecoder.decode(encoded);
        final int minY = volume.minY();
        for (final Map.Entry<Integer, DeltaState> entry : deltas.entrySet()) {
            final int index = entry.getKey();
            final int y = minY + index / 256;
            final int column = index % 256;
            volume.update(
                entry.getValue().revision(),
                column & 15,
                y,
                column >> 4,
                entry.getValue().stateId()
            );
        }
        return volume;
    }

    synchronized void invalidate() {
        valid = false;
        deltas.clear();
    }

    synchronized boolean valid() {
        return valid;
    }

    synchronized long revision() {
        long revision = encoded.revision();
        for (final DeltaState delta : deltas.values()) {
            revision = Math.max(revision, delta.revision());
        }
        return revision;
    }

    synchronized long estimatedBytes() {
        return encoded.estimatedBytes() + DELTA_BYTES * deltas.size();
    }

    synchronized int chunkX() {
        return encoded.chunkX();
    }

    synchronized int chunkZ() {
        return encoded.chunkZ();
    }

    private record DeltaState(long revision, int stateId) {
    }
}
