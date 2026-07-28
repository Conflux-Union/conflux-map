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
        return deriveWindow(
            grid,
            -BaselineGrid.MARGIN,
            -BaselineGrid.MARGIN,
            BaselineGrid.PIXELS - 1 + BaselineGrid.MARGIN,
            BaselineGrid.PIXELS - 1 + BaselineGrid.MARGIN
        );
    }

    public static DerivedGrid deriveWindow(
        final BaselineGrid grid,
        final int minPixelX,
        final int minPixelZ,
        final int maxPixelX,
        final int maxPixelZ
    ) {
        if (grid == null || minPixelX < -BaselineGrid.MARGIN
            || minPixelZ < -BaselineGrid.MARGIN
            || maxPixelX >= BaselineGrid.PIXELS + BaselineGrid.MARGIN
            || maxPixelZ >= BaselineGrid.PIXELS + BaselineGrid.MARGIN
            || minPixelX > maxPixelX || minPixelZ > maxPixelZ) {
            throw new IllegalArgumentException("invalid baseline derivation window");
        }
        final DerivedGrid out = new DerivedGrid(grid.subPerAxis);
        for (int localZ = minPixelZ; localZ <= maxPixelZ; localZ++) {
            for (int localX = minPixelX; localX <= maxPixelX; localX++) {
                final int i = BaselineGrid.index(localX, localZ);
                deriveColumn(
                    grid.terrainY[i], grid.biomeId[i], grid.baseSurfaceY[i], grid.surfaceFlags[i],
                    grid.blockX(localX), grid.blockZ(localZ),
                    out.kind, out.surfaceY, out.fluidDepth, i
                );
                if (!grid.supersampled()) {
                    continue;
                }
                for (int sz = 0; sz < grid.subPerAxis; sz++) {
                    for (int sx = 0; sx < grid.subPerAxis; sx++) {
                        final int s = grid.subIndex(i, sx, sz);
                        // Terrain height is shared with the pixel centre by design; only the biome and
                        // the fluid classification that follows from it vary per sub-sample.
                        deriveColumn(
                            grid.terrainY[i], grid.subBiomeId[s], grid.subBaseSurfaceY[s],
                            grid.subSurfaceFlags[s],
                            grid.subBlockX(localX, sx), grid.subBlockZ(localZ, sz),
                            out.subKind, out.subSurfaceY, out.subFluidDepth, s
                        );
                    }
                }
            }
        }
        return out;
    }

    /** One column's water/land classification, shared by the per-pixel and per-sub-sample passes. */
    private static void deriveColumn(
        final int terrainY,
        final int biomeId,
        final int rawBaseSurfaceY,
        final int surfaceFlags,
        final int blockX,
        final int blockZ,
        final byte[] outKind,
        final int[] outSurfaceY,
        final int[] outFluidDepth,
        final int outIndex
    ) {
        if (terrainY == BaselineGrid.NO_SURFACE) {
            outKind[outIndex] = (byte) SurfaceKind.VOID.ordinal();
            outSurfaceY[outIndex] = terrainY;
            outFluidDepth[outIndex] = 0;
            return;
        }
        final BiomeTable.Entry entry = BiomeTable.get(biomeId);
        final boolean resolvedSurface = rawBaseSurfaceY != BaselineGrid.NO_SURFACE;
        final boolean compatibilityFluid = !resolvedSurface
            && terrainY < WATER_LEVEL
            && !BiomeTable.isEnd(biomeId);
        final int baseSurfaceY = resolvedSurface
            ? rawBaseSurfaceY
            : compatibilityFluid ? WATER_LEVEL : terrainY;
        if ((surfaceFlags & BaselineGrid.SURFACE_FLUID) != 0 || compatibilityFluid) {
            final boolean coldSurface = entry.kind() == SurfaceKind.ICE || entry.kind() == SurfaceKind.SNOW;
            final SurfaceKind surface = coldSurface
                && FrozenOceanTemperature.freezesAtSeaLevel(biomeId, blockX, blockZ)
                ? SurfaceKind.ICE
                : SurfaceKind.WATER;
            outKind[outIndex] = (byte) surface.ordinal();
            outSurfaceY[outIndex] = baseSurfaceY;
            outFluidDepth[outIndex] = Math.max(0, Math.min(baseSurfaceY - terrainY, 255));
        } else {
            final SurfaceKind kind = entry.kind() == SurfaceKind.LAND
                && BiomeTable.hasAltitudeSnow(biomeId, baseSurfaceY)
                ? SurfaceKind.SNOW
                : entry.kind();
            outKind[outIndex] = (byte) kind.ordinal();
            outSurfaceY[outIndex] = baseSurfaceY;
            outFluidDepth[outIndex] = 0;
        }
    }
}
