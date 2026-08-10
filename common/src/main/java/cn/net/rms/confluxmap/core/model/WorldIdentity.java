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
import java.util.UUID;
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
    private static final int SERVER_ID_PREFIX_MAX_LENGTH = 80;
    private static final int SERVER_ID_HASH_LENGTH = 16;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public static final WorldIdentity NONE = new WorldIdentity("none", "none");

    private final String serverId;
    private final String worldId;
    private final List<String> legacyStorageIds;
    private final List<String> legacyServerIds;

    public WorldIdentity(final String serverId, final String worldId) {
        this(serverId, worldId, List.of(), List.of());
    }

    private WorldIdentity(final String serverId, final String worldId, final List<String> legacyStorageIds) {
        this(serverId, worldId, legacyStorageIds, List.of());
    }

    private WorldIdentity(
        final String serverId,
        final String worldId,
        final List<String> legacyStorageIds,
        final List<String> legacyServerIds
    ) {
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.worldId = Objects.requireNonNull(worldId, "worldId");
        this.legacyStorageIds = List.copyOf(legacyStorageIds);
        this.legacyServerIds = List.copyOf(legacyServerIds);
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

    /** Pre-canonicalization server folders that may still own this world's map data. */
    public List<String> legacyServerIds() {
        return legacyServerIds;
    }

    /**
     * Non-companion multiplayer: {@code worldId} stays at the literal {@code "world"} so existing
     * caches keep working bit-for-bit. Companion servers go through {@link #companionMultiplayer(String, String)}
     * instead with the UUID the server advertised.
     */
    public static WorldIdentity multiplayer(final String address) {
        return multiplayerIdentity(address, "world");
    }

    /**
     * Multiplayer with a caller-selected stable storage identity. Client-owned multiworld
     * profiles use this factory and must not implicitly claim the old default-world cache.
     */
    public static WorldIdentity multiplayer(final String address, final String worldId) {
        return multiplayerIdentity(address, sanitizeWorldId(worldId));
    }

    /** Companion-only form; unlike the legacy overload it rejects untrusted non-UUID namespaces. */
    public static WorldIdentity companionMultiplayer(final String address, final String worldId) {
        if (!isCanonicalUuid(worldId)) {
            throw new IllegalArgumentException("companion worldId must be a canonical UUID");
        }
        return multiplayerIdentity(address, worldId);
    }

    /**
     * Companion-aware multiplayer: the server handed us a stable {@code worldId} (UUID string),
     * so we adopt it as the cache namespace. The address-based {@code serverId} stays the same
     * as the non-companion path so a server operator can still find the right cache directory.
     * The old client-only {@code world} id remains available for non-destructive configuration
     * lookups. Multiplayer disk storage migration is always explicit.
     */
    public static WorldIdentity companionMultiplayer(final String address, final String worldId) {
        final String sanitizedWorldId = sanitizeWorldId(worldId);
        final List<String> legacyIds = "world".equals(sanitizedWorldId)
            ? List.of()
            : List.of("world");
        return new WorldIdentity(sanitize(address), sanitizedWorldId, legacyIds);
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

    /**
     * Preserves the historical directory for ordinary DNS/IPv4 addresses while adding a digest
     * to inputs whose punctuation, Unicode or IPv6 syntax would collide under replacement-only
     * sanitization. Unsafe prefixes are bounded so an untrusted address cannot exceed path limits.
     */
    private static String serverId(final String address) {
        final String canonical = Objects.requireNonNull(address, "address").trim().toLowerCase(Locale.ROOT);
        if (canonical.matches("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?(?::[0-9]{1,5})?")) {
            return sanitize(canonical);
        }
        final String sanitized = sanitize(canonical);
        final String prefix = sanitized.length() <= SERVER_ID_PREFIX_MAX_LENGTH
            ? sanitized
            : sanitized.substring(0, SERVER_ID_PREFIX_MAX_LENGTH);
        return prefix + "--" + sha256(canonical).substring(0, SERVER_ID_HASH_LENGTH);
    }

    private static WorldIdentity multiplayerIdentity(final String address, final String worldId) {
        final String raw = Objects.requireNonNull(address, "address").trim().toLowerCase(Locale.ROOT);
        final String canonicalAddress = withoutDefaultPort(raw);
        final String canonicalServerId = serverId(canonicalAddress);
        final String explicitDefaultAddress = withDefaultPort(canonicalAddress);
        final String legacyServerId = serverId(raw.equals(canonicalAddress) ? explicitDefaultAddress : raw);
        final List<String> legacyServers = legacyServerId.equals(canonicalServerId)
            ? List.of() : List.of(legacyServerId);
        return new WorldIdentity(canonicalServerId, worldId, List.of(), legacyServers);
    }

    /** Removes only an unambiguous Minecraft default port; unbracketed IPv6 is left untouched. */
    private static String withoutDefaultPort(final String address) {
        if (address.startsWith("[")) {
            final int close = address.indexOf(']');
            return close > 0 && address.substring(close + 1).equals(":25565")
                ? address.substring(0, close + 1) : address;
        }
        final int firstColon = address.indexOf(':');
        return firstColon >= 0 && firstColon == address.lastIndexOf(':')
            && address.substring(firstColon).equals(":25565")
            ? address.substring(0, firstColon) : address;
    }

    private static String withDefaultPort(final String address) {
        if (address.startsWith("[") && address.endsWith("]")) {
            return address + ":25565";
        }
        return address.indexOf(':') < 0 ? address + ":25565" : address;
    }

    private static String sanitizeWorldId(final String s) {
        final String cleaned = sanitize(s);
        // Neutralize a leading dot run so values such as ".." cannot be interpreted as a parent
        // directory by any cache path consumer.
        return cleaned.replaceFirst("^\\.+", "_");
    }

    /** Returns true only for the lowercase hyphenated UUID representation used on the wire. */
    public static boolean isCanonicalUuid(final String value) {
        if (value == null) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (final IllegalArgumentException error) {
            return false;
        }
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
