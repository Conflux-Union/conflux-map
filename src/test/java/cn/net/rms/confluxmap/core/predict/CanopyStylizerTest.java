package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** {@link CanopyStylizer}'s determinism and areal-coverage-vs-treeCover approximation. */
class CanopyStylizerTest {
    /** cubiomes id for {@code forest}: {@link BiomeTable#get}'s treeCover is 0.35. */
    private static final int FOREST = 4;
    private static final int JUNGLE = 21;
    private static final int BAMBOO_JUNGLE = 168;

    private static BaselineGrid uniformGrid(final int biomeId, final int terrainY) {
        final BaselineGrid grid = new BaselineGrid();
        Arrays.fill(grid.biomeId, biomeId);
        Arrays.fill(grid.terrainY, terrainY);
        return grid;
    }

    @Test
    void deterministicAcrossRepeatedCalls() {
        final BaselineGrid grid = uniformGrid(FOREST, 70);
        final DerivedGrid first = BaselineDeriver.derive(grid);
        CanopyStylizer.apply(first, grid, 42L, 0, 1000, -2000);

        final DerivedGrid second = BaselineDeriver.derive(grid);
        CanopyStylizer.apply(second, grid, 42L, 0, 1000, -2000);

        assertArrayEquals(first.kind, second.kind);
        assertArrayEquals(first.surfaceY, second.surfaceY);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void foliageFractionApproximatesTreeCover(final int lod) {
        final double treeCover = BiomeTable.get(FOREST).treeCover();
        final BaselineGrid grid = uniformGrid(FOREST, 70);
        final DerivedGrid derived = BaselineDeriver.derive(grid);
        CanopyStylizer.apply(derived, grid, 2024L, lod, 40_000, -80_000);

        int foliage = 0;
        int total = 0;
        for (int z = 0; z < BaselineGrid.PIXELS; z++) {
            for (int x = 0; x < BaselineGrid.PIXELS; x++) {
                total++;
                if (derived.kind[BaselineGrid.index(x, z)] == (byte) SurfaceKind.FOLIAGE.ordinal()) {
                    foliage++;
                }
            }
        }
        final double fraction = (double) foliage / total;
        assertTrue(
            Math.abs(fraction - treeCover) <= 0.15,
            "lod " + lod + ": foliage fraction " + fraction + " too far from treeCover " + treeCover
        );
    }

    @Test
    void zeroTreeCoverBiomeNeverGetsFoliage() {
        final BaselineGrid grid = uniformGrid(2 /* desert */, 70);
        final DerivedGrid derived = BaselineDeriver.derive(grid);
        CanopyStylizer.apply(derived, grid, 1L, 0, 0, 0);
        for (final byte k : derived.kind) {
            assertTrue(k != (byte) SurfaceKind.FOLIAGE.ordinal());
        }
    }

    @Test
    void nativeCandidatePathPaintsTheReportedTreeInsteadOfSeedNoise() {
        final BaselineGrid grid = uniformGrid(FOREST, 70);
        final DerivedGrid derived = BaselineDeriver.derive(grid);
        final int[] queries = new int[1];
        final BaselineSampler sampler = treeSampler((chunkX, chunkZ, out) -> {
            queries[0]++;
            if (chunkX == 0 && chunkZ == 0) {
                out[0] = new TreeCandidate(8, 71, 8, 0, FOREST, 0);
                return 1;
            }
            return 0;
        });

        CanopyStylizer.apply(derived, grid, sampler, 99L, 0, 0, 0);

        assertTrue(queries[0] > 0);
        assertEquals((byte) SurfaceKind.FOLIAGE.ordinal(), derived.kind[BaselineGrid.index(8, 8)]);
        assertEquals(73, derived.surfaceY[BaselineGrid.index(8, 8)]);
        assertEquals((byte) SurfaceKind.LAND.ordinal(), derived.kind[BaselineGrid.index(100, 100)]);

        final BaselineGrid bambooGrid = uniformGrid(BAMBOO_JUNGLE, 70);
        final DerivedGrid bambooDerived = BaselineDeriver.derive(bambooGrid);
        final BaselineSampler bambooSampler = treeSampler((chunkX, chunkZ, out) -> {
            if (chunkX == 0 && chunkZ == 0) {
                out[0] = new TreeCandidate(8, 70, 8, 11, BAMBOO_JUNGLE, 14 << 16);
                return 1;
            }
            return 0;
        });

        CanopyStylizer.apply(bambooDerived, bambooGrid, bambooSampler, 99L, 0, 0, 0);

        assertEquals((byte) SurfaceKind.LAND.ordinal(), bambooDerived.kind[BaselineGrid.index(8, 8)]);
        assertEquals(85, bambooDerived.surfaceY[BaselineGrid.index(8, 8)]);
        assertEquals(70, bambooDerived.surfaceY[BaselineGrid.index(9, 8)]);
    }

    @Test
    void nativeJungleTreeUsesATallerCanopyEstimateThanAnOrdinaryTree() {
        final int ordinaryCanopyY = nativeCanopyY(FOREST, 0);
        final int jungleCanopyY = nativeCanopyY(JUNGLE, 3);

        assertTrue(jungleCanopyY > ordinaryCanopyY, "jungle canopy should be taller than an ordinary tree");
    }

    private static int nativeCanopyY(final int biomeId, final int treeType) {
        final BaselineGrid grid = uniformGrid(biomeId, 70);
        final DerivedGrid derived = BaselineDeriver.derive(grid);
        final BaselineSampler sampler = treeSampler((chunkX, chunkZ, out) -> {
            if (chunkX == 0 && chunkZ == 0) {
                out[0] = new TreeCandidate(8, 71, 8, treeType, biomeId, 0);
                return 1;
            }
            return 0;
        });

        CanopyStylizer.apply(derived, grid, sampler, 99L, 0, 0, 0);

        return derived.surfaceY[BaselineGrid.index(8, 8)];
    }

    @Test
    void unsupportedNativeFeatureFallsBackToTheExistingSyntheticResult() {
        final BaselineGrid grid = uniformGrid(FOREST, 70);
        final DerivedGrid expected = BaselineDeriver.derive(grid);
        CanopyStylizer.apply(expected, grid, 42L, 0, 0, 0);

        final DerivedGrid actual = BaselineDeriver.derive(grid);
        CanopyStylizer.apply(actual, grid, treeSampler((chunkX, chunkZ, out) -> BaselineSampler.TREES_UNSUPPORTED), 42L, 0, 0, 0);

        assertArrayEquals(expected.kind, actual.kind);
        assertArrayEquals(expected.surfaceY, actual.surfaceY);
    }

    @Test
    void unsupportedChunkFallsBackWithoutReplacingSupportedNeighbor() {
        final BaselineGrid grid = uniformGrid(FOREST, 70);
        final DerivedGrid synthetic = BaselineDeriver.derive(grid);
        CanopyStylizer.apply(synthetic, grid, 42L, 0, 0, 0);

        final DerivedGrid actual = BaselineDeriver.derive(grid);
        final BaselineSampler sampler = treeSampler((chunkX, chunkZ, out) -> {
            if (chunkX == 0 && chunkZ == 0) {
                out[0] = new TreeCandidate(8, 71, 8, 0, FOREST, 0);
                return 1;
            }
            return BaselineSampler.TREES_UNSUPPORTED;
        });

        CanopyStylizer.apply(actual, grid, sampler, 42L, 0, 0, 0);

        assertEquals((byte) SurfaceKind.FOLIAGE.ordinal(), actual.kind[BaselineGrid.index(8, 8)]);
        assertEquals(73, actual.surfaceY[BaselineGrid.index(8, 8)]);
        for (int z = 0; z < 16; z++) {
            for (int x = 16; x < 32; x++) {
                final int index = BaselineGrid.index(x, z);
                assertEquals(synthetic.kind[index], actual.kind[index]);
                assertEquals(synthetic.surfaceY[index], actual.surfaceY[index]);
            }
        }
    }

    private static BaselineSampler treeSampler(final TreeQuery query) {
        return new BaselineSampler() {
            @Override
            public boolean biomes(final int scale, final int x, final int z, final int w, final int h, final int[] out) {
                return false;
            }

            @Override
            public boolean heights(final int x4, final int z4, final int w, final int h, final int[] outY) {
                return false;
            }

            @Override
            public boolean endHeights(final int x4, final int z4, final int w, final int h, final int[] outY) {
                return false;
            }

            @Override
            public int treeCandidates(final int chunkX, final int chunkZ, final TreeCandidate[] out) {
                return query.query(chunkX, chunkZ, out);
            }
        };
    }

    @FunctionalInterface
    private interface TreeQuery {
        int query(int chunkX, int chunkZ, TreeCandidate[] out);
    }
}
