package cn.net.rms.confluxmap.core.backfill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Column bookkeeping of {@link McaSnapshotConverter}, checked against a recording fake baker. */
class McaSnapshotConverterTest {

    private static final long SESSION = 42L;
    private static final long MTIME = 1_760_000_000_000L;

    private record BakedCall(
        SummaryCodec.Column column, int worldX, int worldZ, int biomeId
    ) {}

    private static final class RecordingBaker implements McaColumnBaker {
        final List<BakedCall> calls = new java.util.ArrayList<>();

        @Override
        public Baked bake(
            final SummaryCodec.Column column,
            final int worldX,
            final int worldZ,
            final int cubiomesBiomeId
        ) {
            calls.add(new BakedCall(column, worldX, worldZ, cubiomesBiomeId));
            return new Baked(0xFF000001, 0xFF000002, 0xFF000003, 0xFF000004, 0xFF000005);
        }
    }

    private static SummaryCodec.Column column(
        final int biomeId, final int surfaceY, final SurfaceKind kind, final int fluidDepth,
        final int blockLight, final String materialId
    ) {
        return new SummaryCodec.Column(
            biomeId, surfaceY, kind.ordinal(), 11, fluidDepth, fluidDepth == 0 ? 255 : 12,
            blockLight, materialId, fluidDepth == 0 ? "" : "minecraft:sand", ""
        );
    }

    /** Region with chunk 0 generated (land), chunk 1 empty, chunk 16 void, chunk 17 water. */
    private static SummaryCodec.SampledRegion region() {
        final SummaryCodec.SampledChunk[] chunks = new SummaryCodec.SampledChunk[256];
        for (int i = 0; i < chunks.length; i++) {
            chunks[i] = SummaryCodec.SampledChunk.empty(1);
        }
        chunks[0] = SummaryCodec.SampledChunk.empty(1, 7L);
        chunks[0] = filled(
            column(1, 64, SurfaceKind.LAND, 0, 5, "minecraft:grass_block")
        );
        chunks[1] = SummaryCodec.SampledChunk.empty(1, 0L);
        chunks[16] = filled(
            column(3, Short.MIN_VALUE, SurfaceKind.VOID, 0, 0, "minecraft:air")
        );
        chunks[17] = filled(
            column(1, 62, SurfaceKind.WATER, 6, 3, "minecraft:water")
        );
        return new SummaryCodec.SampledRegion(10, -4, MTIME, 1, chunks);
    }

    private static SummaryCodec.SampledChunk filled(final SummaryCodec.Column column) {
        final SummaryCodec.Column[] columns = new SummaryCodec.Column[256];
        java.util.Arrays.fill(columns, column);
        return new SummaryCodec.SampledChunk(true, 7L, 1, columns);
    }

    @Test
    void convertsOnlyGeneratedChunksAtRegionLocalCoordinates() {
        final RecordingBaker baker = new RecordingBaker();
        final List<ChunkSnapshot> snapshots =
            McaSnapshotConverter.toSnapshots(region(), SESSION, MapLayer.SURFACE, baker);

        assertEquals(3, snapshots.size());
        final int[] chunkXs = snapshots.stream().mapToInt(s -> s.chunkX).toArray();
        final int[] chunkZs = snapshots.stream().mapToInt(s -> s.chunkZ).toArray();
        // chunks[0]=(160,-64) generated, chunks[1]=(161,-64) empty, chunks[16]=(160,-63) void,
        // chunks[17]=(161,-63) water: exactly 0, 16 and 17 convert.
        assertTrue(contains(chunkXs, 160) && contains(chunkZs, -64));
        assertTrue(contains(chunkXs, 161) && contains(chunkZs, -63));
    }

    @Test
    void landColumnCarriesSummaryFieldsAndBakedColors() {
        final RecordingBaker baker = new RecordingBaker();
        final List<ChunkSnapshot> snapshots =
            McaSnapshotConverter.toSnapshots(region(), SESSION, MapLayer.SURFACE, baker);
        final ChunkSnapshot land = snapshots.stream()
            .filter(s -> s.chunkX == 160 && s.chunkZ == -64).findFirst().orElseThrow();

        assertEquals(MTIME, land.sourceRevision);
        assertEquals(SESSION, land.sessionToken);
        assertEquals(64, land.surfaceY[0]);
        assertEquals((byte) SurfaceKind.LAND.ordinal(), land.kind[0]);
        assertEquals(5, land.light[0]);
        assertEquals("minecraft:plains", land.biomeId[0]);
        assertEquals(0xFF000001, land.baseArgb[0]);
        assertEquals(0xFF000002, land.xaeroBaseArgb[0]);
        assertEquals(0xFF000003, land.tintArgb[0]);
        assertEquals(0xFF000004, land.overlayArgb[0]);
        assertEquals(0xFF000005, land.xaeroOverlayArgb[0]);
        // Land and water chunks run every column through the baker; void columns do not.
        assertEquals(512, baker.calls.size());
        assertEquals(160 * 16 + 0, baker.calls.get(0).worldX());
    }

    @Test
    void voidColumnHasNoSurfaceAndNoColors() {
        final RecordingBaker baker = new RecordingBaker();
        final List<ChunkSnapshot> snapshots =
            McaSnapshotConverter.toSnapshots(region(), SESSION, MapLayer.SURFACE, baker);
        final ChunkSnapshot voidChunk = snapshots.stream()
            .filter(s -> s.chunkX == 160 && s.chunkZ == -63).findFirst().orElseThrow();

        assertEquals(ChunkSnapshot.NO_SURFACE, voidChunk.surfaceY[0]);
        assertEquals(0, voidChunk.baseArgb[0]);
        assertEquals((byte) SurfaceKind.VOID.ordinal(), voidChunk.kind[0]);
        assertEquals("minecraft:mountains", voidChunk.biomeId[0]); // cubiomes id 3
    }

    @Test
    void waterColumnKeepsDepthAndLight() {
        final RecordingBaker baker = new RecordingBaker();
        final List<ChunkSnapshot> snapshots =
            McaSnapshotConverter.toSnapshots(region(), SESSION, MapLayer.SURFACE, baker);
        final ChunkSnapshot water = snapshots.stream()
            .filter(s -> s.chunkX == 161 && s.chunkZ == -63).findFirst().orElseThrow();

        assertEquals(6, water.fluidDepth[0]);
        assertEquals(62, water.surfaceY[0]);
        assertEquals(3, water.light[0]);
        assertEquals("minecraft:plains", water.biomeId[0]); // cubiomes id 1
    }

    @Test
    void rejectsNonLod0Stride() {
        final SummaryCodec.SampledChunk[] chunks = new SummaryCodec.SampledChunk[256];
        for (int i = 0; i < chunks.length; i++) {
            chunks[i] = SummaryCodec.SampledChunk.empty(8);
        }
        final SummaryCodec.SampledRegion region = new SummaryCodec.SampledRegion(0, 0, MTIME, 8, chunks);
        assertThrows(IllegalArgumentException.class,
            () -> McaSnapshotConverter.toSnapshots(region, SESSION, MapLayer.SURFACE, new RecordingBaker()));
    }

    private static boolean contains(final int[] values, final int value) {
        for (final int candidate : values) {
            if (candidate == value) {
                return true;
            }
        }
        return false;
    }
}
