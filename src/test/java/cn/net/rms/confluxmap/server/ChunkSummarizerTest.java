package cn.net.rms.confluxmap.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.core.predict.BaselineGrid;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntUnaryOperator;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import org.junit.jupiter.api.Test;

class ChunkSummarizerTest {
    @Test
    void structureStartsChunkIsNotTreatedAsGeneratedSurfaceData() {
        final NbtCompound level = new NbtCompound();
        level.putString("Status", "structure_starts");
        final NbtCompound root = new NbtCompound();
        root.put("Level", level);

        assertFalse(new ChunkSummarizer().summarize(root).generated());
    }

    @Test
    void naturalOceanSummaryDoesNotProduceAFalseCorrection() {
        final SummaryCodec.Chunk chunk = new ChunkSummarizer().summarize(oceanChunk());
        final SummaryCodec.Column column = chunk.columns()[0];
        assertEquals(62, column.surfaceY());
        assertEquals(SurfaceKind.WATER.ordinal(), column.kind());
        assertEquals(13, column.fluidDepth());

        assertNoFalseCorrection(chunk);
    }

    private static void assertNoFalseCorrection(final SummaryCodec.Chunk chunk) {
        final SummaryCodec.Chunk[] chunks = new SummaryCodec.Chunk[SummaryCodec.CHUNKS];
        Arrays.fill(chunks, SummaryCodec.Chunk.empty());
        chunks[0] = chunk;
        final SummaryTile summary = new SummaryTile(
            0, 0, 0, List.of(new SummaryCodec.Region(0, 0, 0L, chunks))
        );
        final BaselineGrid baseline = new BaselineGrid();
        Arrays.fill(baseline.biomeId, 0);
        Arrays.fill(baseline.terrainY, 49);

        final PatchBuilder.Result result = new PatchBuilder().build(summary, 0L, baseline, false);

        assertEquals(Proto.PATCH_MODE_UNCHANGED, result.mode());
        assertEquals(0, result.recordCount());
    }

    @Test
    void kelpAtTheOceanSurfaceIsSummarizedAsWater() {
        assertOceanSurface("minecraft:kelp");
        assertOceanSurface("minecraft:kelp_plant");
    }

    @Test
    void driedKelpBlockIsNotSummarizedAsWater() {
        final SummaryCodec.Column column = new ChunkSummarizer()
            .summarize(oceanChunk("minecraft:dried_kelp_block"))
            .columns()[0];

        assertEquals(SurfaceKind.LAND.ordinal(), column.kind());
    }

    @Test
    void resolvedMapColorOverridesTheHeuristicLandColor() {
        final ChunkSummarizer.MapColorResolver resolver =
            name -> "minecraft:oak_planks".equals(name) ? 13 : -1;
        final SummaryCodec.Column column = new ChunkSummarizer(resolver)
            .summarize(oceanChunk("minecraft:oak_planks"))
            .columns()[0];

        assertEquals(SurfaceKind.LAND.ordinal(), column.kind());
        assertEquals(13, column.mapColorId());
    }

    @Test
    void withoutAResolverTheHeuristicLandColorRemains() {
        final SummaryCodec.Column column = new ChunkSummarizer()
            .summarize(oceanChunk("minecraft:oak_planks"))
            .columns()[0];

        assertEquals(1, column.mapColorId());
    }

    @Test
    void clearMapColorFallsBackToTheHeuristic() {
        final SummaryCodec.Column column = new ChunkSummarizer(name -> 0)
            .summarize(oceanChunk("minecraft:glass"))
            .columns()[0];

        assertEquals(SurfaceKind.LAND.ordinal(), column.kind());
        assertEquals(1, column.mapColorId());
    }

    @Test
    void waterKeepsItsFixedColorEvenWithAResolver() {
        final SummaryCodec.Column column = new ChunkSummarizer(name -> 40)
            .summarize(oceanChunk())
            .columns()[0];

        assertEquals(SurfaceKind.WATER.ordinal(), column.kind());
        assertEquals(12, column.mapColorId());
    }

    private static void assertOceanSurface(final String blockName) {
        final SummaryCodec.Chunk chunk = new ChunkSummarizer().summarize(oceanChunk(blockName));
        final SummaryCodec.Column column = chunk.columns()[0];

        assertEquals(SurfaceKind.WATER.ordinal(), column.kind());
        assertEquals(12, column.mapColorId());
        assertEquals(13, column.fluidDepth());
        assertNoFalseCorrection(chunk);
    }

    private static NbtCompound oceanChunk() {
        return oceanChunk("minecraft:water");
    }

    private static NbtCompound oceanChunk(final String surfaceBlockName) {
        final NbtCompound level = new NbtCompound();
        level.putString("Status", "full");
        level.putLong("LastUpdate", 100L);

        final NbtCompound heightmaps = new NbtCompound();
        heightmaps.putLongArray("MOTION_BLOCKING", pack(9, 256, ignored -> 63));
        heightmaps.putLongArray("OCEAN_FLOOR", pack(9, 256, ignored -> 50));
        level.put("Heightmaps", heightmaps);
        level.putIntArray("Biomes", new int[1024]);

        final NbtCompound section = new NbtCompound();
        section.putByte("Y", (byte) 3);
        final NbtList palette = new NbtList();
        palette.add(paletteEntry("minecraft:stone"));
        palette.add(paletteEntry("minecraft:water"));
        palette.add(paletteEntry("minecraft:air"));
        palette.add(paletteEntry(surfaceBlockName));
        section.put("Palette", palette);
        section.putLongArray("BlockStates", pack(4, 4096, index -> {
            final int localY = index >>> 8;
            if (localY >= 2 && localY < 14) {
                return 1;
            }
            if (localY == 14) {
                return 3;
            }
            return localY == 15 ? 2 : 0;
        }));
        final NbtList sections = new NbtList();
        sections.add(section);
        level.put("Sections", sections);

        final NbtCompound root = new NbtCompound();
        root.put("Level", level);
        return root;
    }

    private static NbtCompound paletteEntry(final String name) {
        final NbtCompound entry = new NbtCompound();
        entry.putString("Name", name);
        return entry;
    }

    private static long[] pack(final int bits, final int count, final IntUnaryOperator values) {
        final int perWord = 64 / bits;
        final long[] words = new long[(count + perWord - 1) / perWord];
        final long mask = (1L << bits) - 1L;
        for (int i = 0; i < count; i++) {
            words[i / perWord] |= ((long) values.applyAsInt(i) & mask) << ((i % perWord) * bits);
        }
        return words;
    }
}
