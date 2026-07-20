package cn.net.rms.confluxmap.core.quality;

import cn.net.rms.confluxmap.core.color.ShadingPipeline;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.predict.MapColorTable;
import cn.net.rms.confluxmap.core.predict.PredictionPalette;
import cn.net.rms.confluxmap.core.util.Argb;

/** Renders generated map data into the same 2D map-color space used for quality comparison. */
public final class GeneratedTileComposer {
    private static final int SNOW_MAP_COLOR_ID = 8;

    public record Grid(
        int width,
        int height,
        short[] surfaceY,
        byte[] kind,
        byte[] fluidDepth,
        byte[] mapColorId,
        int[] biomeId
    ) {
        public Grid {
            final int length = Math.multiplyExact(width, height);
            if (width <= 0 || height <= 0
                || surfaceY.length != length
                || kind.length != length
                || fluidDepth.length != length
                || mapColorId.length != length
                || biomeId.length != length) {
                throw new IllegalArgumentException("generated tile arrays must match width * height");
            }
        }
    }

    private GeneratedTileComposer() {
    }

    public static PredictionQualityEvaluator.TileData compose(
        final Grid grid,
        final PredictionPalette palette
    ) {
        final int[] pixels = new int[grid.width() * grid.height()];
        final byte[] visibleKind = grid.kind().clone();
        for (int z = 0; z < grid.height(); z++) {
            for (int x = 0; x < grid.width(); x++) {
                final int index = z * grid.width() + x;
                final SurfaceKind kind = visibleKind(grid, index);
                visibleKind[index] = (byte) kind.ordinal();
                if (kind == SurfaceKind.UNKNOWN || kind == SurfaceKind.VOID) {
                    pixels[index] = Argb.TRANSPARENT;
                    continue;
                }
                final Integer neighborHeight = x > 0 && z + 1 < grid.height()
                    ? (int) grid.surfaceY()[(z + 1) * grid.width() + x - 1]
                    : null;
                final double shade = ShadingPipeline.combinedShade(
                    true,
                    true,
                    grid.surfaceY()[index],
                    ShadingPipeline.REFERENCE_HEIGHT,
                    neighborHeight
                );
                pixels[index] = ShadingPipeline.applyShade(baseColor(grid, palette, kind, index), shade);
            }
        }
        return new PredictionQualityEvaluator.TileData(
            grid.width(),
            grid.height(),
            pixels,
            grid.surfaceY().clone(),
            visibleKind,
            grid.fluidDepth().clone()
        );
    }

    private static SurfaceKind visibleKind(final Grid grid, final int index) {
        final SurfaceKind kind = SurfaceKind.byOrdinal(grid.kind()[index]);
        if (kind == SurfaceKind.LAND
            && Byte.toUnsignedInt(grid.mapColorId()[index]) == SNOW_MAP_COLOR_ID) {
            return SurfaceKind.SNOW;
        }
        return kind;
    }

    private static int baseColor(
        final Grid grid,
        final PredictionPalette palette,
        final SurfaceKind kind,
        final int index
    ) {
        final int mapColor = Byte.toUnsignedInt(grid.mapColorId()[index]);
        if (mapColor != Proto.MAP_COLOR_NONE) {
            return MapColorTable.argb(mapColor);
        }
        final int biome = grid.biomeId()[index];
        return switch (kind) {
            case WATER -> Argb.multiply(palette.waterBase, palette.waterTint(biome));
            case FOLIAGE -> Argb.multiply(palette.foliageBase, palette.foliageTint(biome));
            case SAND -> palette.sandBase;
            case SNOW -> palette.snowBase;
            case ICE -> palette.iceBase;
            default -> Argb.multiply(palette.landBase, palette.grassTint(biome));
        };
    }
}
