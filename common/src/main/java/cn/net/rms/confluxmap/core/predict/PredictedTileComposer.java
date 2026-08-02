package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.color.ShadingPipeline;
import cn.net.rms.confluxmap.core.model.MapPixel;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.util.Argb;
import cn.net.rms.confluxmap.core.util.TileMath;
import java.util.Arrays;

/**
 * Turns a {@link DerivedGrid} (+ the {@link BaselineGrid} it was derived from, for biome ids)
 * into a 256x256 ARGB pixel array. The prediction plane layers LOD-normalized directional relief
 * over the captured-map absolute-height curve, and renders water as a translucent surface color
 * pre-composited over an approximated seafloor (see
 * {@link #seafloorColor}, since there is no real seafloor block to sample - cubiomes gives biome +
 * terrain height only). Day/night lighting is deliberately not baked into these cached pixels:
 * the fullscreen renderer applies one global tint to the entire predicted plane, so tiles composed
 * at different times stay identical.
 *
 * <p>Deterministic: every input here is already-sampled/derived data plus a per-session {@link
 * PredictionPalette}; no randomness, no wall-clock or otherwise non-reproducible state.
 */
public final class PredictedTileComposer {
    /** Opaque sand/dirt-like stand-in for an unseen seafloor, darkened by {@link #seafloorColor}. */
    private static final int SEAFLOOR_BASE = 0xFFC2A876;
    /** Vanilla map colour GRASS: what a grass block reports regardless of the biome tinting it. */
    private static final int GRASS_MAP_COLOR = 1;
    /** Vanilla map colour PLANT: leaves and ground plants, likewise biome-tinted in the world. */
    private static final int PLANT_MAP_COLOR = 7;

    private PredictedTileComposer() {
    }

    public static int[] compose(
        final DerivedGrid derived,
        final BaselineGrid grid,
        final PredictionPalette palette
    ) {
        return compose(derived, grid, palette, null, PredictionViewMode.EVERYWHERE, 0);
    }

    /** Composes with an optional absolute correction overlay and generated-only mask. */
    public static int[] compose(
        final DerivedGrid derived,
        final BaselineGrid grid,
        final PredictionPalette palette,
        final CorrectionTile corrections,
        final PredictionViewMode viewMode,
        final int lod
    ) {
        return compose(derived, grid, palette, corrections, viewMode, lod, Proto.MAP_COLOR_NONE);
    }

    /**
     * Full form: {@code baselineMapColorId} pins every non-water, non-corrected baseline pixel to
     * one vanilla map color instead of the biome palette - a superflat underlay renders the
     * actual top-layer block (stone, sandstone, ...) this way, matching the color corrections
     * would use for the same block. {@link Proto#MAP_COLOR_NONE} keeps the biome-palette path, as
     * do the biome-tinted map colors {@link #paintsFromMapColor} excludes.
     */
    public static int[] compose(
        final DerivedGrid derived,
        final BaselineGrid grid,
        final PredictionPalette palette,
        final CorrectionTile corrections,
        final PredictionViewMode viewMode,
        final int lod,
        final int baselineMapColorId
    ) {
        return compose(
            derived, grid, palette, corrections, viewMode, lod, baselineMapColorId,
            derived, grid, baselineMapColorId
        );
    }

