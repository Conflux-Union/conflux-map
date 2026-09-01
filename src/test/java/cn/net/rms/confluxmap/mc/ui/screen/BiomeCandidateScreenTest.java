package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BiomeCandidateScreenTest {
    @Test
    void statusAppearsAboveTheBottomControls() {
        assertEquals(204, BiomeCandidateScreen.statusY(240));
    }

    @Test
    void searchButtonReflectsWhetherAQueryIsRunning() {
        assertEquals(
            "confluxmap.screen.structure_candidates.search",
            BiomeCandidateScreen.searchButtonKey(false)
        );
        assertEquals(
            "confluxmap.screen.biome_candidates.searching_button",
            BiomeCandidateScreen.searchButtonKey(true)
        );
    }

}
