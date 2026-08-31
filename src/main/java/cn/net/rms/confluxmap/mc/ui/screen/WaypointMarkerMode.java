package cn.net.rms.confluxmap.mc.ui.screen;

/** Chooses the one marker representation written by the waypoint form. */
enum WaypointMarkerMode {
    TEXT("confluxmap.screen.waypoint.marker_label"),
    ITEM("confluxmap.screen.waypoint.icon");

    private final String translationKey;

    WaypointMarkerMode(final String translationKey) {
        this.translationKey = translationKey;
    }

    static WaypointMarkerMode initial(final String iconItemId) {
        return iconItemId == null || iconItemId.isEmpty() ? TEXT : ITEM;
    }

    WaypointMarkerMode next() {
        return this == TEXT ? ITEM : TEXT;
    }

    String translationKey() {
        return translationKey;
    }

    String iconItemId(final String value) {
        return this == ITEM ? value : "";
    }

    String markerLabel(final String value) {
        return this == TEXT ? value : "";
    }
}
