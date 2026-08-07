package cn.net.rms.confluxmap.core.multiworld;

import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A compact, persistence-safe 3x3 terrain sample. It deliberately retains only stable terrain
 * features already present in map snapshots; entity, light, and rendered-color changes are not
 * identity evidence.
 */
public final class ClientWorldTerrainFingerprint {
    private static final int[] SAMPLE_OFFSETS = {0, 5, 10, 15};
    private static final int EXPECTED_CHUNKS = 9;
    private static final int SAMPLES_PER_CHUNK = SAMPLE_OFFSETS.length * SAMPLE_OFFSETS.length;

    private List<Chunk> chunks;
    /** Null for fingerprints persisted before center coordinates were introduced. */
    private Integer centerChunkX;
    private Integer centerChunkZ;

    ClientWorldTerrainFingerprint() {
        // Gson
    }

    private ClientWorldTerrainFingerprint(final List<Chunk> chunks) {
        this(chunks, null, null);
    }

    private ClientWorldTerrainFingerprint(
        final List<Chunk> chunks,
        final Integer centerChunkX,
        final Integer centerChunkZ
    ) {
        this.chunks = List.copyOf(chunks);
        this.centerChunkX = centerChunkX;
        this.centerChunkZ = centerChunkZ;
    }

    public static ClientWorldTerrainFingerprint from(
        final List<ChunkSnapshot> snapshots,
        final int centerChunkX,
        final int centerChunkZ
    ) {
        final List<Chunk> samples = new ArrayList<>();
        for (final ChunkSnapshot snapshot : snapshots) {
            final int offsetX = snapshot.chunkX - centerChunkX;
            final int offsetZ = snapshot.chunkZ - centerChunkZ;
            if (Math.abs(offsetX) > 1 || Math.abs(offsetZ) > 1) {
                continue;
            }
            samples.add(Chunk.from(snapshot, offsetX, offsetZ));
        }
        samples.sort(Comparator.comparingInt((Chunk chunk) -> chunk.offsetZ)
            .thenComparingInt(chunk -> chunk.offsetX));
        return new ClientWorldTerrainFingerprint(samples, centerChunkX, centerChunkZ);
    }

    public boolean complete() {
        if (chunks == null || chunks.size() != EXPECTED_CHUNKS) {
            return false;
        }
        final Set<Long> positions = new HashSet<>();
        for (final Chunk chunk : chunks) {
            if (chunk == null || !chunk.complete()
                || Math.abs(chunk.offsetX) > 1 || Math.abs(chunk.offsetZ) > 1
                || !positions.add(chunk.positionKey())) {
                return false;
            }
        }
        return positions.size() == EXPECTED_CHUNKS;
    }

    public boolean hasCenter() {
        return centerChunkX != null && centerChunkZ != null;
    }

    public int centerChunkX() {
        if (!hasCenter()) {
            throw new IllegalStateException("terrain fingerprint center is unavailable");
        }
        return centerChunkX;
    }

    public int centerChunkZ() {
        if (!hasCenter()) {
            throw new IllegalStateException("terrain fingerprint center is unavailable");
        }
        return centerChunkZ;
    }

    public boolean sameCenter(final ClientWorldTerrainFingerprint other) {
        return other != null && hasCenter() && other.hasCenter()
            && centerChunkX.equals(other.centerChunkX)
            && centerChunkZ.equals(other.centerChunkZ);
    }

    /** Exact sampled terrain evidence, independent of its persisted center coordinate. */
    boolean sameEvidence(final ClientWorldTerrainFingerprint other) {
        if (other == null || !complete() || !other.complete()) {
            return false;
        }
        for (final Chunk chunk : chunks) {
            final Chunk candidate = other.find(chunk.offsetX, chunk.offsetZ);
            if (candidate == null || !chunk.sameEvidence(candidate)) {
                return false;
            }
        }
        return true;
    }

    public int capturedChunks() {
        return chunks == null ? 0 : chunks.size();
    }

    public Match match(final ClientWorldTerrainFingerprint other) {
        if (!complete() || other == null || !other.complete()) {
            return Match.unavailable();
        }
        int comparableChunks = 0;
        double total = 0.0D;
        for (final Chunk chunk : chunks) {
            final Chunk candidate = other.find(chunk.offsetX, chunk.offsetZ);
            if (candidate == null) {
                continue;
            }
            final double score = chunk.similarity(candidate);
            if (score >= 0.0D) {
                comparableChunks++;
                total += score;
            }
        }
        return comparableChunks == EXPECTED_CHUNKS
            ? new Match(total / comparableChunks, comparableChunks, true)
            : Match.unavailable();
    }

    void normalize() {
        final List<Chunk> normalized = new ArrayList<>();
        if (chunks != null) {
            for (final Chunk chunk : chunks) {
                if (chunk != null) {
                    chunk.normalize();
                    if (Math.abs(chunk.offsetX) <= 1 && Math.abs(chunk.offsetZ) <= 1
                        && normalized.stream().noneMatch(existing -> existing.samePosition(chunk))) {
                        normalized.add(chunk);
                    }
                }
            }
        }
        normalized.sort(Comparator.comparingInt((Chunk chunk) -> chunk.offsetZ)
            .thenComparingInt(chunk -> chunk.offsetX));
        chunks = normalized;
    }

