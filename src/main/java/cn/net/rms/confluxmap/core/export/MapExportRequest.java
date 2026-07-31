package cn.net.rms.confluxmap.core.export;

import cn.net.rms.confluxmap.core.loadstate.FullscreenDisplayMode;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.predict.PredictionViewMode;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.util.Objects;

/** Immutable rendering state and inclusive world range for one PNG export. */
public record MapExportRequest(
    SessionGuard.Session session,
    MapLayer layer,
    MapExportBounds bounds,
    MapExportResolution resolution,
    FullscreenDisplayMode displayMode,
    boolean predictionActive,
    PredictionViewMode predictionMode,
    int predictionTint,
    int background,
    boolean dynamicLighting,
    float daylightFactor,
    MapExportLoadState loadState
) {
    public MapExportRequest {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(layer, "layer");
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(resolution, "resolution");
        Objects.requireNonNull(displayMode, "displayMode");
        Objects.requireNonNull(predictionMode, "predictionMode");
        Objects.requireNonNull(loadState, "loadState");
        if (!session.active()) {
            throw new IllegalArgumentException("Map export requires an active session");
        }
        if (!Float.isFinite(daylightFactor) || daylightFactor < 0f || daylightFactor > 1f) {
            throw new IllegalArgumentException("Daylight factor must be in [0, 1]");
        }
        Math.multiplyExact(bounds.pixelCount(resolution), 4L);
    }

    public int pixelWidth() {
        return bounds.pixelWidth(resolution);
    }

    public int pixelHeight() {
        return bounds.pixelHeight(resolution);
    }

    public long spoolBytes() {
        return Math.multiplyExact(bounds.pixelCount(resolution), 4L);
    }

    public MapExportRequest withSelection(
        final MapExportBounds selectedBounds,
        final MapExportResolution selectedResolution
    ) {
        return new MapExportRequest(
            session,
            layer,
            selectedBounds,
            selectedResolution,
            displayMode,
            predictionActive,
            predictionMode,
            predictionTint,
            background,
            dynamicLighting,
            daylightFactor,
            loadState
        );
    }
}
