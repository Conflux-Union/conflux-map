package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.core.radar.RadarCategory;

record MapOverlayBounds(float left, float top, float right, float bottom) {
    private static final float RADAR_PADDING = 4f;

    static MapOverlayBounds structureIcon(
        final float centerX,
        final float centerY,
        final boolean hovered
    ) {
        final int size = hovered ? 20 : 18;
        final int left = Math.round(centerX - size / 2f);
        final int top = Math.round(centerY - size / 2f);
        return new MapOverlayBounds(left, top, left + size, top + size);
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
