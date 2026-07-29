package cn.net.rms.confluxmap.core.loadstate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FullscreenDisplayModeTest {
    @Test
    void cyclesTerrainLoadStateBiomeWhenCompanionAllowsLoadState() {
        assertEquals(
            FullscreenDisplayMode.CHUNK_LOAD_STATE,
            FullscreenDisplayMode.TERRAIN.next(true)
        );
        assertEquals(
            FullscreenDisplayMode.BIOME,
            FullscreenDisplayMode.CHUNK_LOAD_STATE.next(true)
        );
        assertEquals(
            FullscreenDisplayMode.TERRAIN,
            FullscreenDisplayMode.BIOME.next(true)
        );
    }

    @Test
    void skipsLoadStateWhenCompanionDoesNotAllowIt() {
        assertEquals(FullscreenDisplayMode.BIOME, FullscreenDisplayMode.TERRAIN.next(false));
        assertEquals(FullscreenDisplayMode.TERRAIN, FullscreenDisplayMode.BIOME.next(false));
        assertEquals(
            FullscreenDisplayMode.BIOME,
            FullscreenDisplayMode.CHUNK_LOAD_STATE.next(false)
        );
    }

    @Test
    void skipsBiomeWhenServerDoesNotAllowIt() {
        assertEquals(
            FullscreenDisplayMode.CHUNK_LOAD_STATE,
            FullscreenDisplayMode.TERRAIN.next(true, false)
        );
        assertEquals(
            FullscreenDisplayMode.TERRAIN,
            FullscreenDisplayMode.CHUNK_LOAD_STATE.next(true, false)
        );
        assertEquals(
            FullscreenDisplayMode.TERRAIN,
            FullscreenDisplayMode.TERRAIN.next(false, false)
        );
    }
}
