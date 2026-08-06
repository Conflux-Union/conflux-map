package cn.net.rms.confluxmap.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import org.junit.jupiter.api.Test;

class ChunkColumnSummarizerTest {
    @Test
    void platformAdapterSuppliesColumnsWithoutMinecraftTypes() {
        final ChunkColumnSummarizer summarizer = new ChunkColumnSummarizer(name -> 3);

        final SummaryCodec.Chunk summary = summarizer.summarize(new StoneWithSnowColumn());

        final SummaryCodec.Column column = summary.columns()[0];
        assertEquals(17L, summary.revision());
        assertEquals(64, column.surfaceY());
        assertEquals(SurfaceKind.SNOW.ordinal(), column.kind());
        assertEquals(3, column.mapColorId());
        assertEquals(1, column.biomeId());
        assertEquals(12, column.blockLight());
    }

    private static final class StoneWithSnowColumn implements ChunkColumnSource {
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
                return "minecraft:snow";
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
