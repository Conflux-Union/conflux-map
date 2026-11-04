package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.core.model.DimensionId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class SearchCenterTest {
    private static final int MAP_CENTER_X = 1234;
    private static final int MAP_CENTER_Z = -567;

    @Test
    void defaultsToCameraBlockPositionWhenCameraIsInSearchedDimension() {
        final PlayerView camera = new PlayerView(
            10.9, 64.0, -3.2, 69.9, 0f, DimensionId.OVERWORLD
        );
        final SearchCenter center = SearchCenter.defaultCenter(
            Optional.of(camera), DimensionId.OVERWORLD, MAP_CENTER_X, MAP_CENTER_Z
        );
        assertEquals(10, center.blockX());
        assertEquals(-4, center.blockZ());
    }

    @Test
    void fallsBackToMapViewCenterWhenCameraIsInAnotherDimension() {
        final PlayerView camera = new PlayerView(
            10.9, 64.0, -3.2, 69.9, 0f, DimensionId.NETHER
        );
        final SearchCenter center = SearchCenter.defaultCenter(
            Optional.of(camera), DimensionId.OVERWORLD, MAP_CENTER_X, MAP_CENTER_Z
        );
        assertEquals(MAP_CENTER_X, center.blockX());
        assertEquals(MAP_CENTER_Z, center.blockZ());
    }

    @Test
    void fallsBackToMapViewCenterWhenCameraIsUnavailable() {
        final SearchCenter center = SearchCenter.defaultCenter(
            Optional.empty(), DimensionId.OVERWORLD, MAP_CENTER_X, MAP_CENTER_Z
        );
        assertEquals(MAP_CENTER_X, center.blockX());
        assertEquals(MAP_CENTER_Z, center.blockZ());
    }
}
