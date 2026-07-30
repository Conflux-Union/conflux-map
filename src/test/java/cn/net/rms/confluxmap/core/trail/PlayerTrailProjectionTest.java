package cn.net.rms.confluxmap.core.trail;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PlayerTrailProjectionTest {

    @Test
    void projectsWorldPositionsAtTheMapScaleAndRotation() {
        final PlayerTrailProjection projection = new PlayerTrailProjection(
            100.0, 200.0, 50.0, 50.0, 2.0, 90.0, 100.0, 100.0
        );

        assertEquals(
            new PlayerTrailProjection.ScreenPoint(50.0, 52.0),
            projection.project(new PlayerTrail.Sample(104.0, 200.0, 0L))
        );
        assertEquals(
            new PlayerTrailProjection.ScreenPoint(48.0, 50.0),
            projection.project(new PlayerTrail.Sample(100.0, 204.0, 0L))
        );
    }
}
