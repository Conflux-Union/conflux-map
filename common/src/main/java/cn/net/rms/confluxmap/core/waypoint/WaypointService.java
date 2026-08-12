package cn.net.rms.confluxmap.core.waypoint;

import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.store.WorldStorageMigration;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.apache.logging.log4j.Logger;

/**
 * Owns the current world's {@link WaypointStore}, bound to
 * {@link cn.net.rms.confluxmap.mc.world.WorldSessionTracker} sessions: loads
 * (blocking, main thread - mirrors {@link cn.net.rms.confluxmap.core.config.ConfigIo}'s
 * own load-at-a-natural-pause-point pattern; these files are small) whenever
 * a genuinely *new* world/server identity appears, saves the outgoing
 * world's data before switching away, and saves again on every store
 * mutation and on session end. All saves go through {@link MapExecutors#io()},
 * atomically (see {@link WaypointIo}).
 *
 * <p>A dimension change within the same world/server does not reload or
 * save anything - waypoints for every dimension of one world/server live in
 * a single file (see {@code waypoint-ux.md} S2), so only a genuine world
 * identity change matters here. This mirrors {@link cn.net.rms.confluxmap.core.store.MapWorldService}'s
 * session-swap shape.
 */
public final class WaypointService implements WaypointDataView {
    public enum LegacyMigrationStatus {
        APPLIED,
        SOURCE_NOT_FOUND,
        SOURCE_IS_TARGET,
        SOURCE_READ_ONLY,
        TARGET_READ_ONLY,
        FAILED
    }

    public record LegacyMigrationResult(
        LegacyMigrationStatus status,
        int migratedWaypoints,
        int skippedDuplicates
    ) {
    }

    private record LegacyMigrationTaskResult(
        LegacyMigrationResult result,
        WaypointStore.State mergedState
    ) {
    }

    private final Path baseDir;
    private final MapExecutors executors;
    private final Logger logger;

    private volatile WaypointStore current;
    /** Keeps a just-written outgoing state available while its IO task is still queued. */
    private final Map<WorldIdentity, WaypointStore.State> pendingOutgoing = new ConcurrentHashMap<>();

    public WaypointService(final Path baseDir, final MapExecutors executors, final Logger logger) {
        this.baseDir = baseDir;
        this.executors = executors;
        this.logger = logger;
    }

    /** Main thread only: {@link cn.net.rms.confluxmap.mc.world.WorldSessionTracker} listener. */
    public void onSessionChanged(final SessionGuard.Session session) {
        final WorldIdentity newWorld = session.active() ? session.world() : null;
        final WorldIdentity oldWorld = current == null ? null : current.world();
        if (Objects.equals(newWorld, oldWorld)) {
            return;
        }
        if (current != null) {
            final WorldIdentity outgoingWorld = current.world();
            final WaypointStore.State outgoingState = current.state();
            pendingOutgoing.put(outgoingWorld, outgoingState);
            saveOutgoingSnapshot(outgoingWorld, outgoingState);
        }
        if (newWorld == null) {
            current = null;
            return;
        }
        final Path file = WorldStorageMigration.file(baseDir, newWorld, ".json", logger);
        final WaypointStore.State pendingState = pendingOutgoing.remove(newWorld);
        final WaypointStore.State loaded = pendingState != null
            ? pendingState
            : WaypointIo.loadState(file, logger);
        final WaypointStore store = new WaypointStore(newWorld, loaded);
        store.addListener(waypoints -> saveSnapshot(newWorld, store.state()));
        current = store;
    }

    /** The active store, or {@code null} between sessions. */
    public WaypointStore current() {
        return current;
    }

    /** Convenience: current waypoints, or an empty list between sessions. */
    public List<Waypoint> list() {
        final WaypointStore store = current;
        return store == null ? Collections.emptyList() : store.list();
    }

    /** True when this multiplayer address still owns the pre-profile {@code world.json}. */
    public boolean hasLegacyMultiplayerWaypoints(final String serverId) {
        final Path sourceFile = legacyMultiplayerFile(serverId);
        return Files.isRegularFile(sourceFile) && Files.notExists(firstBackupFile(sourceFile));
    }

