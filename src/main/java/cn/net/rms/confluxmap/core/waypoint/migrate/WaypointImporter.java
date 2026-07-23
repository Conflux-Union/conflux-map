package cn.net.rms.confluxmap.core.waypoint.migrate;

import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointSet;
import cn.net.rms.confluxmap.core.waypoint.WaypointStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Adopts parsed foreign waypoints into the active {@link WaypointStore}.
 *
 * <p>Duplicate detection is by dimension + block position only, not by name:
 * the same base saved in both Xaero and VoxelMap (or an earlier import that
 * was renamed afterwards) must not produce a second marker on re-import.
 * Two genuinely distinct waypoints sharing one exact block are rare enough
 * to accept that trade-off.
 */
public final class WaypointImporter {
    /** Longest waypoint name the edit UI accepts; longer imports are truncated. */
    private static final int MAX_NAME_LENGTH = 64;

    /** Outcome counts of one import batch. */
    public record Result(int imported, int duplicates) {
        public static final Result EMPTY = new Result(0, 0);
    }

    private WaypointImporter() {
    }

    /** Main thread only (mutates the store). Never throws on odd input; unusable stores import nothing. */
    public static Result importInto(final WaypointStore store, final List<ImportedWaypoint> candidates) {
        if (store == null || !store.persistenceWritable() || candidates == null || candidates.isEmpty()) {
            return Result.EMPTY;
        }
        final Set<String> occupied = new HashSet<>();
        for (final Waypoint existing : store.list()) {
            occupied.add(positionKey(existing.dimensionId.toString(), existing.x, existing.y, existing.z));
        }
        final List<Waypoint> batch = new ArrayList<>();
        int duplicates = 0;
        for (final ImportedWaypoint candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            final String key = positionKey(
                candidate.dimensionId().toString(), candidate.x(), candidate.y(), candidate.z()
            );
            if (!occupied.add(key)) {
                duplicates++;
                continue;
            }
            batch.add(new Waypoint(
                UUID.randomUUID(),
                normalizeName(candidate.name()),
                candidate.dimensionId(),
                candidate.x(),
                candidate.y(),
                candidate.z(),
                candidate.colorArgb(),
                normalizeSetName(candidate.setName()),
                candidate.visible(),
                candidate.type(),
                System.currentTimeMillis()
            ));
        }
        final int imported = store.addAll(batch);
        return new Result(imported, duplicates);
    }

    private static String positionKey(final String dimension, final double x, final double y, final double z) {
        return dimension + ':' + (long) Math.floor(x) + ',' + (long) Math.floor(y) + ',' + (long) Math.floor(z);
    }

    private static String normalizeName(final String raw) {
        final String cleaned = stripControlChars(raw).trim();
        if (cleaned.isEmpty()) {
            return "Waypoint";
        }
        return cleaned.length() <= MAX_NAME_LENGTH ? cleaned : cleaned.substring(0, MAX_NAME_LENGTH).trim();
    }

    /** Foreign set names are clamped to this mod's set-name rules; unusable ones fall back to the default set. */
    private static String normalizeSetName(final String raw) {
        final String cleaned = stripControlChars(raw).trim();
        if (cleaned.isEmpty()) {
            return WaypointSet.DEFAULT_NAME;
        }
        return cleaned.length() <= WaypointSet.MAX_NAME_LENGTH
            ? cleaned
            : cleaned.substring(0, WaypointSet.MAX_NAME_LENGTH).trim();
    }

    private static String stripControlChars(final String raw) {
        if (raw == null) {
            return "";
        }
        final StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            if (!Character.isISOControl(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }
}
