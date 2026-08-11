package cn.net.rms.confluxmap.core.multiworld;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Moves a client-world namespace into recoverable local storage before its profile is removed.
 * No source is deleted, and every candidate path is constrained to its declared storage root.
 */
public final class ClientWorldProfileDeletionService {
    private final Path mapRoot;
    private final Path waypointRoot;
    private final Path annotationRoot;
    private final Path recoveryRoot;

    public ClientWorldProfileDeletionService(
        final Path mapRoot,
        final Path waypointRoot,
        final Path annotationRoot,
        final Path recoveryRoot
    ) {
        this.mapRoot = normalizedRoot(mapRoot);
        this.waypointRoot = normalizedRoot(waypointRoot);
        this.annotationRoot = normalizedRoot(annotationRoot);
        this.recoveryRoot = normalizedRoot(recoveryRoot);
    }

    /**
     * Moves all known client-owned data into one recovery transaction. Call {@link Transaction#restore()}
     * if the corresponding profile-registry save fails.
     */
    public Transaction moveToRecovery(final String serverId, final ClientWorldProfile profile) {
        Objects.requireNonNull(profile, "profile");
        final String namespace = profile.storageId();
        final String storageServerId = profile.storageServerId(serverId);
        final List<Move> completed = new ArrayList<>();
        Path journal = null;
        try {
            final Path recovery = recoveryDirectory(serverId, namespace);
            Files.createDirectories(recovery);
            journal = recovery.resolve("transaction.properties");
            final List<Move> planned = new ArrayList<>();
            planIfPresent(
                safeDirectory(mapRoot, storageServerId, namespace),
                recovery.resolve("map"),
                planned
            );
            planIfPresent(
                safeDirectory(mapRoot.resolve("prediction"), storageServerId, namespace),
                recovery.resolve("prediction"),
                planned
            );
            planIfPresent(
                safeDirectory(mapRoot.resolve("structures"), storageServerId, namespace),
                recovery.resolve("structures"),
                planned
            );
            planIfPresent(
                safeFile(waypointRoot, storageServerId, namespace + ".json"),
                recovery.resolve("waypoints.json"),
                planned
            );
            planIfPresent(
                safeFile(annotationRoot, storageServerId, namespace + ".json"),
                recovery.resolve("annotations.json"),
                planned
            );
            // Persist the complete intent before the first move. A crash after any individual
            // move is therefore recoverable even if the process stops before another journal write.
            writeJournal(journal, serverId, profile.id(), planned);
            for (final Move move : planned) {
                if (!Files.exists(move.source)) {
                    continue;
                }
                Files.createDirectories(move.target.getParent());
                move(move.source, move.target);
                completed.add(move);
            }
            return Transaction.prepared(completed, journal);
        } catch (final IOException | RuntimeException error) {
            final String rollbackError = restore(completed);
            if (rollbackError == null) {
                deleteJournal(journal);
            }
            final String message = "could not move profile data to recovery: " + error;
            return Transaction.failed(rollbackError == null ? message : message + "; " + rollbackError);
        }
    }

    /**
     * Reconciles an interrupted move before a server's profiles are used. A journal whose
     * profile still exists is rolled back; one whose registry entry is already gone is finalized
     * in recovery, so no source directory can be recreated by a half-completed delete.
     */
    public void recoverPendingTransactions(final String serverId, final Set<String> liveProfileIds) {
        final Path serverRoot = recoveryRoot.resolve(serverId).normalize();
        if (!serverRoot.startsWith(recoveryRoot) || !Files.isDirectory(serverRoot)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(serverRoot, 4)) {
            paths.filter(path -> path.getFileName() != null
                    && path.getFileName().toString().equals("transaction.properties"))
                .forEach(journal -> recoverJournal(journal, liveProfileIds));
        } catch (final IOException | RuntimeException error) {
            // Recovery is best effort; leaving the journal is safer than deleting data. The
            // caller must still be able to diagnose why a recovery remains pending.
            System.getLogger(ClientWorldProfileDeletionService.class.getName()).log(
                System.Logger.Level.WARNING,
                "Client-world deletion recovery failed for server " + serverId,
                error
            );
        }
    }

