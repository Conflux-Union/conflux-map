package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link CountingSampler}: one {@code w*h} per query rectangle, everything else delegated. */
class CountingSamplerTest {
    /** Recording fake: accepts every query and remembers the argument tuples it received. */
    private static final class RecordingSampler implements BaselineSampler {
        final List<int[]> biomesCalls = new ArrayList<>();
        final List<int[]> stridedCalls = new ArrayList<>();

        @Override
        public boolean biomes(final int scale, final int x, final int z, final int w, final int h, final int[] out) {
            biomesCalls.add(new int[] {scale, x, z, w, h});
            return true;
        }

        @Override
        public boolean biomesStrided(
            final int scale, final int x, final int z, final int w, final int h, final int stride, final int[] out
        ) {
            stridedCalls.add(new int[] {scale, x, z, w, h, stride});
            return true;
        }

        @Override
        public boolean heights(final int x4, final int z4, final int w, final int h, final int[] outY) {
            return true;
        }

        @Override
        public boolean endHeights(final int x4, final int z4, final int w, final int h, final int[] outY) {
            return true;
        }

        @Override
        public int terrainFreeBiomeScale() {
            return 16;
        }

        @Override
        public boolean interpolatableOverviewHeights() {
            return true;
        }
    }

    @Test
    void countsOutputCellsPerQueryAndDelegatesUnchanged() {
        final RecordingSampler delegate = new RecordingSampler();
        final CountingSampler sampler = new CountingSampler(delegate);

        final int[] out = new int[16];
        assertTrue(sampler.biomes(1, 0, 0, 4, 4, out));
        assertEquals(1, delegate.biomesCalls.size());
        assertEquals(1, delegate.biomesCalls.get(0)[0]);
        assertEquals(4, delegate.biomesCalls.get(0)[3]);
        assertEquals(16L, sampler.points());

        // Strided queries count their output grid, not the denser raw row width.
        final int[] stridedOut = new int[9];
        assertTrue(sampler.biomesStrided(4, 0, 0, 3, 3, 8, stridedOut));
        assertEquals(4, delegate.stridedCalls.get(0)[0]);
        assertEquals(3, delegate.stridedCalls.get(0)[3]);
        assertEquals(8, delegate.stridedCalls.get(0)[5]);
        assertEquals(16L + 9L, sampler.points());

        final int[] heightsOut = new int[6];
        assertTrue(sampler.heights(0, 0, 2, 3, heightsOut));
        assertEquals(16L + 9L + 6L, sampler.points());
    }

    @Test
    void capabilityAndCanopyProbesDoNotCount() {
        final RecordingSampler delegate = new RecordingSampler();
        final CountingSampler sampler = new CountingSampler(delegate);

        assertEquals(16, sampler.terrainFreeBiomeScale());
        assertTrue(sampler.interpolatableOverviewHeights());
        assertEquals(BaselineSampler.TREES_UNSUPPORTED, sampler.treeCandidates(0, 0, new TreeCandidate[1]));
        assertEquals(0L, sampler.points());
        assertEquals(0, delegate.biomesCalls.size());
        assertEquals(0, delegate.stridedCalls.size());
    }
}