    /**
     * Composes a residual received against a possibly older wire baseline. Evaluated pixels that
     * have no explicit difference record reconstruct from that source baseline, while unread
     * pixels continue to use the newest local prediction.
     */
    public static int[] compose(
        final DerivedGrid derived,
        final BaselineGrid grid,
        final PredictionPalette palette,
        final CorrectionTile corrections,
        final PredictionViewMode viewMode,
        final int lod,
        final int baselineMapColorId,
        final DerivedGrid correctionDerived,
        final BaselineGrid correctionGrid,
        final int correctionBaselineMapColorId
    ) {
        final int size = BaselineGrid.PIXELS;
        final int[] out = new int[size * size];
        final int[] surface = derived.surfaceY.clone();
        final byte[] kinds = derived.kind.clone();
        final int[] fluids = derived.fluidDepth.clone();
        final int[] biomes = grid.biomeId.clone();
        final int[] colors = new int[size * size];
        final int[] floorColors = new int[size * size];
        Arrays.fill(floorColors, MapPixel.MAP_COLOR_NONE);
        final boolean[] corrected = new boolean[size * size];
        if (corrections != null) {
            final PatchCodec.Patch correctionPatch = corrections.copyPatch();
            if (corrections.patchMode() == Proto.PATCH_MODE_RESIDUAL
                && correctionDerived != null && correctionGrid != null) {
                System.arraycopy(
                    correctionDerived.surfaceY, 0, surface, 0, surface.length
                );
                System.arraycopy(correctionDerived.kind, 0, kinds, 0, kinds.length);
                System.arraycopy(
                    correctionDerived.fluidDepth, 0, fluids, 0, fluids.length
                );
                System.arraycopy(correctionGrid.biomeId, 0, biomes, 0, biomes.length);
                final byte[] sourceEvaluated = correctionPatch.evaluated();
                for (int pixel = 0; pixel < out.length; pixel++) {
                    final int gridIndex = BaselineGrid.index(pixel & 255, pixel >>> 8);
                    if ((sourceEvaluated[pixel >>> 3] & (1 << (pixel & 7))) == 0) {
                        surface[gridIndex] = derived.surfaceY[gridIndex];
                        kinds[gridIndex] = derived.kind[gridIndex];
                        fluids[gridIndex] = derived.fluidDepth[gridIndex];
                        biomes[gridIndex] = grid.biomeId[gridIndex];
                        continue;
                    }
                    colors[pixel] = correctionBaselineMapColorId;
                    corrected[pixel] = true;
                }
            }
            for (final PatchCodec.Sample sample : correctionPatch.samples()) {
                final int pixel = sample.pixelIndex();
                final SurfaceKind correctedKind = SurfaceKind.byOrdinal(sample.kind());
                // UNKNOWN means the server summary did not have a usable surface column (most
                // commonly a structure_starts chunk without heightmaps). It is not authoritative
                // terrain and must never erase the deterministic baseline underneath it.
                if (correctedKind == SurfaceKind.UNKNOWN) {
                    final int gridIndex = BaselineGrid.index(pixel & 255, pixel >>> 8);
                    surface[gridIndex] = derived.surfaceY[gridIndex];
                    kinds[gridIndex] = derived.kind[gridIndex];
                    fluids[gridIndex] = derived.fluidDepth[gridIndex];
                    biomes[gridIndex] = grid.biomeId[gridIndex];
                    colors[pixel] = MapPixel.MAP_COLOR_NONE;
                    floorColors[pixel] = MapPixel.MAP_COLOR_NONE;
                    corrected[pixel] = false;
                    continue;
                }
                final int gridIndex = BaselineGrid.index(pixel & 255, pixel >>> 8);
                surface[gridIndex] = sample.surfaceY();
                kinds[gridIndex] = (byte) sample.kind();
                fluids[gridIndex] = sample.fluidDepth();
                biomes[gridIndex] = sample.biomeId();
                colors[pixel] = sample.mapColorId();
                floorColors[pixel] = sample.floorMapColorId();
                corrected[pixel] = true;
            }
        }
        final int[] floorSurface = surface.clone();
        for (int i = 0; i < floorSurface.length; i++) {
            if (SurfaceKind.byOrdinal(kinds[i]) == SurfaceKind.WATER) {
                floorSurface[i] -= fluids[i] & 0xFF;
            }
        }
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                final int idx = BaselineGrid.index(x, z);
                final int outIdx = z * size + x;
                if (!viewMode.showsPredictedPixels(corrections, outIdx, lod)) {
                    out[outIdx] = Argb.TRANSPARENT;
                    continue;
                }
                final SurfaceKind kind = SurfaceKind.byOrdinal(kinds[idx]);
                if (kind == SurfaceKind.UNKNOWN || kind == SurfaceKind.VOID) {
                    out[outIdx] = Argb.TRANSPARENT;
                    continue;
                }

                final double reliefMultiplier = ShadingPipeline.directionalReliefMultiplier(
                    slopeSampleHeight(surface, kinds, x - 1, z),
                    slopeSampleHeight(surface, kinds, x, z + 1),
                    slopeSampleHeight(surface, kinds, x - 1, z + 1),
                    slopeSampleHeight(surface, kinds, x + 1, z),
                    slopeSampleHeight(surface, kinds, x, z - 1),
                    slopeSampleHeight(surface, kinds, x + 1, z - 1),
                    TileMath.blocksPerPixel(lod)
                );
                final double floorReliefMultiplier = kind == SurfaceKind.WATER
                    ? ShadingPipeline.directionalReliefMultiplier(
                        slopeSampleHeight(floorSurface, kinds, x - 1, z),
                        slopeSampleHeight(floorSurface, kinds, x, z + 1),
                        slopeSampleHeight(floorSurface, kinds, x - 1, z + 1),
                        slopeSampleHeight(floorSurface, kinds, x + 1, z),
                        slopeSampleHeight(floorSurface, kinds, x, z - 1),
                        slopeSampleHeight(floorSurface, kinds, x + 1, z - 1),
                        TileMath.blocksPerPixel(lod)
                    )
                    : 1.0;
                // slopeAlsoActive: the directional relief above is this plane's slope term, so the
                // height curve takes the spec's gentler combined K - the same one the captured map
                // uses, since it always runs height and slope together. Predating the relief term,
                // this used to pass false, which left the underlay on the steeper height-only curve:
                // invisible near the Y=80 reference, but at a superflat's Y=-61 it darkened the
                // predicted plane to roughly half the brightness of the captured map beside it.
                final double heightShade = ShadingPipeline.detailedHeightShade(
                    surface[idx], ShadingPipeline.REFERENCE_HEIGHT
                );
                final int composed = corrected[outIdx] || !grid.supersampled()
                    ? baseColor(kind, biomes[idx], fluids[idx], palette,
                        corrected[outIdx], colors[outIdx], floorColors[outIdx], baselineMapColorId,
                        floorReliefMultiplier)
                    : averagedSubColor(derived, grid, palette, idx, baselineMapColorId);
                final int materialDetailed = palette.applyMaterialDetail(
                    kind, composed, grid.blockX(x), grid.blockZ(z)
                );
                final int heightShaded = ShadingPipeline.applyShade(materialDetailed, heightShade);
                out[outIdx] = ShadingPipeline.applyBrightnessMultiplier(heightShaded, reliefMultiplier);
            }
        }
        return out;
    }

    /** One column's colour before height shading and relief. */
    private static int baseColor(
        final SurfaceKind kind,
        final int biomeId,
        final int fluidDepth,
        final PredictionPalette palette,
        final boolean corrected,
        final int correctedMapColorId,
        final int correctedFloorMapColorId,
        final int baselineMapColorId,
        final double floorReliefMultiplier
    ) {
        if (kind == SurfaceKind.WATER) {
            // Keep ocean/river water unified: cubiomes' coarse biome grid otherwise fractures
            // one body of water along warm/cold variant boundaries. Water formed inside a land
            // biome still needs that biome's tint, most visibly the murky swamp water color.
            final int waterTint = BiomeTable.get(biomeId).waterBiome()
                ? BiomeTable.DEFAULT_WATER_TINT
                : palette.waterTint(biomeId);
            final int water = Argb.multiply(palette.waterBase, waterTint);
            final int floor = corrected && paintsFromMapColor(correctedFloorMapColorId)
                ? MapColorTable.argb(correctedFloorMapColorId)
                : SEAFLOOR_BASE;
            return ShadingPipeline.compositeOver(
                water,
                ShadingPipeline.applyBrightnessMultiplier(
                    seafloorColor(fluidDepth, floor), floorReliefMultiplier
                )
            );
        }
        if (corrected && paintsFromMapColor(correctedMapColorId)) {
            return MapColorTable.argb(correctedMapColorId);
        }
        if (!corrected && paintsFromMapColor(baselineMapColorId)) {
            return MapColorTable.argb(baselineMapColorId);
        }
        return colorFor(kind, biomeId, palette);
    }

    /**
     * Box-filters one pixel's biome sub-samples into a single colour, the same operator the
     * captured map's {@code TileService.downsample} applies when it averages finer pixels into a
     * coarse tile. This is what keeps a river narrower than one output pixel visible: it survives
     * as a fraction of the blend instead of being hit or missed by a single point sample.
     *
     * <p>Sub-samples never carry corrections - a correction is authoritative for its exact pixel,
     * so the caller routes corrected pixels through {@link #baseColor} instead.
     */
    private static int averagedSubColor(
        final DerivedGrid derived,
        final BaselineGrid grid,
        final PredictionPalette palette,
        final int idx,
        final int baselineMapColorId
    ) {
        int a = 0;
        int r = 0;
        int g = 0;
        int b = 0;
        final int count = grid.subCount();
        for (int sz = 0; sz < grid.subPerAxis; sz++) {
            for (int sx = 0; sx < grid.subPerAxis; sx++) {
                final int s = grid.subIndex(idx, sx, sz);
                final int color = baseColor(
                    SurfaceKind.byOrdinal(derived.subKind[s]), grid.subBiomeId[s],
                    derived.subFluidDepth[s], palette, false, Proto.MAP_COLOR_NONE,
                    MapPixel.MAP_COLOR_NONE, baselineMapColorId, 1.0
                );
                a += Argb.alpha(color);
                r += Argb.red(color);
                g += Argb.green(color);
                b += Argb.blue(color);
            }
        }
        return Argb.pack(a / count, r / count, g / count, b / count);
    }

    /**
     * Whether a declared vanilla map colour should be painted literally from {@link MapColorTable}
     * rather than through the biome palette.
     *
     * <p>Most map colours are a fair stand-in for the block they belong to, so a correction (or a
     * superflat top layer) of stone, sandstone or terracotta paints straight from the table. Grass
     * and plant blocks are the exception: their textures are greyscale and the game tints them per
     * biome, while the map colour is one fixed saturated green for every biome on earth. Painting
     * those literally put a bright green plate next to the captured map's biome-tinted greens -
     * most visibly across a whole superflat overworld, where the flat baseline covers every
     * unexplored pixel. Routing them back through {@link #colorFor} reuses the same live
     * grass/foliage sample {@code mc.color.BiomeTintResolver} feeds the captured map.
     */
    private static boolean paintsFromMapColor(final int mapColorId) {
        return mapColorId != Proto.MAP_COLOR_NONE
            && mapColorId != GRASS_MAP_COLOR
            && mapColorId != PLANT_MAP_COLOR;
    }

    private static int colorFor(final SurfaceKind kind, final int biomeId, final PredictionPalette palette) {
        switch (kind) {
            case SAND:
                return palette.sandBase;
            case SNOW:
                return palette.snowBase;
            case ICE:
                return palette.iceBase;
            case FOLIAGE:
                return palette.canopyColor(biomeId);
            case LAND:
            default:
                return palette.groundColor(biomeId);
        }
    }

    /** Depth-darkened stand-in for a seafloor cubiomes never actually tells us about. */
    private static int seafloorColor(final int fluidDepth, final int floorColor) {
        return Argb.scale(floorColor, ShadingPipeline.seafloorBrightness(fluidDepth));
    }

    /** Height at one slope sample, with void/unknown boundaries treated as unavailable. */
    private static Integer slopeSampleHeight(
        final int[] surface,
        final byte[] kinds,
        final int x,
        final int z
    ) {
        final int idx = BaselineGrid.index(x, z);
        final SurfaceKind neighborKind = SurfaceKind.byOrdinal(kinds[idx]);
        if (neighborKind == SurfaceKind.VOID || neighborKind == SurfaceKind.UNKNOWN) {
            return null;
        }
        return surface[idx];
    }

}
