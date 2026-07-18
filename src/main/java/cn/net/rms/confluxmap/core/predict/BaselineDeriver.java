package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.model.SurfaceKind;

/**
 * Turns a {@link BaselineGrid} (raw biome id + terrain height) into a {@link DerivedGrid}
 * (kind/surfaceY/fluidDepth), per the plan's determinism spec water rule: a column below sea
 * level in an oceanic/river biome renders as flooded water at sea level, with a fluid depth
 * clamped to a byte; every other column keeps its own terrain height and takes its kind
 * straight from {@link BiomeTable}. Runs over the whole margin-inclusive grid (not just the
 * pixels that get rendered) so {@link CanopyStylizer} can stylize the margin too, keeping the
 * slope-shading neighbor at a tile's own west/south edge consistent with an ordinary pixel.
 */
public final class BaselineDeriver {
    /** 1.17.1 sea level. */
    public static final int WATER_LEVEL = 62;

    private BaselineDeriver() {
    }

    public static DerivedGrid derive(final BaselineGrid grid) {
        final DerivedGrid out = new DerivedGrid();
        final int n = grid.terrainY.length;
        for (int i = 0; i < n; i++) {
            final int terrainY = grid.terrainY[i];
            if (terrainY == BaselineGrid.NO_SURFACE) {
                out.kind[i] = (byte) SurfaceKind.VOID.ordinal();
                out.surfaceY[i] = terrainY;
                out.fluidDepth[i] = 0;
                continue;
            }
            final BiomeTable.Entry entry = BiomeTable.get(grid.biomeId[i]);
            if (terrainY < WATER_LEVEL && entry.waterBiome()) {
                out.kind[i] = (byte) SurfaceKind.WATER.ordinal();
                out.surfaceY[i] = WATER_LEVEL;
                out.fluidDepth[i] = Math.min(WATER_LEVEL - terrainY, 255);
            } else {
                out.kind[i] = (byte) entry.kind().ordinal();
                out.surfaceY[i] = terrainY;
                out.fluidDepth[i] = 0;
            }
        }
        return out;
    }
}