    /**
     * Assigns the old address-wide {@code world.json} to one explicit client world profile.
     * Existing target entries win UUID collisions; the source is retained as {@code *.migrated}.
     * Main thread only, matching the rest of this service's session and mutation API.
     */
    public LegacyMigrationResult migrateLegacyMultiplayerWaypoints(final WorldIdentity targetWorld) {
        Objects.requireNonNull(targetWorld, "targetWorld");
        final Path sourceFile = legacyMultiplayerFile(targetWorld.serverId());
        final Path targetFile = fileFor(targetWorld);
        if (sourceFile.equals(targetFile)) {
            return result(LegacyMigrationStatus.SOURCE_IS_TARGET);
        }
        if (!Files.isRegularFile(sourceFile)) {
            return result(LegacyMigrationStatus.SOURCE_NOT_FOUND);
        }

        final WaypointStore activeTarget = current != null && current.world().equals(targetWorld)
            ? current
            : null;
        final WorldIdentity sourceWorld = new WorldIdentity(targetWorld.serverId(), "world");
        final WaypointStore activeSource = current != null && current.world().equals(sourceWorld)
            ? current
            : null;
        final WaypointStore.State activeState = activeTarget == null ? null : activeTarget.state();
        final WaypointStore.State activeSourceState = activeSource == null
            ? null
            : activeSource.state();
        final Future<LegacyMigrationTaskResult> migration = executors.io().submit(
            () -> migrateOnIo(sourceFile, targetFile, activeState, activeSourceState)
        );
        final LegacyMigrationTaskResult outcome = waitForMigration(migration, targetWorld);
        if (outcome.result().status() == LegacyMigrationStatus.APPLIED
            && activeTarget != null
            && current == activeTarget) {
            current = createStore(targetWorld, outcome.mergedState());
        } else if (outcome.result().status() == LegacyMigrationStatus.APPLIED
            && activeSource != null
            && current == activeSource) {
            current = createStore(sourceWorld, new WaypointStore.State(List.of(), List.of()));
        }
        return outcome.result();
    }

    @Override
    public WaypointDataView.Snapshot snapshot() {
        final WaypointStore store = current;
        return store == null ? WaypointDataView.Snapshot.empty() : store.dataSnapshot();
    }

    private void saveSnapshot(final WorldIdentity world, final WaypointStore.State snapshot) {
        final Path file = fileFor(world);
        executors.io().execute(() -> WaypointIo.save(file, snapshot, logger));
    }

    private void saveOutgoingSnapshot(final WorldIdentity world, final WaypointStore.State snapshot) {
        final Path file = fileFor(world);
        try {
            executors.io().execute(() -> {
                try {
                    WaypointIo.save(file, snapshot, logger);
                } catch (final RuntimeException error) {
                    logger.error("Failed to save outgoing waypoints for {}", world, error);
                } finally {
                    pendingOutgoing.remove(world, snapshot);
                }
            });
        } catch (final RuntimeException error) {
            pendingOutgoing.remove(world, snapshot);
            logger.error("Failed to schedule outgoing waypoint save for {}", world, error);
        }
    }

    int pendingOutgoingCount() {
        return pendingOutgoing.size();
    }

    private Path fileFor(final WorldIdentity world) {
        return baseDir.resolve(world.serverId()).resolve(world.worldId() + ".json");
    }

    private Path legacyMultiplayerFile(final String serverId) {
        return baseDir.resolve(serverId).resolve("world.json");
    }

    private WaypointStore createStore(
        final WorldIdentity world,
        final WaypointStore.State state
    ) {
        final WaypointStore store = new WaypointStore(world, state);
        store.addListener(waypoints -> saveSnapshot(world, store.state()));
        return store;
    }

