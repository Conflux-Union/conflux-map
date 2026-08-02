package cn.net.rms.confluxmap.mc.ui;

import cn.net.rms.confluxmap.compat.Ids;
import cn.net.rms.confluxmap.core.loadstate.FullscreenDisplayMode;
import net.minecraft.util.Identifier;

/** Texture identifiers representing the fullscreen map's client-selectable display modes. */
public final class DisplayModeIconCatalog {
    private static final Identifier TERRAIN = projectTexture("map_terrain.png");
    private static final Identifier CHUNK_LOAD_STATE = projectTexture("chunk_load_state.png");
    private static final Identifier CHUNK_LOAD_STATE_OFF = projectTexture("chunk_load_state_off.png");
    private static final Identifier BIOME = projectTexture("map_biome.png");
    private static final Identifier BIOME_OFF = projectTexture("map_biome_off.png");

    private DisplayModeIconCatalog() {
    }

    public static Identifier icon(final FullscreenDisplayMode mode) {
        return switch (mode) {
            case TERRAIN -> TERRAIN;
            case CHUNK_LOAD_STATE -> CHUNK_LOAD_STATE;
            case BIOME -> BIOME;
        };
    }

    /** Slashed variant for a layer the world or the server does not offer. */
    public static Identifier icon(final FullscreenDisplayMode mode, final boolean available) {
        if (available) {
            return icon(mode);
        }
        return switch (mode) {
            // The terrain layer is always available, so it owns no slashed variant.
            case TERRAIN -> TERRAIN;
            case CHUNK_LOAD_STATE -> CHUNK_LOAD_STATE_OFF;
            case BIOME -> BIOME_OFF;
        };
    }

    private static Identifier projectTexture(final String fileName) {
        return Ids.of("confluxmap", "textures/gui/" + fileName);
    }
}
