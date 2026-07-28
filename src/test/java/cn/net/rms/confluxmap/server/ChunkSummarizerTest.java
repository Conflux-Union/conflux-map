package cn.net.rms.confluxmap.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.compat.Nbts;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.core.predict.BaselineGrid;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntUnaryOperator;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import org.junit.jupiter.api.Test;

class ChunkSummarizerTest {
    @Test
    void columnSourceCanBeSummarizedWithoutAWorldChunkOrSerializedNbt() {
        final ChunkColumnSource source = new ChunkColumnSource() {
            @Override
            public boolean generated() {
                return true;
            }

            @Override
            public long revision() {
                return 77L;
            }

            @Override
            public int bottomY() {
                return -64;
            }

            @Override
            public int motionBlockingHeight(final int x, final int z) {
                return 63;
            }

            @Override
            public int oceanFloorHeight(final int x, final int z) {
                return 50;
            }

            @Override
            public String blockNameAt(final int x, final int y, final int z) {
                return y >= 50 && y <= 62 ? "minecraft:water" : "minecraft:air";
            }

            @Override
            public int biomeIdAt(final int x, final int y, final int z) {
                return 4;
            }
        };

        final SummaryCodec.Chunk chunk = new ChunkSummarizer().summarize(source);
        final SummaryCodec.Column column = chunk.columns()[0];

        assertTrue(chunk.generated());
        assertEquals(77L, chunk.revision());
        assertEquals(4, column.biomeId());
        assertEquals(62, column.surfaceY());
        assertEquals(SurfaceKind.WATER.ordinal(), column.kind());
        assertEquals(13, column.fluidDepth());
    }

    @Test
    void structureStartsChunkIsNotTreatedAsGeneratedSurfaceData() {
        final NbtCompound level = new NbtCompound();
        level.putString("Status", "structure_starts");
        final NbtCompound root = new NbtCompound();
        root.put("Level", level);

        assertFalse(new ChunkSummarizer().summarize(root).generated());
    }

    @Test
    void sampledLodSummariesReadOnlyCenteredOutputCoordinates() {
        final List<String> positions = new ArrayList<>();
        final ChunkColumnSource source = new ChunkColumnSource() {
            @Override
            public boolean generated() {
                return true;
            }

            @Override
            public long revision() {
                return 1L;
            }

            @Override
            public int bottomY() {
                return 0;
            }

            @Override
            public int motionBlockingHeight(final int x, final int z) {
                positions.add(x + "," + z);
                return 64;
            }

            @Override
            public int oceanFloorHeight(final int x, final int z) {
                return ChunkColumnSource.NO_HEIGHT;
            }

            @Override
            public String blockNameAt(final int x, final int y, final int z) {
                return "minecraft:stone";
            }

            @Override
            public int biomeIdAt(final int x, final int y, final int z) {
                return 1;
            }
        };
        final ChunkSummarizer summarizer = new ChunkSummarizer();

        summarizer.summarizeForLod(source, 3);
        assertEquals(List.of("4,4", "12,4", "4,12", "12,12"), positions);

        positions.clear();
        summarizer.summarizeForLod(source, 4);
        assertEquals(List.of("8,8"), positions);
    }

    @Test
    void sampledLodSummariesMatchTheCorrespondingFullColumns() {
        final ChunkSummarizer summarizer = new ChunkSummarizer();
        final NbtCompound nbt = oceanChunk();
        final SummaryCodec.Chunk full = summarizer.summarize(nbt);

        for (int lod = 3; lod <= 4; lod++) {
            final int stride = 1 << lod;
            final int side = 16 / stride;
            final SummaryCodec.SampledChunk sampled = summarizer.summarizeForLod(nbt, lod);
            for (int sampleZ = 0; sampleZ < side; sampleZ++) {
                final int z = sampleZ * stride + (stride >>> 1);
                for (int sampleX = 0; sampleX < side; sampleX++) {
                    final int x = sampleX * stride + (stride >>> 1);
                    assertEquals(
                        full.columns()[z * 16 + x],
                        sampled.column(sampleX, sampleZ),
                        "LOD " + lod + " sample " + sampleX + "," + sampleZ + " disagreed"
                    );
                }
            }
        }
    }

    @Test
    void naturalOceanSummaryCarriesAnExactSeafloorCorrection() {
        final SummaryCodec.Chunk chunk = new ChunkSummarizer().summarize(oceanChunk());
        final SummaryCodec.Column column = chunk.columns()[0];
        assertEquals(62, column.surfaceY());
        assertEquals(SurfaceKind.WATER.ordinal(), column.kind());
        assertEquals(13, column.fluidDepth());
        assertEquals(11, column.floorMapColorId());

        assertExactSeafloorCorrection(chunk);
        assertModernRootAndPalettedContainersAreSummarized();
    }

