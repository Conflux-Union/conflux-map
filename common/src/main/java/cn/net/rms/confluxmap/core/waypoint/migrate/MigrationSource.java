package cn.net.rms.confluxmap.core.waypoint.migrate;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * One importable batch of foreign waypoints: everything found for the current
 * world in one mod's storage (one Xaero world folder or one VoxelMap points
 * file). {@code displayName} is the on-disk folder/file name so the user can
 * recognize which data is about to be imported.
 */
public record MigrationSource(Mod mod, String displayName, Path origin, List<ImportedWaypoint> waypoints) {
    public enum Mod { XAERO, VOXELMAP }

    public MigrationSource {
        Objects.requireNonNull(mod, "mod");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(origin, "origin");
        waypoints = List.copyOf(waypoints);
    }
}
