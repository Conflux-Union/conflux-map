package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.nativepredict.NativeLib;

/**
 * Everything M2 prediction needs to know about the current session: whether a seed is known
 * (singleplayer only this slice - {@code mc.predict.PredictionBootstrap} sets it; multiplayer
 * leaves it cleared until S3), which cubiomes {@code MCVersion} int the worldgen matches, and
 * the current session's {@link PredictionPalette}. All fields are {@code volatile}: written on
 * the main thread (session start/end, or once by {@code PredictionPaletteBuilder}), read from
 * tile-composition worker threads.
 */
public final class PredictionState {
    private volatile boolean seedKnown;
    private volatile long seed;
    private volatile int mcVersion = -1;
    private volatile PredictionPalette palette = PredictionPalette.defaults();

    /** Main thread: a seed became known for the current session (singleplayer join, or S3's multiplayer handshake). */
    public void set(final long seed, final int mcVersion) {
        this.seed = seed;
        this.mcVersion = mcVersion;
        this.seedKnown = true;
    }

    /** Main thread: no seed for the current session (multiplayer without a companion, or session end). */
    public void clear() {
        seedKnown = false;
        mcVersion = -1;
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

    public PredictionPalette palette() {
        return palette;
    }

    /** Whether a predicted underlay can be produced for {@code dimension} right now. */
    public boolean predictable(final DimensionId dimension) {
        return seedKnown && NativeLib.available() && PredictionDimensions.supported(dimension);
    }
}
