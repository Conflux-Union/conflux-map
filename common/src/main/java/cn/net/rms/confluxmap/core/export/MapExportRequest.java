package cn.net.rms.confluxmap.core.export;

import cn.net.rms.confluxmap.core.annotation.Annotation;
import cn.net.rms.confluxmap.core.loadstate.FullscreenDisplayMode;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.predict.PredictionViewMode;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.util.List;
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
    MapExportLoadState loadState,
    List<Annotation> annotations
) {
    public MapExportRequest {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(layer, "layer");
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(resolution, "resolution");
        Objects.requireNonNull(displayMode, "displayMode");
        Objects.requireNonNull(predictionMode, "predictionMode");
        Objects.requireNonNull(loadState, "loadState");
        annotations = List.copyOf(Objects.requireNonNull(annotations, "annotations"));
        if (!session.active()) {
            throw new IllegalArgumentException("Map export requires an active session");
        }
        if (annotations.stream().anyMatch(annotation ->
            !annotation.dimension().equals(session.dimension())
        )) {
            throw new IllegalArgumentException("Map export annotations must match the session dimension");
        }
        if (!Float.isFinite(daylightFactor) || daylightFactor < 0f || daylightFactor > 1f) {
            throw new IllegalArgumentException("Daylight factor must be in [0, 1]");
        }
        Math.multiplyExact(bounds.pixelCount(resolution), 4L);
    }

    public MapExportRequest(
        final SessionGuard.Session session,
        final MapLayer layer,
        final MapExportBounds bounds,
        final MapExportResolution resolution,
        final FullscreenDisplayMode displayMode,
        final boolean predictionActive,
        final PredictionViewMode predictionMode,
        final int predictionTint,
        final int background,
        final boolean dynamicLighting,
        final float daylightFactor,
        final MapExportLoadState loadState
    ) {
        this(
            session, layer, bounds, resolution, displayMode, predictionActive,
            predictionMode, predictionTint, background, dynamicLighting,
            daylightFactor, loadState, List.of()
        );
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
            loadState,
            annotations
        );
    }

    public MapExportRequest withAnnotations(final List<Annotation> selectedAnnotations) {
        return new MapExportRequest(
            session,
            layer,
            bounds,
            resolution,
            displayMode,
            predictionActive,
            predictionMode,
            predictionTint,
            background,
            dynamicLighting,
            daylightFactor,
            loadState,
            selectedAnnotations
        );
    }
}
