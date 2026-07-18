package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
}
