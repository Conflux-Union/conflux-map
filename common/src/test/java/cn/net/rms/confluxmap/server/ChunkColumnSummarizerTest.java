package cn.net.rms.confluxmap.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import org.junit.jupiter.api.Test;

class ChunkColumnSummarizerTest {
    @Test
    void platformAdapterSuppliesColumnsWithoutMinecraftTypes() {
        final ChunkColumnSummarizer summarizer = new ChunkColumnSummarizer(name -> 3);

        final SummaryCodec.Chunk summary = summarizer.summarize(
            new StoneWithCoverColumn("minecraft:snow")
        );

        final SummaryCodec.Column column = summary.columns()[0];
        assertEquals(17L, summary.revision());
        assertEquals(64, column.surfaceY());
        assertEquals(SurfaceKind.SNOW.ordinal(), column.kind());
        assertEquals(3, column.mapColorId());
        assertEquals(1, column.biomeId());
        assertEquals(12, column.blockLight());
        assertEquals("minecraft:snow", column.materialId());
        assertEquals("", column.floorMaterialId());
    }

    @Test
    void carpetAboveTheMotionBlockingSurfaceBecomesTheVisibleMaterial() {
        final ChunkColumnSummarizer summarizer = new ChunkColumnSummarizer(
            name -> "minecraft:white_carpet".equals(name) ? 8 : 3
        );

        final SummaryCodec.Column column = summarizer.summarize(
            new StoneWithCoverColumn("minecraft:white_carpet")
        ).columns()[0];

        assertEquals(64, column.surfaceY());
        assertEquals(SurfaceKind.LAND.ordinal(), column.kind());
        assertEquals(8, column.mapColorId());
        assertEquals("minecraft:white_carpet", column.materialId());
    }

    @Test
    void stainedGlassRoofDescendsToTheSceneryAndReportsTheGlassAsOverlay() {
        final ChunkColumnSummarizer summarizer = new ChunkColumnSummarizer(name -> 3);

        final SummaryCodec.Column column = summarizer.summarize(new LayeredColumn()
            .set(70, "minecraft:black_stained_glass")
            .set(69, "minecraft:snow_block")
            .motionTopExclusive(71)
        ).columns()[0];

        assertEquals(69, column.surfaceY());
        assertEquals(SurfaceKind.SNOW.ordinal(), column.kind());
        assertEquals("minecraft:snow_block", column.materialId());
        assertEquals("minecraft:black_stained_glass", column.overlayMaterialId());
    }

    @Test
    void tintedGlassBlocksLightAndStaysTheSurface() {
        final ChunkColumnSummarizer summarizer = new ChunkColumnSummarizer(name -> 3);

        final SummaryCodec.Column column = summarizer.summarize(new LayeredColumn()
            .set(70, "minecraft:tinted_glass")
            .set(69, "minecraft:snow_block")
            .motionTopExclusive(71)
        ).columns()[0];

        assertEquals(70, column.surfaceY());
        assertEquals("minecraft:tinted_glass", column.materialId());
        assertEquals("", column.overlayMaterialId());
    }

    @Test
    void stackedGlassLayersReportTheTopmostLayerAsOverlay() {
        final ChunkColumnSummarizer summarizer = new ChunkColumnSummarizer(name -> 3);

        final SummaryCodec.Column column = summarizer.summarize(new LayeredColumn()
            .set(70, "minecraft:glass")
            .set(69, "minecraft:blue_stained_glass")
            .set(68, "minecraft:stone")
            .motionTopExclusive(71)
        ).columns()[0];

        assertEquals(68, column.surfaceY());
        assertEquals("minecraft:stone", column.materialId());
        assertEquals("minecraft:glass", column.overlayMaterialId());
    }

    @Test
    void glassOverWaterDescendsIntoTheFluidSurface() {
        final ChunkColumnSummarizer summarizer = new ChunkColumnSummarizer(name -> 3);

        final SummaryCodec.Column column = summarizer.summarize(new LayeredColumn()
            .set(70, "minecraft:glass")
            .set(69, "minecraft:water")
            .set(68, "minecraft:water")
            .set(67, "minecraft:water")
            .set(66, "minecraft:water")
            .set(65, "minecraft:dirt")
            .motionTopExclusive(71)
        ).columns()[0];

        assertEquals(69, column.surfaceY());
        assertEquals(SurfaceKind.WATER.ordinal(), column.kind());
        assertEquals("minecraft:water", column.materialId());
        assertEquals("minecraft:glass", column.overlayMaterialId());
        assertEquals(4, column.fluidDepth());
        assertEquals("minecraft:dirt", column.floorMaterialId());
    }

