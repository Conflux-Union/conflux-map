package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class StructureSearchBulkVisibilityTest {
    @Test
    void checkedMasterCheckboxClearsTheCurrentResults() {
        final StructureSelectionState state = new StructureSelectionState(4, 4);

        assertEquals("✓", state.mark());
        assertFalse(state.visibilityAfterToggle());
    }

    @Test
    void emptyMasterCheckboxSelectsTheCurrentResults() {
        final StructureSelectionState state = new StructureSelectionState(0, 4);

        assertEquals("", state.mark());
        assertTrue(state.visibilityAfterToggle());
    }

    @Test
    void partialMasterCheckboxShowsMixedStateAndSelectsTheCurrentResults() {
        final StructureSelectionState state = new StructureSelectionState(2, 4);

        assertEquals("−", state.mark());
        assertTrue(state.visibilityAfterToggle());
    }

    @Test
    void masterCheckboxIsDisabledWhenPolicyDeniesItOrNoResultsExist() {
        assertFalse(new StructureSelectionState(0, 0).enabled(true));
        assertFalse(new StructureSelectionState(2, 4).enabled(false));
        assertTrue(new StructureSelectionState(2, 4).enabled(true));
    }
}
