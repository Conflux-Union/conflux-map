package cn.net.rms.confluxmap.core.net.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SharedWaypointAvailabilityTest {
    @Test
    void onlyEnabledStateExposesPublicControls() {
        for (final SharedWaypointClientState.State state : SharedWaypointClientState.State.values()) {
            final SharedWaypointAvailability availability = SharedWaypointAvailability.from(state, false);
            assertEquals(state == SharedWaypointClientState.State.ENABLED, availability.enabled());
            assertFalse(availability.ready());
        }
    }

    @Test
    void synchronizationOnlyMakesEnabledFeatureReady() {
        for (final SharedWaypointClientState.State state : SharedWaypointClientState.State.values()) {
            final SharedWaypointAvailability availability = SharedWaypointAvailability.from(state, true);
            assertEquals(state == SharedWaypointClientState.State.ENABLED, availability.enabled());
            assertEquals(availability.enabled(), availability.ready());
        }

        assertTrue(SharedWaypointAvailability.from(SharedWaypointClientState.State.ENABLED, true).ready());
    }
}