    @Test
    void standingDecorationBecomesOverlayMaterial() {
        final ChunkColumnSummarizer summarizer = new ChunkColumnSummarizer(name -> 3);

        final SummaryCodec.Column column = summarizer.summarize(new LayeredColumn()
            .set(63, "minecraft:dirt")
            .set(64, "minecraft:wither_rose")
            .motionTopExclusive(64)
        ).columns()[0];

        assertEquals(63, column.surfaceY());
        assertEquals("minecraft:dirt", column.materialId());
        assertEquals("minecraft:wither_rose", column.overlayMaterialId());
    }

    @Test
    void bulkGrassDecorationIsNotReportedAsOverlay() {
        final ChunkColumnSummarizer summarizer = new ChunkColumnSummarizer(name -> 3);

        final SummaryCodec.Column column = summarizer.summarize(new LayeredColumn()
            .set(63, "minecraft:grass_block")
            .set(64, "minecraft:grass")
            .motionTopExclusive(64)
        ).columns()[0];

        assertEquals(63, column.surfaceY());
        assertEquals("minecraft:grass_block", column.materialId());
        assertEquals("", column.overlayMaterialId());
    }

    @Test
    void glassOverNothingIsPromotedToTheVisibleSurface() {
        final ChunkColumnSummarizer summarizer = new ChunkColumnSummarizer(name -> 3);

        final SummaryCodec.Column column = summarizer.summarize(new LayeredColumn()
            .set(-64, "minecraft:white_stained_glass")
            .motionTopExclusive(-63)
        ).columns()[0];

        assertEquals(-64, column.surfaceY());
        assertEquals(SurfaceKind.LAND.ordinal(), column.kind());
        assertEquals("minecraft:white_stained_glass", column.materialId());
        assertEquals("", column.overlayMaterialId());
    }

    @Test
    void stackedGlassOverNothingKeepsTheTopmostLayerAsTheSurface() {
        final ChunkColumnSummarizer summarizer = new ChunkColumnSummarizer(name -> 3);

        final SummaryCodec.Column column = summarizer.summarize(new LayeredColumn()
            .set(70, "minecraft:glass")
            .set(69, "minecraft:blue_stained_glass")
            .motionTopExclusive(71)
        ).columns()[0];

        assertEquals(70, column.surfaceY());
        assertEquals(SurfaceKind.LAND.ordinal(), column.kind());
        assertEquals("minecraft:glass", column.materialId());
        assertEquals("", column.overlayMaterialId());
    }

    private static final class LayeredColumn implements ChunkColumnSource {
        private final java.util.Map<Integer, String> blocks = new java.util.HashMap<>();
        private int motionTop;
        private int oceanFloor = NO_HEIGHT;

        LayeredColumn set(final int y, final String name) {
            blocks.put(y, name);
            return this;
        }

        LayeredColumn motionTopExclusive(final int y) {
            motionTop = y;
            return this;
        }

        @Override
        public boolean generated() {
            return true;
        }

        @Override
        public long revision() {
            return 5L;
        }

        @Override
        public int bottomY() {
            return -64;
        }

        @Override
        public int motionBlockingHeight(final int x, final int z) {
            return motionTop;
        }

        @Override
        public int oceanFloorHeight(final int x, final int z) {
            return oceanFloor;
        }

        @Override
        public String blockNameAt(final int x, final int y, final int z) {
            return blocks.getOrDefault(y, "minecraft:air");
        }

        @Override
        public SurfaceKind fluidKindAt(final int x, final int y, final int z) {
            return blockNameAt(x, y, z).contains("water")
                ? SurfaceKind.WATER
                : SurfaceKind.UNKNOWN;
        }

        @Override
        public int biomeIdAt(final int x, final int y, final int z) {
            return 1;
        }
    }

    private static final class StoneWithCoverColumn implements ChunkColumnSource {
        private final String cover;

        private StoneWithCoverColumn(final String cover) {
            this.cover = cover;
        }

        @Override
        public boolean generated() {
            return true;
        }

        @Override
        public long revision() {
            return 17L;
        }

        @Override
        public int bottomY() {
            return -64;
        }

        @Override
        public int motionBlockingHeight(final int x, final int z) {
            return 64;
        }

        @Override
        public int oceanFloorHeight(final int x, final int z) {
            return NO_HEIGHT;
        }

        @Override
        public String blockNameAt(final int x, final int y, final int z) {
            if (y == 64) {
                return cover;
            }
            return y == 63 ? "minecraft:stone" : "minecraft:air";
        }

        @Override
        public SurfaceKind fluidKindAt(final int x, final int y, final int z) {
            return SurfaceKind.UNKNOWN;
        }

        @Override
        public int biomeIdAt(final int x, final int y, final int z) {
            return 1;
        }

        @Override
        public int blockLightAbove(final int x, final int surfaceY, final int z) {
            return 12;
        }
    }
}