    private LegacyMigrationTaskResult migrateOnIo(
        final Path sourceFile,
        final Path targetFile,
        final WaypointStore.State activeTargetState,
        final WaypointStore.State activeSourceState
    ) {
        if (!Files.isRegularFile(sourceFile)) {
            return taskResult(LegacyMigrationStatus.SOURCE_NOT_FOUND);
        }
        final WaypointStore.State sourceState = activeSourceState == null
            ? WaypointIo.loadState(sourceFile, logger)
            : activeSourceState;
        if (!sourceState.persistenceWritable()) {
            return taskResult(LegacyMigrationStatus.SOURCE_READ_ONLY);
        }
        final WaypointStore.State targetState = activeTargetState == null
            ? WaypointIo.loadState(targetFile, logger)
            : activeTargetState;
        if (!targetState.persistenceWritable()) {
            return taskResult(LegacyMigrationStatus.TARGET_READ_ONLY);
        }
        final LegacyMigrationTaskResult merged = merge(targetState, sourceState);
        final Path backupFile = nextBackupFile(sourceFile);
        try {
            if (activeSourceState != null) {
                WaypointIo.saveChecked(sourceFile, activeSourceState);
            }
            move(sourceFile, backupFile);
            try {
                WaypointIo.saveChecked(targetFile, merged.mergedState());
            } catch (final IOException saveFailure) {
                restoreSource(backupFile, sourceFile, saveFailure);
                throw saveFailure;
            }
            logger.info(
                "Assigned legacy multiplayer waypoints from {} to {}; backup kept at {}",
                sourceFile,
                targetFile,
                backupFile
            );
            return merged;
        } catch (final IOException e) {
            logger.error(
                "Could not assign legacy multiplayer waypoints from {} to {}",
                sourceFile,
                targetFile,
                e
            );
            return taskResult(LegacyMigrationStatus.FAILED);
        }
    }

    private LegacyMigrationTaskResult waitForMigration(
        final Future<LegacyMigrationTaskResult> migration,
        final WorldIdentity targetWorld
    ) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return migration.get();
                } catch (final InterruptedException e) {
                    interrupted = true;
                }
            }
        } catch (final ExecutionException e) {
            logger.error("Failed to migrate legacy waypoints to {}", targetWorld, e.getCause());
            return taskResult(LegacyMigrationStatus.FAILED);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static LegacyMigrationTaskResult merge(
        final WaypointStore.State target,
        final WaypointStore.State source
    ) {
        final List<WaypointSet> sets = new ArrayList<>(target.sets());
        sets.addAll(source.sets());
        final Map<UUID, Waypoint> waypoints = new LinkedHashMap<>();
        for (final Waypoint waypoint : target.waypoints()) {
            waypoints.put(waypoint.id, waypoint);
        }
        int skippedDuplicates = 0;
        int migratedWaypoints = 0;
        for (final Waypoint waypoint : source.waypoints()) {
            if (waypoints.putIfAbsent(waypoint.id, waypoint) == null) {
                migratedWaypoints++;
            } else {
                skippedDuplicates++;
            }
        }
        return new LegacyMigrationTaskResult(
            new LegacyMigrationResult(
                LegacyMigrationStatus.APPLIED, migratedWaypoints, skippedDuplicates
            ),
            new WaypointStore.State(sets, List.copyOf(waypoints.values()))
        );
    }

    private static Path nextBackupFile(final Path sourceFile) {
        final Path first = firstBackupFile(sourceFile);
        if (!Files.exists(first)) {
            return first;
        }
        int suffix = 2;
        while (Files.exists(first.resolveSibling(first.getFileName() + "." + suffix))) {
            suffix++;
        }
        return first.resolveSibling(first.getFileName() + "." + suffix);
    }

    private static Path firstBackupFile(final Path sourceFile) {
        return sourceFile.resolveSibling(sourceFile.getFileName() + ".migrated");
    }

    private static void restoreSource(
        final Path backupFile,
        final Path sourceFile,
        final IOException saveFailure
    ) {
        try {
            move(backupFile, sourceFile);
        } catch (final IOException restoreFailure) {
            saveFailure.addSuppressed(restoreFailure);
        }
    }

    private static void move(final Path source, final Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private static LegacyMigrationResult result(final LegacyMigrationStatus status) {
        return new LegacyMigrationResult(status, 0, 0);
    }

    private static LegacyMigrationTaskResult taskResult(final LegacyMigrationStatus status) {
        return new LegacyMigrationTaskResult(result(status), null);
    }
}
