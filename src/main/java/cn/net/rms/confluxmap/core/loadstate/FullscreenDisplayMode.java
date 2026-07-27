package cn.net.rms.confluxmap.core.loadstate;

/** Client-selectable fullscreen-map base-plane choices. */
public enum FullscreenDisplayMode {
    TERRAIN,
    CHUNK_LOAD_STATE,
    BIOME;

    /** Advances in UI order, skipping the server-authoritative mode when it is unavailable. */
    public FullscreenDisplayMode next(final boolean chunkLoadStateAvailable) {
        FullscreenDisplayMode next = values()[(ordinal() + 1) % values().length];
        if (!chunkLoadStateAvailable && next == CHUNK_LOAD_STATE) {
            next = values()[(next.ordinal() + 1) % values().length];
        }
        return next;
    }
}
