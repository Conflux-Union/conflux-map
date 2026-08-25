package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WaypointShareConfirmScreenTest {
    @Test
    void sharedWaypointShareDoesNotOfferPublicPublishingAgain() {
        assertFalse(WaypointShareConfirmScreen.shouldShowPublicTarget(false, true));
        assertTrue(WaypointShareConfirmScreen.shouldShowPublicTarget(true, true));
        assertFalse(WaypointShareConfirmScreen.shouldShowPublicTarget(true, false));
    }
}
