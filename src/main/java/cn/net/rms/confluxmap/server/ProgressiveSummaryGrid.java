package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.core.util.TileMath;

/**
 * Fixed-size sampled summary plane for a progressively scanned coarse tile.
 * It retains only the columns that the tile can publish, never all source regions.
 */
final class ProgressiveSummaryGrid implements SummaryView {
    private static final int PIXELS = 256;

    private final int lod;
    private final int tileX;
    private final int tileZ;
    private final int scale;
    private final int samplesPerChunk;
    private final int originChunkX;
    private final int originChunkZ;
    private final SummaryCodec.Column[] columns = new SummaryCodec.Column[PIXELS * PIXELS];
    private final long[] pixelRevisions = new long[PIXELS * PIXELS];
    private final boolean[] known = new boolean[PIXELS * PIXELS];
    private final boolean[] generated = new boolean[PIXELS * PIXELS];
    private final byte[] presence = new byte[Proto.PATCH_PRESENCE_BYTES];
    private long maxRevision;

    ProgressiveSummaryGrid(final int lod, final int tileX, final int tileZ) {
        if (lod < 0 || lod > TileMath.MAX_LOD) {
            throw new IllegalArgumentException("unsupported summary LOD " + lod);
        }
        this.lod = lod;
        this.tileX = tileX;
        this.tileZ = tileZ;
        this.scale = 1 << lod;
        this.samplesPerChunk = Math.max(1, 16 / scale);
        this.originChunkX = tileX * (16 << lod);
        this.originChunkZ = tileZ * (16 << lod);
    }

    void acceptChunk(final int chunkX, final int chunkZ, final SummaryCodec.Chunk chunk) {
        final int localChunkX = chunkX - originChunkX;
        final int localChunkZ = chunkZ - originChunkZ;
        final int chunksPerSide = 16 << lod;
        if (localChunkX < 0 || localChunkX >= chunksPerSide
            || localChunkZ < 0 || localChunkZ >= chunksPerSide || chunk == null) {
            return;
        }
        final int basePixelX = localChunkX * samplesPerChunk;
        final int basePixelZ = localChunkZ * samplesPerChunk;
        for (int sampleZ = 0; sampleZ < samplesPerChunk; sampleZ++) {
            final int localBlockZ = sampleZ * scale + (scale >>> 1);
            for (int sampleX = 0; sampleX < samplesPerChunk; sampleX++) {
                final int localBlockX = sampleX * scale + (scale >>> 1);
                final int pixelX = basePixelX + sampleX;
                final int pixelZ = basePixelZ + sampleZ;
                final int pixel = pixelZ * PIXELS + pixelX;
                known[pixel] = true;
                generated[pixel] = chunk.generated();
                pixelRevisions[pixel] = chunk.revision();
                columns[pixel] = chunk.generated()
                    ? chunk.columns()[localBlockZ * 16 + localBlockX]
                    : null;
                if (chunk.generated()) {
                    final int cell = (pixelZ >>> 4) * 16 + (pixelX >>> 4);
                    presence[cell >>> 3] |= (byte) (1 << (cell & 7));
                    maxRevision = Math.max(maxRevision, chunk.revision());
                }
            }
        }
    }

    SummaryView snapshot(final boolean complete) {
        return new Snapshot(
            lod, originBlockX(), originBlockZ(), complete ? maxRevision : 0L,
            columns.clone(), pixelRevisions.clone(), known.clone(), generated.clone(), presence.clone()
        );
    }

    @Override
    public int lod() {
        return lod;
    }

    @Override
    public long originBlockX() {
        return (long) tileX * TileMath.blocksPerTile(lod);
    }

    @Override
    public long originBlockZ() {
        return (long) tileZ * TileMath.blocksPerTile(lod);
    }

    @Override
    public long revision() {
        return maxRevision;
    }

    @Override
    public byte[] presence() {
        return presence.clone();
    }

    @Override
    public Pixel pixel(final int pixelX, final int pixelZ) {
        return pixel(columns, pixelRevisions, known, generated, pixelX, pixelZ);
    }

    private static Pixel pixel(
        final SummaryCodec.Column[] columns,
        final long[] revisions,
        final boolean[] known,
        final boolean[] generated,
        final int pixelX,
        final int pixelZ
    ) {
        if (pixelX < 0 || pixelX >= PIXELS || pixelZ < 0 || pixelZ >= PIXELS) {
            return null;
        }
        final int index = pixelZ * PIXELS + pixelX;
        return known[index] ? new Pixel(generated[index], revisions[index], columns[index]) : null;
    }

    private record Snapshot(
        int lod,
        long originBlockX,
        long originBlockZ,
        long revision,
        SummaryCodec.Column[] columns,
        long[] pixelRevisions,
        boolean[] known,
        boolean[] generated,
        byte[] presence
    ) implements SummaryView {
        @Override
        public byte[] presence() {
            return presence.clone();
        }

        @Override
        public Pixel pixel(final int pixelX, final int pixelZ) {
            return ProgressiveSummaryGrid.pixel(
                columns, pixelRevisions, known, generated, pixelX, pixelZ
            );
        }
    }
}