    private static void assertModernRootAndPalettedContainersAreSummarized() {
        final SummaryCodec.Chunk chunk = new ChunkSummarizer().summarize(modernOceanChunk());
        final SummaryCodec.Column column = chunk.columns()[0];

        assertTrue(chunk.generated());
        assertEquals(62, column.surfaceY());
        assertEquals(SurfaceKind.WATER.ordinal(), column.kind());
        assertEquals(13, column.fluidDepth());
        assertEquals(0, column.biomeId());
    }

    private static void assertExactSeafloorCorrection(final SummaryCodec.Chunk chunk) {
        final SummaryCodec.Chunk[] chunks = new SummaryCodec.Chunk[SummaryCodec.CHUNKS];
        Arrays.fill(chunks, SummaryCodec.Chunk.empty());
        chunks[0] = chunk;
        final SummaryTile summary = new SummaryTile(
            0, 0, 0, List.of(new SummaryCodec.Region(0, 0, 0L, chunks))
        );
        final BaselineGrid baseline = new BaselineGrid();
        Arrays.fill(baseline.biomeId, 0);
        Arrays.fill(baseline.terrainY, 49);
        Arrays.fill(baseline.fluidY, 62);
        Arrays.fill(baseline.baseSurfaceY, 62);
        Arrays.fill(baseline.surfaceFlags, BaselineGrid.SURFACE_FLUID);

        final PatchBuilder.Result result = new PatchBuilder().build(summary, 0L, baseline, false);

        assertEquals(Proto.PATCH_MODE_RESIDUAL, result.mode());
        assertEquals(256, result.recordCount());
    }

    @Test
    void snowLayerAboveTheMotionBlockingSurfaceBecomesTheSnowSurface() {
        final SummaryCodec.Column column = new ChunkSummarizer()
            .summarize(snowCoveredChunk("minecraft:snow"))
            .columns()[0];

        assertEquals(SurfaceKind.SNOW.ordinal(), column.kind());
        assertEquals(62, column.surfaceY());
        assertEquals(0, column.fluidDepth());
    }

    @Test
    void snowCoverUsesTheResolvedSnowMapColor() {
        final ChunkSummarizer.MapColorResolver resolver =
            name -> "minecraft:snow".equals(name) ? 8 : -1;
        final SummaryCodec.Column column = new ChunkSummarizer(resolver)
            .summarize(snowCoveredChunk("minecraft:snow"))
            .columns()[0];

        assertEquals(SurfaceKind.SNOW.ordinal(), column.kind());
        assertEquals(8, column.mapColorId());
    }

    @Test
    void nonSnowCoverAboveTheSurfaceDoesNotReplaceIt() {
        final SummaryCodec.Column column = new ChunkSummarizer()
            .summarize(snowCoveredChunk("minecraft:short_grass"))
            .columns()[0];

        assertEquals(SurfaceKind.LAND.ordinal(), column.kind());
        assertEquals(61, column.surfaceY());
    }

    @Test
    void fluidCoverAboveTheMotionBlockingSurfacePromotesTheWaterSurface() {
        final NbtCompound chunk = coveredChunk("minecraft:clay", "minecraft:seagrass");
        final NbtCompound heightmaps = Nbts.compound(Nbts.compound(chunk, "Level"), "Heightmaps");
        heightmaps.putLongArray("OCEAN_FLOOR", pack(9, 256, ignored -> 61));
        final SummaryCodec.Column column = new ChunkSummarizer()
            .summarize(chunk)
            .columns()[0];

        assertEquals(SurfaceKind.WATER.ordinal(), column.kind());
        assertEquals(62, column.surfaceY());
        assertEquals(1, column.fluidDepth());
        assertEquals(1, column.floorMapColorId());
    }

    @Test
    void oceanIceKeepsItsWaterColumnDepth() {
        final SummaryCodec.Column column = new ChunkSummarizer()
            .summarize(frozenOceanChunk("minecraft:air"))
            .columns()[0];

        assertEquals(SurfaceKind.ICE.ordinal(), column.kind());
        assertEquals(62, column.surfaceY());
        assertEquals(13, column.fluidDepth());
    }

    @Test
    void snowSettledOnOceanIceBecomesSnowButKeepsTheWaterColumn() {
        final SummaryCodec.Column column = new ChunkSummarizer()
            .summarize(frozenOceanChunk("minecraft:snow"))
            .columns()[0];

        assertEquals(SurfaceKind.SNOW.ordinal(), column.kind());
        assertEquals(63, column.surfaceY());
        assertEquals(13, column.fluidDepth());
    }

