package cn.net.rms.confluxmap.mc.ui.screen;

/** Immutable scrollbar geometry shared by structure list screens. */
record ScrollBarModel(
    int trackTop,
    int trackHeight,
    int totalRows,
    int visibleRows,
    int offset,
    int thumbTop,
    int thumbHeight
) {
    private static final int MIN_THUMB_HEIGHT = 12;

    public static ScrollBarModel of(
        final int trackTop,
        final int trackHeight,
        final int totalRows,
        final int visibleRows,
        final int offset
    ) {
        final int safeTrackHeight = Math.max(0, trackHeight);
        final int safeTotalRows = Math.max(0, totalRows);
        final int safeVisibleRows = Math.max(0, visibleRows);
        final int maxOffset = Math.max(0, safeTotalRows - safeVisibleRows);
        final int safeOffset = Math.max(0, Math.min(offset, maxOffset));
        if (safeTrackHeight == 0 || safeTotalRows <= safeVisibleRows || safeVisibleRows == 0) {
            return new ScrollBarModel(
                trackTop, safeTrackHeight, safeTotalRows, safeVisibleRows, safeOffset,
                trackTop, safeTrackHeight
            );
        }
        final int thumbHeight = Math.min(
            safeTrackHeight,
            Math.max(MIN_THUMB_HEIGHT, safeTrackHeight * safeVisibleRows / safeTotalRows)
        );
        final int travel = safeTrackHeight - thumbHeight;
        final int thumbTop = trackTop + Math.round(travel * safeOffset / (float) maxOffset);
        return new ScrollBarModel(
            trackTop, safeTrackHeight, safeTotalRows, safeVisibleRows, safeOffset,
            thumbTop, thumbHeight
        );
    }

    public boolean visible() {
        return totalRows > visibleRows && visibleRows > 0 && trackHeight > 0;
    }

    public int maxOffset() {
        return Math.max(0, totalRows - visibleRows);
    }

    public int offsetForThumbTop(final double requestedThumbTop) {
        if (!visible()) {
            return 0;
        }
        final int travel = trackHeight - thumbHeight;
        if (travel <= 0) {
            return offset;
        }
        final double clamped = Math.max(trackTop, Math.min(trackTop + travel, requestedThumbTop));
        return (int) Math.round((clamped - trackTop) * maxOffset() / travel);
    }
}
