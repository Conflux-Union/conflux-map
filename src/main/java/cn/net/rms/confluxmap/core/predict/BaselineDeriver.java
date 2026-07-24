package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.model.SurfaceKind;

/**
 * Turns native-resolved base columns into render kinds, surface heights, and fluid depths. The
 * terrain generator owns whether a column contains visible fluid and at which Y; this class only
 * applies biome surface styling such as frozen-ocean ice and altitude snow. It runs over the whole
 * margin-inclusive grid so {@link CanopyStylizer} keeps tile-edge slope samples consistent.
 */
public final class BaselineDeriver {
    /** Top block of Vanilla's default Overworld sea-level fluid. */
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
            final int biomeId = grid.biomeId[i];
            final boolean resolvedSurface = grid.baseSurfaceY[i] != BaselineGrid.NO_SURFACE;
            final boolean compatibilityFluid = !resolvedSurface
                && terrainY < WATER_LEVEL
                && !BiomeTable.isEnd(biomeId);
            final int baseSurfaceY = resolvedSurface
                ? grid.baseSurfaceY[i]
                : compatibilityFluid ? WATER_LEVEL : terrainY;
            if ((grid.surfaceFlags[i] & BaselineGrid.SURFACE_FLUID) != 0 || compatibilityFluid) {
                final boolean coldSurface = entry.kind() == SurfaceKind.ICE || entry.kind() == SurfaceKind.SNOW;
                final int localX = i % BaselineGrid.SIZE - BaselineGrid.MARGIN;
                final int localZ = i / BaselineGrid.SIZE - BaselineGrid.MARGIN;
                final SurfaceKind surface = coldSurface
                    && FrozenOceanTemperature.freezesAtSeaLevel(biomeId, grid.blockX(localX), grid.blockZ(localZ))
                    ? SurfaceKind.ICE
                    : SurfaceKind.WATER;
                out.kind[i] = (byte) surface.ordinal();
                out.surfaceY[i] = baseSurfaceY;
                out.fluidDepth[i] = Math.max(0, Math.min(baseSurfaceY - terrainY, 255));
            } else {
                final SurfaceKind kind = entry.kind() == SurfaceKind.LAND
                    && BiomeTable.hasAltitudeSnow(grid.biomeId[i], baseSurfaceY)
                    ? SurfaceKind.SNOW
                    : entry.kind();
                out.kind[i] = (byte) kind.ordinal();
                out.surfaceY[i] = baseSurfaceY;
                out.fluidDepth[i] = 0;
            }
        }
        return out;
    }
}
