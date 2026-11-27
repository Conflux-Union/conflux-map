package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.core.radar.RadarCategory;
import cn.net.rms.confluxmap.mc.ui.StructureMarkerRenderer;

record MapOverlayBounds(float left, float top, float right, float bottom) {
    private static final float RADAR_PADDING = 4f;

    static MapOverlayBounds structureIcon(
        final float centerX,
        final float centerY
    ) {
        final float half = StructureMarkerRenderer.SIZE * 0.5f;
        return new MapOverlayBounds(centerX - half, centerY - half, centerX + half, centerY + half);
    }

    static MapOverlayBounds portalIcon(
        final float centerX,
        final float centerY,
        final int size
    ) {
        final float half = size * 0.5f;
        return new MapOverlayBounds(centerX - half, centerY - half, centerX + half, centerY + half);
    }

    static MapOverlayBounds text(
        final float x,
        final float y,
        final float width,
        final float height
    ) {
        return new MapOverlayBounds(x, y, x + width, y + height);
    }

    static MapOverlayBounds radar(
        final float centerX,
        final float centerY,
        final float iconHalf,
        final float nameWidth,
        final float fontHeight,
        final RadarCategory category,
        final boolean showPlayerNames
    ) {
        final boolean nameDrawn = category == RadarCategory.PLAYER
            && showPlayerNames && nameWidth > 0f;
        final float nameHalf = nameDrawn ? nameWidth / 2f : 0f;
        final float horizontalRadius = Math.max(iconHalf, nameHalf) + RADAR_PADDING;
        final float top = centerY - iconHalf - RADAR_PADDING;
        final float bottom = centerY + iconHalf
            + (nameDrawn ? 2f + fontHeight : 0f)
            + RADAR_PADDING;
        return new MapOverlayBounds(
            centerX - horizontalRadius, top,
            centerX + horizontalRadius, bottom
        );
    }
}
