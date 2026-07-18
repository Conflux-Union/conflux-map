package cn.net.rms.confluxmap.core.predict;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-session color data for {@link PredictedTileComposer}: a small fixed set of per-{@link
 * cn.net.rms.confluxmap.core.model.SurfaceKind} base colors (copied from {@link BiomeTable}'s
 * compiled-in constants - never overridden, there's no live-game equivalent of "the sand
 * color") plus a per-biome-id tint table ({@code grassTint}/{@code foliageTint}/{@code
 * waterTint}) that starts at {@link BiomeTable}'s fallback and gets overwritten, once per
 * session, by {@code mc.predict.PredictionPaletteBuilder} sampling the client's live biome
 * registry. Palette data never affects which pixels are water/land/foliage (that's {@link
 * BaselineDeriver}/{@link CanopyStylizer}, from the baseline sample alone) - only their color.
 *
 * <p>Immutable once built: a session either uses {@link #defaults()} (before the builder runs,
 * or forever for a biome id the registry doesn't know) or a single {@link #fromSamples} snapshot
 * built fully on the main thread before being published for worker threads to read.
 */
public final class PredictionPalette {
    public final int landBase = BiomeTable.LAND_BASE;
    public final int sandBase = BiomeTable.SAND_BASE;
    public final int snowBase = BiomeTable.SNOW_BASE;
    public final int iceBase = BiomeTable.ICE_BASE;
    public final int waterBase = BiomeTable.WATER_BASE;
    public final int foliageBase = BiomeTable.FOLIAGE_BASE;

    /** biomeId -> {grassTint, foliageTint, waterTint}, opaque ARGB each. */
    private final Map<Integer, int[]> tints;

    private PredictionPalette(final Map<Integer, int[]> tints) {
        this.tints = tints;
    }

    /** Compiled-in {@link BiomeTable} fallbacks only - no live registry sample. */
    public static PredictionPalette defaults() {
        return new PredictionPalette(Map.of());
    }

    /** One immutable snapshot of every biome id the live client registry could resolve this session. */
    public static PredictionPalette fromSamples(final Map<Integer, int[]> sampledTints) {
        return new PredictionPalette(new HashMap<>(sampledTints));
    }

    public int grassTint(final int biomeId) {
        final int[] sampled = tints.get(biomeId);
        return sampled != null ? sampled[0] : BiomeTable.get(biomeId).grassTint();
    }

    public int foliageTint(final int biomeId) {
        final int[] sampled = tints.get(biomeId);
        return sampled != null ? sampled[1] : BiomeTable.get(biomeId).foliageTint();
    }

    public int waterTint(final int biomeId) {
        final int[] sampled = tints.get(biomeId);
        return sampled != null ? sampled[2] : BiomeTable.get(biomeId).waterTint();
    }
}
