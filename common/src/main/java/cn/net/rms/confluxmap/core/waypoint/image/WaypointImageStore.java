package cn.net.rms.confluxmap.core.waypoint.image;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Directory-backed storage of waypoint images, one container file per image
 * named by its canonical UUID. Sweeping treats the caller's reference set (the
 * server's waypoint file is the single source of truth) as the only reason to
 * keep a file beyond the grace window, so a crash between upload and waypoint
 * upsert never loses a fresh image.
 */
public final class WaypointImageStore {
    public static final String EXTENSION = ".img";
    private final Path dir;

    public WaypointImageStore(final Path dir) {
        this.dir = java.util.Objects.requireNonNull(dir, "dir");
    }

    public Path directory() {
        return dir;
    }

    public Path fileFor(final UUID imageId) {
        return dir.resolve(imageId.toString() + EXTENSION);
    }

    public void save(
        final UUID imageId,
        final UUID owner,
        final int formatTag,
        final int width,
        final int height,
        final byte[] png
    ) throws IOException {
        Files.createDirectories(dir);
        WaypointImageFile.save(fileFor(imageId), new StoredImage(owner, formatTag, width, height, png));
    }

    public boolean exists(final UUID imageId) {
        return Files.exists(fileFor(imageId));
    }

    public Optional<StoredImage> load(final UUID imageId) {
        try {
            return Optional.of(WaypointImageFile.load(fileFor(imageId)));
        } catch (final IOException e) {
            return Optional.empty();
        }
    }

    public Optional<WaypointImageFile.Header> readHeader(final UUID imageId) {
        try {
            return Optional.of(WaypointImageFile.readHeader(fileFor(imageId)));
        } catch (final IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Deletes every image file that is neither referenced nor written within
     * {@code minAgeMs} of {@code nowEpochMs}. Returns the number of deletions.
     */
    public int sweep(final Set<String> referencedIds, final long minAgeMs, final long nowEpochMs)
        throws IOException {
        return sweep(referencedIds, minAgeMs, nowEpochMs, (owner, bytes) -> {});
    }

    /** Deletes unreferenced aged files, reporting each freed image's owner and byte size. */
    public int sweep(
        final Set<String> referencedIds,
        final long minAgeMs,
        final long nowEpochMs,
        final java.util.function.BiConsumer<UUID, Long> onDeleted
    )
        throws IOException {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        int deleted = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*" + EXTENSION)) {
            for (final Path file : files) {
                final UUID imageId = imageIdFromFileName(file);
                if (imageId == null) {
                    continue;
                }
                if (referencedIds.contains(imageId.toString())) {
                    continue;
                }
                try {
                    if (nowEpochMs - Files.getLastModifiedTime(file).toMillis() < minAgeMs) {
                        continue;
                    }
                } catch (final IOException e) {
                    continue;
                }
                final Optional<WaypointImageFile.Header> header = readHeader(imageId);
                Files.deleteIfExists(file);
                deleted++;
                header.ifPresent(h -> onDeleted.accept(h.owner(), (long) h.payloadBytes()));
            }
        }
        return deleted;
    }

    private static UUID imageIdFromFileName(final Path file) {
        final String name = file.getFileName().toString();
        if (!name.endsWith(EXTENSION)) {
            return null;
        }
        try {
            return UUID.fromString(name.substring(0, name.length() - EXTENSION.length()));
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }
}
