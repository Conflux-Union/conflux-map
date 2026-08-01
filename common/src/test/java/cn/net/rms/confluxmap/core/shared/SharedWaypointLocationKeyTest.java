package cn.net.rms.confluxmap.core.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.net.rms.confluxmap.core.model.DimensionId;
import org.junit.jupiter.api.Test;

class SharedWaypointLocationKeyTest {
    @Test
    void coordinatesInTheSameBlockHaveTheSameKeyIncludingNegativeCoordinates() {
        assertEquals(
            SharedWaypointLocationKey.from(DimensionId.OVERWORLD, 12.01d, 64.99d, -8.01d),
            SharedWaypointLocationKey.from(DimensionId.OVERWORLD, 12.99d, 64.01d, -8.99d)
        );
        assertEquals(-9L,
            SharedWaypointLocationKey.from(DimensionId.OVERWORLD, 0d, 0d, -8.01d).blockZ());
    }

    @Test
    void dimensionAndHeightRemainPartOfTheLocationIdentity() {
        assertNotEquals(
            SharedWaypointLocationKey.from(DimensionId.OVERWORLD, 12d, 64d, -8d),
            SharedWaypointLocationKey.from(DimensionId.NETHER, 12d, 64d, -8d)
        );
        assertNotEquals(
            SharedWaypointLocationKey.from(DimensionId.OVERWORLD, 12d, 64d, -8d),
            SharedWaypointLocationKey.from(DimensionId.OVERWORLD, 12d, 65d, -8d)
        );
    }

    @Test
    void nonFiniteCoordinatesAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> SharedWaypointLocationKey.from(DimensionId.OVERWORLD, Double.NaN, 64d, 0d));
    }
}
