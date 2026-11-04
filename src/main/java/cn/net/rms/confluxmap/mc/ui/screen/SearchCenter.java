package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.core.model.DimensionId;
import java.util.Optional;

/**
 * Default center for biome/structure searches: the camera viewpoint when it is in the
 * searched dimension (stable across the map's per-result auto-focus), else the map view
 * center.
 */
record SearchCenter(int blockX, int blockZ) {
    static SearchCenter defaultCenter(
        final Optional<PlayerView> viewpoint,
        final DimensionId searchedDimension,
        final int mapCenterX,
        final int mapCenterZ
    ) {
        return viewpoint
            .filter(view -> view.dimension().equals(searchedDimension))
            .map(view -> new SearchCenter(view.blockX(), view.blockZ()))
            .orElse(new SearchCenter(mapCenterX, mapCenterZ));
    }
}
