package cn.net.rms.confluxmap.core.loadstate;

/** Client-selectable fullscreen-map base-plane choices. */
public enum FullscreenDisplayMode {
    TERRAIN,
    CHUNK_LOAD_STATE,
    BIOME;

    /** Advances in UI order, skipping the server-authoritative mode when it is unavailable. */
    public FullscreenDisplayMode next(final boolean chunkLoadStateAvailable) {
        return next(chunkLoadStateAvailable, true);
    }

    /** Advances in UI order, skipping every mode unavailable under the current server policy. */
    public FullscreenDisplayMode next(
        final boolean chunkLoadStateAvailable,
        final boolean biomeMapAvailable
    ) {
        FullscreenDisplayMode candidate = this;
        for (int checked = 0; checked < values().length; checked++) {
            candidate = values()[(candidate.ordinal() + 1) % values().length];
            if (candidate == CHUNK_LOAD_STATE && !chunkLoadStateAvailable) {
                continue;
            }
            if (candidate == BIOME && !biomeMapAvailable) {
                continue;
            }
            return candidate;
        }
        return this;
    }
}
