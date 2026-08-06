package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.model.MapPixel;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.net.ChunkPatchCodec;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.core.predict.BaselineGrid;
import cn.net.rms.confluxmap.core.predict.DerivedGrid;
import cn.net.rms.confluxmap.core.util.ChunkRegionSlice;
import cn.net.rms.confluxmap.core.util.TileMath;
import java.util.ArrayList;
import java.util.List;

/** Builds one authoritative cropped region-page patch without materializing a coarse tile. */
public final class RegionPatchBuilder {
    public record Result(int mode, long revision, byte[] body, int recordCount) {
    }

    public Result build(
        final int lod,
        final ChunkRegionSlice slice,
        final SummaryCodec.SampledRegion region,
        final long sinceRevision,
        final PatchBuilder.PreparedBaseline prepared
    ) {
        return build(lod, slice, region, sinceRevision, prepared, true);
    }

    public Result build(
        final int lod,
        final ChunkRegionSlice slice,
        final SummaryCodec.SampledRegion region,
        final long sinceRevision,
        final PatchBuilder.PreparedBaseline prepared,
        final boolean enhancedProfile
    ) {
        if (lod < 0 || lod > TileMath.MAX_LOD || slice == null || region == null || prepared == null
            || region.rx() != slice.regionX() || region.rz() != slice.regionZ()
            || region.sampleStride() != 1 << lod) {
            return unavailable();
        }
        final int samplesPerChunk = 16 >> lod;
        final int sampleWidth = slice.width() * samplesPerChunk;
        final int sampleHeight = slice.height() * samplesPerChunk;
        final byte[] generated = new byte[ChunkPatchCodec.maskBytes(slice.width() * slice.height())];
        final byte[] evaluated = new byte[ChunkPatchCodec.maskBytes(sampleWidth * sampleHeight)];
        final long[] sourceRevisions = new long[slice.width() * slice.height()];
        java.util.Arrays.fill(sourceRevisions, Long.MIN_VALUE);
        final byte[] blockLight = new byte[sampleWidth * sampleHeight];
        final List<PatchCodec.Sample> records = new ArrayList<>();
        final BaselineGrid baseline = prepared.baseline();
        final DerivedGrid derived = prepared.derived();
        final MapPixel uniformPixel = prepared.uniformPixel();
        final boolean absolute = prepared.absolute()
            || (uniformPixel == null && (baseline == null || derived == null));
        final int chunksPerTile = 16 << lod;
        for (int chunkZ = 0; chunkZ < slice.height(); chunkZ++) {
            for (int chunkX = 0; chunkX < slice.width(); chunkX++) {
                final int regionLocalX = slice.minLocalChunkX() + chunkX;
                final int regionLocalZ = slice.minLocalChunkZ() + chunkZ;
                final SummaryCodec.SampledChunk chunk = region.chunks()[regionLocalZ * 16 + regionLocalX];
                final int patchChunk = chunkZ * slice.width() + chunkX;
                sourceRevisions[patchChunk] = chunk.revision();
                if (!chunk.generated()) {
                    continue;
                }
                ChunkPatchCodec.setBit(generated, patchChunk);
                final int worldChunkX = slice.regionX() * 16 + regionLocalX;
                final int worldChunkZ = slice.regionZ() * 16 + regionLocalZ;
                final int tileChunkX = Math.floorMod(worldChunkX, chunksPerTile);
                final int tileChunkZ = Math.floorMod(worldChunkZ, chunksPerTile);
                for (int sampleZ = 0; sampleZ < samplesPerChunk; sampleZ++) {
                    for (int sampleX = 0; sampleX < samplesPerChunk; sampleX++) {
                        final SummaryCodec.Column column = chunk.column(sampleX, sampleZ);
                        if (column == null || column.kind() == SurfaceKind.UNKNOWN.ordinal()) {
                            continue;
                        }
                        final int patchX = chunkX * samplesPerChunk + sampleX;
                        final int patchZ = chunkZ * samplesPerChunk + sampleZ;
                        final int patchPixel = patchZ * sampleWidth + patchX;
                        ChunkPatchCodec.setBit(evaluated, patchPixel);
                        blockLight[patchPixel] = (byte) column.blockLight();
                        final boolean differs = !absolute && (uniformPixel != null
                            ? !uniformPixel.equals(column.pixel())
                            : differsFromBaseline(
                                column, baseline, derived, prepared.mapColorId(),
                                tileChunkX * samplesPerChunk + sampleX,
                                tileChunkZ * samplesPerChunk + sampleZ
                            ));
                        if (absolute || differs) {
                            records.add(new PatchCodec.Sample(patchPixel, column.pixel()));
                        }
                    }
                }
            }
        }
        final ChunkPatchCodec.Patch patch = new ChunkPatchCodec.Patch(
            slice.width(), slice.height(), samplesPerChunk, generated, evaluated, records,
            sourceRevisions, blockLight
        );
        final long revision = ChunkPatchCodec.regionRevision(
            lod, slice, patch, enhancedProfile
        );
        if (sinceRevision != Long.MIN_VALUE && sinceRevision == revision) {
            return new Result(Proto.PATCH_MODE_UNCHANGED, revision, new byte[0], 0);
        }
        return new Result(
            absolute ? Proto.PATCH_MODE_ABSOLUTE : Proto.PATCH_MODE_RESIDUAL,
            revision,
            enhancedProfile
                ? ChunkPatchCodec.encode(patch)
                : ChunkPatchCodec.encodeLegacy(patch),
            records.size()
        );
    }

    public static Result unavailable() {
        return new Result(Proto.PATCH_MODE_UNAVAILABLE, 0L, new byte[0], 0);
    }

    private static boolean differsFromBaseline(
        final SummaryCodec.Column actual,
        final BaselineGrid baseline,
        final DerivedGrid derived,
        final int baselineMapColorId,
        final int tilePixelX,
        final int tilePixelZ
    ) {
        final int index = BaselineGrid.index(tilePixelX, tilePixelZ);
        final MapPixel expected = new MapPixel(
            baseline.biomeId[index],
            derived.surfaceY[index],
            derived.kind[index] & 255,
            baselineMapColorId,
            derived.fluidDepth[index],
            MapPixel.MAP_COLOR_NONE
        );
        return !expected.equals(actual.pixel());
    }
}
