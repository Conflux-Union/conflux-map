package cn.net.rms.confluxmap.paper;

import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.server.SummaryView;
import java.util.Collection;

/** Tile-wide view assembled at the exact centered sample density required by one LOD. */
final class PaperSampledSummaryTile implements SummaryView {
    private final int lod;
    private final int tileX;
    private final int tileZ;
    private final int regionsPerSide;
    private final int baseRegionX;
    private final int baseRegionZ;
    private final SummaryCodec.SampledRegion[] regions;
    private final long revision;
    private final byte[] presence;

    PaperSampledSummaryTile(
        final int lod,
        final int tileX,
        final int tileZ,
        final Collection<SummaryCodec.SampledRegion> source
    ) {
        if (lod < 0 || lod > 4) {
            throw new IllegalArgumentException("unsupported summary tile LOD " + lod);
        }
        this.lod = lod;
        this.tileX = tileX;
        this.tileZ = tileZ;
        regionsPerSide = 1 << lod;
        baseRegionX = Math.multiplyExact(tileX, regionsPerSide);
        baseRegionZ = Math.multiplyExact(tileZ, regionsPerSide);
        regions = new SummaryCodec.SampledRegion[regionsPerSide * regionsPerSide];
        long maximumRevision = 0L;
        for (final SummaryCodec.SampledRegion region : source) {
            if (region == null || region.sampleStride() != 1 << lod) {
                continue;
            }
            final int localX = region.rx() - baseRegionX;
            final int localZ = region.rz() - baseRegionZ;
            if (localX < 0 || localX >= regionsPerSide || localZ < 0 || localZ >= regionsPerSide) {
                continue;
            }
            regions[localZ * regionsPerSide + localX] = region;
            for (final SummaryCodec.SampledChunk chunk : region.chunks()) {
                if (chunk.generated()) {
                    maximumRevision = Math.max(maximumRevision, chunk.revision());
                }
            }
        }
        revision = maximumRevision;
        presence = buildPresence();
    }

    @Override
    public int lod() {
        return lod;
    }

    @Override
    public long originBlockX() {
        return (long) tileX * 256L * (1 << lod);
    }

    @Override
    public long originBlockZ() {
        return (long) tileZ * 256L * (1 << lod);
    }

    @Override
    public long revision() {
        return revision;
    }

    @Override
    public byte[] presence() {
        return presence.clone();
    }

    @Override
    public Pixel pixel(final int pixelX, final int pixelZ) {
        if (pixelX < 0 || pixelX >= 256 || pixelZ < 0 || pixelZ >= 256) {
            return null;
        }
        final int samplesPerChunk = 16 >> lod;
        final int chunkXInTile = pixelX / samplesPerChunk;
        final int chunkZInTile = pixelZ / samplesPerChunk;
        final int chunksPerRegion = 16;
        final int regionLocalX = chunkXInTile / chunksPerRegion;
        final int regionLocalZ = chunkZInTile / chunksPerRegion;
        final SummaryCodec.SampledRegion region = regions[
            regionLocalZ * regionsPerSide + regionLocalX
        ];
        if (region == null) {
            return null;
        }
        final int chunkLocalX = chunkXInTile & 15;
        final int chunkLocalZ = chunkZInTile & 15;
        final SummaryCodec.SampledChunk chunk = region.chunks()[chunkLocalZ * 16 + chunkLocalX];
        if (!chunk.generated()) {
            return new Pixel(false, chunk.revision(), null);
        }
        return new Pixel(
            true,
            chunk.revision(),
            chunk.column(pixelX % samplesPerChunk, pixelZ % samplesPerChunk)
        );
    }

    private byte[] buildPresence() {
        final byte[] bits = new byte[Proto.PATCH_PRESENCE_BYTES];
        for (int cellZ = 0; cellZ < 16; cellZ++) {
            for (int cellX = 0; cellX < 16; cellX++) {
                boolean generated = false;
                for (int pixelZ = cellZ * 16; pixelZ < cellZ * 16 + 16 && !generated; pixelZ++) {
                    for (int pixelX = cellX * 16; pixelX < cellX * 16 + 16; pixelX++) {
                        final Pixel pixel = pixel(pixelX, pixelZ);
                        if (pixel != null && pixel.generated()) {
                            generated = true;
                            break;
                        }
                    }
                }
                if (generated) {
                    final int bit = cellZ * 16 + cellX;
                    bits[bit >>> 3] |= (byte) (1 << (bit & 7));
                }
            }
        }
        return bits;
    }
}