    private void recoverJournal(final Path journal, final Set<String> liveProfileIds) {
        final Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(journal)) {
            properties.load(reader);
            final String profileId = properties.getProperty("profileId");
            if (profileId == null || profileId.isBlank()) {
                // A truncated/corrupt journal must remain available for manual recovery. Removing
                // it here could strand files already moved into recovery with no ownership record.
                return;
            }
            if (!liveProfileIds.contains(profileId)) {
                deleteJournal(journal);
                return;
            }
            final List<Move> moves = readMoves(properties);
            final String restoreError = restore(moves);
            if (restoreError == null) {
                deleteJournal(journal);
            }
        } catch (final IOException | RuntimeException error) {
            // Keep an unreadable journal for manual recovery rather than guessing.
        }
    }

    private Path recoveryDirectory(final String serverId, final String namespace) {
        final Path parent = safeDirectory(recoveryRoot, serverId, namespace);
        Path candidate = parent.resolve(Long.toString(System.currentTimeMillis())).normalize();
        int suffix = 1;
        while (Files.exists(candidate)) {
            candidate = parent.resolve(System.currentTimeMillis() + "-" + suffix++).normalize();
        }
        if (!candidate.startsWith(recoveryRoot)) {
            throw new IllegalArgumentException("recovery path escaped its root");
        }
        return candidate;
    }

    private static Path normalizedRoot(final Path root) {
        return Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    private static Path safeDirectory(final Path root, final String serverId, final String namespace) {
        return safePath(root, serverId, namespace);
    }

    private static Path safeFile(final Path root, final String serverId, final String fileName) {
        return safePath(root, serverId, fileName);
    }

    private static Path safePath(final Path root, final String serverId, final String child) {
        if (serverId == null || serverId.isBlank() || child == null || child.isBlank()) {
            throw new IllegalArgumentException("profile storage path has a blank segment");
        }
        final Path normalized = root.resolve(serverId).resolve(child).normalize();
        if (!normalized.startsWith(root)) {
            throw new IllegalArgumentException("profile storage path escaped its root");
        }
        return normalized;
    }

    private static void planIfPresent(
        final Path source,
        final Path target,
        final List<Move> planned
    ) {
        if (!Files.exists(source)) {
            return;
        }
        planned.add(new Move(source, target));
    }

    private static void move(final Path source, final Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException ignored) {
            Files.move(source, target);
        }
    }

    private static String restore(final List<Move> completed) {
        String firstError = null;
        final List<Move> reversed = new ArrayList<>(completed);
        Collections.reverse(reversed);
        for (final Move move : reversed) {
            try {
                if (!Files.exists(move.target) || Files.exists(move.source)) {
                    continue;
                }
                Files.createDirectories(move.source.getParent());
                move(move.target, move.source);
            } catch (final IOException error) {
                if (firstError == null) {
                    firstError = "could not restore profile data: " + error;
                }
            }
        }
        return firstError;
    }

    private static void writeJournal(
        final Path journal,
        final String serverId,
        final String profileId,
        final List<Move> moves
    ) throws IOException {
        final Properties properties = new Properties();
        if (serverId != null) {
            properties.setProperty("serverId", serverId);
        }
        if (profileId != null) {
            properties.setProperty("profileId", profileId);
        }
        properties.setProperty("moveCount", Integer.toString(moves.size()));
        for (int index = 0; index < moves.size(); index++) {
            properties.setProperty("move." + index + ".source", moves.get(index).source.toString());
            properties.setProperty("move." + index + ".target", moves.get(index).target.toString());
        }
        final Path temporary = journal.resolveSibling(journal.getFileName() + ".tmp");
        try {
            Files.createDirectories(journal.getParent());
            try (var writer = Files.newBufferedWriter(temporary)) {
                properties.store(writer, "Conflux Map client-world deletion transaction");
            }
            move(temporary, journal);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private List<Move> readMoves(final Properties properties) {
        final int count = Integer.parseInt(properties.getProperty("moveCount", "0"));
        if (count < 0 || count > 16) {
            throw new IllegalArgumentException("invalid deletion journal move count");
        }
        final List<Move> moves = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            final Path source = Path.of(properties.getProperty("move." + index + ".source")).toAbsolutePath().normalize();
            final Path target = Path.of(properties.getProperty("move." + index + ".target")).toAbsolutePath().normalize();
            if (!source.startsWith(mapRoot) && !source.startsWith(waypointRoot)
                && !source.startsWith(annotationRoot)) {
                throw new IllegalArgumentException("deletion journal source escaped storage roots");
            }
            if (!target.startsWith(recoveryRoot)) {
                throw new IllegalArgumentException("deletion journal target escaped recovery root");
            }
            moves.add(new Move(source, target));
        }
        return moves;
    }

    private static void deleteJournal(final Path journal) {
        if (journal == null) {
            return;
        }
        try {
            Files.deleteIfExists(journal);
        } catch (final IOException ignored) {
            // Keep an undeleted journal for the next recovery pass.
        }
    }

    private record Move(Path source, Path target) {
    }

    /** Prepared moves can be reversed after a registry-persistence failure. */
    public static final class Transaction {
        private final List<Move> completed;
        private final Path journal;
        private final String error;

        private Transaction(final List<Move> completed, final Path journal, final String error) {
            this.completed = List.copyOf(completed);
            this.journal = journal;
            this.error = error;
        }

        static Transaction prepared(final List<Move> completed, final Path journal) {
            return new Transaction(completed, journal, null);
        }

        static Transaction failed(final String error) {
            return new Transaction(List.of(), null, error);
        }

        public boolean prepared() {
            return error == null;
        }

        public String error() {
            return error;
        }

        /** Returns null when every moved item was restored. */
        public String restore() {
            final String error = ClientWorldProfileDeletionService.restore(completed);
            if (error == null) {
                deleteJournal(journal);
            }
            return error;
        }

        /** Removes the journal after the profile registry mutation has committed. */
        public String commit() {
            if (journal == null) {
                return null;
            }
            try {
                Files.deleteIfExists(journal);
                return null;
            } catch (final IOException error) {
                return "could not finalize deletion journal: " + error;
            }
        }
    }

    public record DeletionResult(boolean deleted, String error) {
        public static DeletionResult success() {
            return new DeletionResult(true, null);
        }

        public static DeletionResult failure(final String error) {
            return new DeletionResult(false, error == null || error.isBlank() ? "profile deletion failed" : error);
        }
    }
}