    ClientWorldTerrainFingerprint copy() {
        final List<Chunk> copied = new ArrayList<>();
        if (chunks != null) {
            for (final Chunk chunk : chunks) {
                if (chunk != null) {
                    copied.add(chunk.copy());
                }
            }
        }
        return new ClientWorldTerrainFingerprint(copied, centerChunkX, centerChunkZ);
    }

    public record Match(double score, int comparableChunks, boolean available) {
        static Match unavailable() {
            return new Match(0.0D, 0, false);
        }
    }

    private Chunk find(final int offsetX, final int offsetZ) {
        for (final Chunk chunk : chunks) {
            if (chunk.offsetX == offsetX && chunk.offsetZ == offsetZ) {
                return chunk;
            }
        }
        return null;
    }

    private static final class Chunk {
        private int offsetX;
        private int offsetZ;
        private List<Column> columns;

        private Chunk() {
            // Gson
        }

        private Chunk(final int offsetX, final int offsetZ, final List<Column> columns) {
            this.offsetX = offsetX;
            this.offsetZ = offsetZ;
            this.columns = List.copyOf(columns);
        }

        static Chunk from(final ChunkSnapshot snapshot, final int offsetX, final int offsetZ) {
            final List<Column> columns = new ArrayList<>(SAMPLES_PER_CHUNK);
            for (final int z : SAMPLE_OFFSETS) {
                for (final int x : SAMPLE_OFFSETS) {
                    final int index = z * 16 + x;
                    columns.add(new Column(
                        snapshot.surfaceY[index],
                        snapshot.biomeId[index],
                        snapshot.kind[index],
                        snapshot.fluidDepth[index]
                    ));
                }
            }
            return new Chunk(offsetX, offsetZ, columns);
        }

        boolean complete() {
            return columns != null && columns.size() == SAMPLES_PER_CHUNK;
        }

        boolean samePosition(final Chunk other) {
            return offsetX == other.offsetX && offsetZ == other.offsetZ;
        }

        long positionKey() {
            return ((long) offsetX << 32) ^ (offsetZ & 0xFFFFFFFFL);
        }

        double similarity(final Chunk other) {
            if (!complete() || !other.complete()) {
                return -1.0D;
            }
            double total = 0.0D;
            for (int index = 0; index < SAMPLES_PER_CHUNK; index++) {
                total += columns.get(index).similarity(other.columns.get(index));
            }
            return total / SAMPLES_PER_CHUNK;
        }

        boolean sameEvidence(final Chunk other) {
            if (!complete() || other == null || !other.complete()) {
                return false;
            }
            for (int index = 0; index < SAMPLES_PER_CHUNK; index++) {
                if (!columns.get(index).sameEvidence(other.columns.get(index))) {
                    return false;
                }
            }
            return true;
        }

        void normalize() {
            if (columns == null) {
                columns = List.of();
                return;
            }
            final List<Column> normalized = new ArrayList<>();
            for (final Column column : columns) {
                if (column != null) {
                    column.normalize();
                    normalized.add(column);
                }
            }
            columns = normalized;
        }

        Chunk copy() {
            final List<Column> copied = new ArrayList<>();
            if (columns != null) {
                for (final Column column : columns) {
                    if (column != null) {
                        copied.add(column.copy());
                    }
                }
            }
            return new Chunk(offsetX, offsetZ, copied);
        }
    }

    private static final class Column {
        private short surfaceY;
        private String biomeId;
        private byte kind;
        private byte fluidDepth;

        private Column() {
            // Gson
        }

        private Column(final short surfaceY, final String biomeId, final byte kind, final byte fluidDepth) {
            this.surfaceY = surfaceY;
            this.biomeId = biomeId;
            this.kind = kind;
            this.fluidDepth = fluidDepth;
        }

        double similarity(final Column other) {
            if (surfaceY == ChunkSnapshot.NO_SURFACE || other.surfaceY == ChunkSnapshot.NO_SURFACE) {
                return surfaceY == other.surfaceY ? 1.0D : 0.0D;
            }
            double score = 0.0D;
            final int heightDifference = Math.abs(surfaceY - other.surfaceY);
            if (heightDifference <= 1) {
                score += 0.45D;
            } else if (heightDifference <= 3) {
                score += 0.25D;
            }
            if (Objects.equals(biomeId, other.biomeId)) {
                score += 0.30D;
            }
            if (kind == other.kind) {
                score += 0.15D;
            }
            final int fluidDifference = Math.abs(Byte.toUnsignedInt(fluidDepth) - Byte.toUnsignedInt(other.fluidDepth));
            if (fluidDifference == 0) {
                score += 0.10D;
            } else if (fluidDifference == 1) {
                score += 0.05D;
            }
            return score;
        }

        boolean sameEvidence(final Column other) {
            return other != null && surfaceY == other.surfaceY
                && Objects.equals(biomeId, other.biomeId)
                && kind == other.kind
                && fluidDepth == other.fluidDepth;
        }

        void normalize() {
            biomeId = biomeId == null || biomeId.isBlank() ? "" : biomeId;
        }

        Column copy() {
            return new Column(surfaceY, biomeId, kind, fluidDepth);
        }
    }
}
