package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.nativepredict.NativeLib;

/**
 * Everything M2 prediction needs to know about the current session, published by {@code
 * mc.predict.PredictionBootstrap} on session change: the recognized {@link WorldPreset} per
 * predicted dimension, the world seed when one is known (singleplayer, a granting companion, or
 * a client-entered multiplayer seed),
 * and - for a superflat overworld - the uniform {@link FlatBaseline} that replaces cubiomes
 * entirely. The three are independent: a superflat overworld predicts from its flat baseline
 * with no seed and no native library, while the End of that same world still needs the seeded
 * cubiomes path. All fields are {@code volatile}: written on the main thread (session start/end,
 * or once by {@code PredictionPaletteBuilder}), read from tile-composition worker threads.
 */
public final class PredictionState {
    private volatile boolean seedKnown;
    private volatile boolean manualSeed;
    private volatile long seed;
    private volatile int mcVersion = -1;
    private volatile WorldPreset overworldPreset = WorldPreset.DEFAULT;
    private volatile WorldPreset netherPreset = WorldPreset.DEFAULT;
    private volatile WorldPreset endPreset = WorldPreset.DEFAULT;
    private volatile FlatBaseline overworldFlatBaseline;
    private volatile PredictionPalette palette = PredictionPalette.defaults();

    /** Main thread, on session start: the recognized generator preset per predicted dimension. */
    public void setPresets(final WorldPreset overworldPreset, final WorldPreset endPreset) {
        setPresets(overworldPreset, WorldPreset.DEFAULT, endPreset);
    }

    /** Main thread, on session start: the recognized generator preset for all predicted dimensions. */
    public void setPresets(
        final WorldPreset overworldPreset,
        final WorldPreset netherPreset,
        final WorldPreset endPreset
    ) {
        this.overworldPreset = overworldPreset;
        this.netherPreset = netherPreset;
        this.endPreset = endPreset;
    }

    /** Main thread: a trusted seed became known from singleplayer or the companion handshake. */
    public void setSeed(final long seed, final int mcVersion) {
        this.manualSeed = false;
        this.seed = seed;
        this.mcVersion = mcVersion;
        this.seedKnown = true;
    }

    /** Main thread: use a client-entered seed, which must never consume persisted server corrections. */
    public void setManualSeed(final long seed, final int mcVersion) {
        setSeed(seed, mcVersion);
        manualSeed = true;
    }

    /** Main thread: the overworld is superflat with this uniform surface (seed-independent). */
    public void setFlatBaseline(final FlatBaseline baseline) {
        this.overworldFlatBaseline = baseline;
    }

    /** Main thread: nothing is known for the current session (multiplayer without a companion, or session end). */
    public void clear() {
        seedKnown = false;
        manualSeed = false;
        seed = 0L;
        mcVersion = -1;
        overworldPreset = WorldPreset.DEFAULT;
        netherPreset = WorldPreset.DEFAULT;
        endPreset = WorldPreset.DEFAULT;
        overworldFlatBaseline = null;
        palette = PredictionPalette.defaults();
    }

    /** Main thread, once per session after the seed is known: publishes the live-sampled palette. */
    public void setPalette(final PredictionPalette palette) {
        this.palette = palette;
    }

    public boolean seedKnown() {
        return seedKnown;
    }

    public long seed() {
        return seed;
    }

    public int mcVersion() {
        return mcVersion;
    }

    public boolean manualSeed() {
        return manualSeed;
    }

    public PredictionPalette palette() {
        return palette;
    }

    /** The recognized generator preset for {@code dimension} ({@link WorldPreset#DEFAULT} when unset/unsupported). */
    public WorldPreset preset(final DimensionId dimension) {
        if (dimension.equals(DimensionId.OVERWORLD)) {
            return overworldPreset;
        }
        if (dimension.equals(DimensionId.NETHER)) {
            return netherPreset;
        }
        return PredictionDimensions.isEnd(dimension) ? endPreset : WorldPreset.DEFAULT;
    }

    /** cubiomes {@code setupGenerator} flags for contexts predicting {@code dimension}. */
    public int cubiomesFlags(final DimensionId dimension) {
        return preset(dimension).cubiomesFlags();
    }

    /** The superflat overworld's uniform surface, or {@code null} when not flat / not yet known. */
    public FlatBaseline flatBaseline(final DimensionId dimension) {
        return dimension.equals(DimensionId.OVERWORLD) ? overworldFlatBaseline : null;
    }

    /**
     * Whether the seeded cubiomes pipeline can run for {@code dimension}: seed known, native
     * library loaded, and a generator cubiomes can model. Structure markers and the normal
     * sampled underlay both require this; the flat underlay does not.
     */
    public boolean cubiomesBacked(final DimensionId dimension) {
        return seedKnown
            && NativeLib.available()
            && PredictionDimensions.supported(dimension)
            && preset(dimension).predictable();
    }

    /** Whether cubiomes can locate vanilla structures in this dimension for the current seed. */
    public boolean structuresCubiomesBacked(final DimensionId dimension) {
        return seedKnown
            && NativeLib.available()
            && PredictionDimensions.structuresSupported(dimension)
            && preset(dimension).predictable();
    }

    /** Whether a predicted underlay can be produced for {@code dimension} right now. */
    public boolean predictable(final DimensionId dimension) {
        if (!PredictionDimensions.supported(dimension)) {
            return false;
        }
        if (preset(dimension) == WorldPreset.FLAT) {
            return flatBaseline(dimension) != null;
        }
        return cubiomesBacked(dimension);
    }
}