    @Test
    void iceOnSolidGroundHasNoFluidColumn() {
        final SummaryCodec.Column column = new ChunkSummarizer()
            .summarize(coveredChunk("minecraft:packed_ice", "minecraft:air"))
            .columns()[0];

        assertEquals(SurfaceKind.ICE.ordinal(), column.kind());
        assertEquals(0, column.fluidDepth());
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
        assertEquals(11, column.floorMapColorId());
        assertExactSeafloorCorrection(chunk);
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

    private static NbtCompound snowCoveredChunk(final String coverBlockName) {
        return coveredChunk("minecraft:grass_block", coverBlockName);
    }

    /**
     * A land column whose surface block (y 61) carries one extra block at y 62 that MOTION_BLOCKING
     * ignores, exactly like vanilla's collision-less single snow layers.
     */
    private static NbtCompound coveredChunk(final String groundBlockName, final String coverBlockName) {
        final NbtCompound level = new NbtCompound();
        level.putString("Status", "full");
        level.putLong("LastUpdate", 100L);

        final NbtCompound heightmaps = new NbtCompound();
        heightmaps.putLongArray("MOTION_BLOCKING", pack(9, 256, ignored -> 62));
        level.put("Heightmaps", heightmaps);
        level.putIntArray("Biomes", new int[1024]);

        final NbtCompound section = new NbtCompound();
        section.putByte("Y", (byte) 3);
        final NbtList palette = new NbtList();
        palette.add(paletteEntry("minecraft:stone"));
        palette.add(paletteEntry(groundBlockName));
        palette.add(paletteEntry(coverBlockName));
        palette.add(paletteEntry("minecraft:air"));
        section.put("Palette", palette);
        section.putLongArray("BlockStates", pack(4, 4096, index -> {
            final int localY = index >>> 8;
            if (localY < 13) {
                return 0;
            }
            if (localY == 13) {
                return 1;
            }
            return localY == 14 ? 2 : 3;
        }));
        final NbtList sections = new NbtList();
        sections.add(section);
        level.put("Sections", sections);

        final NbtCompound root = new NbtCompound();
        root.put("Level", level);
        return root;
    }

    /**
     * A frozen-ocean column: water from y 50 to 61, ice at y 62 (the MOTION_BLOCKING surface),
     * and an optional cover block at y 63 the heightmap ignores.
     */
    private static NbtCompound frozenOceanChunk(final String coverBlockName) {
        final NbtCompound level = new NbtCompound();
        level.putString("Status", "full");
        level.putLong("LastUpdate", 100L);

        final NbtCompound heightmaps = new NbtCompound();
        heightmaps.putLongArray("MOTION_BLOCKING", pack(9, 256, ignored -> 63));
        level.put("Heightmaps", heightmaps);
        level.putIntArray("Biomes", new int[1024]);

        final NbtCompound section = new NbtCompound();
        section.putByte("Y", (byte) 3);
        final NbtList palette = new NbtList();
        palette.add(paletteEntry("minecraft:stone"));
        palette.add(paletteEntry("minecraft:water"));
        palette.add(paletteEntry("minecraft:ice"));
        palette.add(paletteEntry(coverBlockName));
        palette.add(paletteEntry("minecraft:air"));
        section.put("Palette", palette);
        section.putLongArray("BlockStates", pack(4, 4096, index -> {
            final int localY = index >>> 8;
            if (localY < 2) {
                return 0;
            }
            if (localY < 14) {
                return 1;
            }
            return localY == 14 ? 2 : 3;
        }));
        final NbtList sections = new NbtList();
        sections.add(section);
        level.put("Sections", sections);

        final NbtCompound root = new NbtCompound();
        root.put("Level", level);
        return root;
    }

    private static NbtCompound modernOceanChunk() {
        final NbtCompound root = new NbtCompound();
        root.putString("Status", "minecraft:full");
        root.putInt("yPos", -4);
        root.putLong("LastUpdate", 100L);

        final NbtCompound heightmaps = new NbtCompound();
        heightmaps.putLongArray("MOTION_BLOCKING", pack(9, 256, ignored -> 127));
        heightmaps.putLongArray("OCEAN_FLOOR", pack(9, 256, ignored -> 114));
        root.put("Heightmaps", heightmaps);

        final NbtCompound section = new NbtCompound();
        section.putByte("Y", (byte) 3);
        final NbtCompound blockStates = new NbtCompound();
        final NbtList blockPalette = new NbtList();
        blockPalette.add(paletteEntry("minecraft:stone"));
        blockPalette.add(paletteEntry("minecraft:water"));
        blockPalette.add(paletteEntry("minecraft:air"));
        blockStates.put("palette", blockPalette);
        blockStates.putLongArray("data", pack(4, 4096, index -> {
            final int localY = index >>> 8;
            if (localY >= 2 && localY <= 14) {
                return 1;
            }
            return localY == 15 ? 2 : 0;
        }));
        section.put("block_states", blockStates);

        final NbtCompound biomes = new NbtCompound();
        final NbtList biomePalette = new NbtList();
        biomePalette.add(NbtString.of("minecraft:ocean"));
        biomes.put("palette", biomePalette);
        section.put("biomes", biomes);

        final NbtList sections = new NbtList();
        sections.add(section);
        root.put("sections", sections);
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
