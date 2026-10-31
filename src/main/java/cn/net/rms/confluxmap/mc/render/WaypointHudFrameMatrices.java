package cn.net.rms.confluxmap.mc.render;

/**
 * Per-frame snapshot of the world pass's projection and camera view for the HUD-phase
 * waypoint item labels, stored row-major as doubles so version-free math can consume it.
 *
 * <p>From 1.21.2 on, vanilla folds the nausea sway (plus view bobbing and hurt tilt) into
 * the world projection and installs it from {@code GameRenderer.renderWorld}/{@code
 * renderLevel}; the plain matrix exists only at that install call, so the projection is
 * captured there and the camera view is captured later inside the world pass (the same
 * matrices the 3D labels render through). The world pass always runs before the HUD phase
 * of the same frame, so the HUD reads same-frame values. {@link #clear()} drops the
 * snapshot on session or dimension change so a stale capture never outlives its world
 * pass; with no snapshot the HUD falls back to the hand-rolled camera basis.
 */
public final class WaypointHudFrameMatrices {
    /** One frame's captured matrices in row-major order: projection[16], view[16]. */
    public record Snapshot(double[] projection, double[] view) {
        public Snapshot {
            projection = projection.clone();
            view = view.clone();
        }
    }

    private static Snapshot snapshot;

    private WaypointHudFrameMatrices() {
    }

    /** Stores this frame's captured matrices; every world pass overwrites the previous one. */
    public static void update(final double[] projectionRowMajor, final double[] viewRowMajor) {
        snapshot = new Snapshot(projectionRowMajor, viewRowMajor);
    }

    /** Drops the snapshot on session or dimension change. */
    public static void clear() {
        snapshot = null;
    }

    /** The latest captured matrices, or null when no world pass has captured yet. */
    public static Snapshot current() {
        return snapshot;
    }
}
