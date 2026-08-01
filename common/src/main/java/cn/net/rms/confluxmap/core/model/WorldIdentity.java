package cn.net.rms.confluxmap.core.model;

import cn.net.rms.confluxmap.core.store.WorldIdStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Identifies where map data belongs on disk and in memory.
 * {@code serverId} is a sanitized server address for multiplayer or "local" for singleplayer;
 * {@code worldId} is a path-safe storage identity for the save (singleplayer) or a
 * server-provided/world marker. Optional legacy storage ids are migration metadata and are not
 * part of identity equality.
 */
public final class WorldIdentity {
    private static final int SAVE_ID_PREFIX_MAX_LENGTH = 80;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public static final WorldIdentity NONE = new WorldIdentity("none", "none");

    private final String serverId;
    private final String worldId;
    private final List<String> legacyStorageIds;

    public WorldIdentity(final String serverId, final String worldId) {
        this(serverId, worldId, List.of());
    }

    private WorldIdentity(final String serverId, final String worldId, final List<String> legacyStorageIds) {
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.worldId = Objects.requireNonNull(worldId, "worldId");
        this.legacyStorageIds = List.copyOf(legacyStorageIds);
    }

    public String serverId() {
        return serverId;
    }

    public String worldId() {
        return worldId;
    }

    public List<String> legacyStorageIds() {
        return legacyStorageIds;
    }

    /**
     * Non-companion multiplayer: {@code worldId} stays at the literal {@code "world"} so existing
     * caches keep working bit-for-bit. Companion servers go through {@link #multiplayer(String, String)}
     * instead with the UUID the server advertised.
     */
    public static WorldIdentity multiplayer(final String address) {
        return new WorldIdentity(sanitize(address), "world");
    }

    /**
     * Companion-aware multiplayer: the server handed us a stable {@code worldId} (UUID string),
     * so we adopt it as the cache namespace. The address-based {@code serverId} stays the same
     * as the non-companion path so a server operator can still find the right cache directory.
     */
    public static WorldIdentity multiplayer(final String address, final String worldId) {
        return new WorldIdentity(sanitize(address), sanitizeWorldId(worldId));
    }

    public static WorldIdentity singleplayer(final String levelName) {
        return new WorldIdentity("local", sanitizeWorldId(levelName));
    }

    /**
     * Uses a UUID persisted inside the save, rather than its editable display or directory name.
     * Reopening or renaming a save keeps the UUID; deleting it and creating a same-named save
     * creates a fresh UUID and therefore a fresh map namespace.
     */
    public static WorldIdentity singleplayerSave(final Path saveRoot) {
        if (saveRoot == null) {
            return singleplayer("unknown");
        }
        final Path fileName = saveRoot.normalize().getFileName();
        if (fileName == null) {
            return singleplayer("unknown");
        }
        final String rawSaveName = fileName.toString();
        final String legacyId = sanitizeWorldId(rawSaveName);
        final String prefix = legacyId.length() <= SAVE_ID_PREFIX_MAX_LENGTH
            ? legacyId
            : legacyId.substring(0, SAVE_ID_PREFIX_MAX_LENGTH);
        final String directoryBasedId = prefix + "--" + sha256(rawSaveName);
        final List<String> legacyIds = new ArrayList<>();
        legacyIds.add(directoryBasedId);
        if (legacyStorageIdIsUnique(saveRoot, legacyId) && !legacyId.equals(directoryBasedId)) {
            legacyIds.add(legacyId);
        }
        return new WorldIdentity("local", WorldIdStore.loadOrCreate(saveRoot).toString(), legacyIds);
    }

    private static String sanitize(final String s) {
        final String cleaned = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        return cleaned.isEmpty() ? "unknown" : cleaned;
    }

    private static String sanitizeWorldId(final String s) {
        final String cleaned = sanitize(s);
        // Neutralize a leading dot run so values such as ".." cannot be interpreted as a parent
        // directory by any cache path consumer.
        return cleaned.replaceFirst("^\\.+", "_");
    }

    private static String sha256(final String value) {
        final byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", e);
        }
        final char[] encoded = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            final int valueByte = digest[i] & 0xFF;
            encoded[i * 2] = HEX[valueByte >>> 4];
            encoded[i * 2 + 1] = HEX[valueByte & 0x0F];
        }
        return new String(encoded);
    }

    private static boolean legacyStorageIdIsUnique(final Path saveRoot, final String legacyId) {
        final Path parent = saveRoot.normalize().getParent();
        if (parent == null) {
            return false;
        }
        try (Stream<Path> siblings = Files.list(parent)) {
            return siblings
                .filter(Files::isDirectory)
                .filter(path -> Files.isRegularFile(path.resolve("level.dat")))
                .map(Path::getFileName)
                .filter(Objects::nonNull)
                .map(Path::toString)
                .filter(name -> sanitizeWorldId(name).equals(legacyId))
                .limit(2L)
                .count() == 1L;
        } catch (final IOException | SecurityException e) {
            return false;
        }
    }

    @Override
    public boolean equals(final Object other) {
        return this == other
            || other instanceof final WorldIdentity that
            && serverId.equals(that.serverId)
            && worldId.equals(that.worldId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(serverId, worldId);
    }

    @Override
    public String toString() {
        return "WorldIdentity[serverId=" + serverId + ", worldId=" + worldId + "]";
    }

    public boolean isPresent() {
        return !equals(NONE);
    }
}
