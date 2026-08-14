package cn.net.rms.confluxmap.core.multiworld;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Serializable map from one stable server identity to every address spelling that reaches it,
 * so a server published under several hostnames or IPs shares one cache and waypoint namespace.
 *
 * <p>The canonical id is the first address the client ever stored data under, which keeps existing
 * directories in place and keeps them readable. Lookups scan linearly: a registry holds one entry
 * per server the player has visited and is consulted once per connection.
 */
public final class ServerAliasRegistry {
    public static final int SCHEMA_VERSION = 1;

    private int schemaVersion = SCHEMA_VERSION;
    private Map<String, Entry> servers = new LinkedHashMap<>();

    /** Canonical id owning {@code addressId}, or empty when this address is unknown. */
    public Optional<String> canonicalForAddress(final String addressId) {
        Objects.requireNonNull(addressId, "addressId");
        return servers().entrySet().stream()
            .filter(entry -> entry.getValue().addresses().contains(addressId))
            .map(Map.Entry::getKey)
            .findFirst();
    }

    /**
     * Canonical id previously seen advertising {@code companionInstanceId}. The instance id is
     * stored outside the world save, so meeting it again identifies the same server regardless of
     * which address the player used to connect - and, unlike a world UUID, a copied world cannot
     * carry it to a different server.
     */
    public Optional<String> canonicalForCompanionInstance(final String companionInstanceId) {
        Objects.requireNonNull(companionInstanceId, "companionInstanceId");
        return servers().entrySet().stream()
            .filter(entry -> entry.getValue().companionInstanceIds().contains(companionInstanceId))
            .map(Map.Entry::getKey)
            .findFirst();
    }

    /** Registers {@code canonicalId} owning itself; returns false when it already exists. */
    public boolean create(final String canonicalId) {
        Objects.requireNonNull(canonicalId, "canonicalId");
        if (servers().containsKey(canonicalId)) {
            return false;
        }
        final Entry entry = new Entry();
        entry.addresses().add(canonicalId);
        servers().put(canonicalId, entry);
        return true;
    }

    /**
     * Points {@code addressId} at {@code canonicalId}. Returns false when the link already exists;
     * throws when the address belongs to a different server, because silently re-pointing it would
     * strand the data written under the old owner.
     */
    public boolean linkAddress(final String canonicalId, final String addressId) {
        Objects.requireNonNull(addressId, "addressId");
        final Entry entry = require(canonicalId);
        final Optional<String> owner = canonicalForAddress(addressId);
        if (owner.isPresent()) {
            if (owner.get().equals(canonicalId)) {
                return false;
            }
            throw new IllegalArgumentException(
                "address " + addressId + " already belongs to " + owner.get()
            );
        }
        entry.addresses().add(addressId);
        entry.detachedAddresses().remove(addressId);
        return true;
    }

    /**
     * Whether the player has explicitly detached {@code addressId} from {@code canonicalId}.
     * Companion learning honours this, otherwise the next connection would silently re-link an
     * address the player just separated.
     */
    public boolean isDetached(final String canonicalId, final String addressId) {
        final Entry entry = servers().get(canonicalId);
        return entry != null && entry.detachedAddresses().contains(addressId);
    }

    /** Records a companion world UUID observed on {@code canonicalId}; false when already known. */
    public boolean linkCompanionWorld(final String canonicalId, final String companionWorldId) {
        Objects.requireNonNull(companionWorldId, "companionWorldId");
        final Entry entry = require(canonicalId);
        if (entry.companionWorldIds().contains(companionWorldId)) {
            return false;
        }
        entry.companionWorldIds().add(companionWorldId);
        return true;
    }

    /** Records a companion instance id observed on {@code canonicalId}; false when already known. */
    public boolean linkCompanionInstance(final String canonicalId, final String companionInstanceId) {
        Objects.requireNonNull(companionInstanceId, "companionInstanceId");
        final Entry entry = require(canonicalId);
        if (entry.companionInstanceIds().contains(companionInstanceId)) {
            return false;
        }
        entry.companionInstanceIds().add(companionInstanceId);
        return true;
    }

    /**
     * Folds {@code sourceCanonicalId} into {@code canonicalId}, moving every address and companion
     * world onto it and dropping the source entry. Only the identity is merged; the caller must
     * have already migrated whatever the source namespace stored, because this makes the source
     * directory unreachable.
     */
    public void absorb(final String canonicalId, final String sourceCanonicalId) {
        final Entry target = require(canonicalId);
        final Entry source = require(sourceCanonicalId);
        if (canonicalId.equals(sourceCanonicalId)) {
            throw new IllegalArgumentException("cannot absorb " + canonicalId + " into itself");
        }
        servers().remove(sourceCanonicalId);
        for (final String address : source.addresses()) {
            if (!target.addresses().contains(address)) {
                target.addresses().add(address);
            }
        }
        for (final String worldId : source.companionWorldIds()) {
            if (!target.companionWorldIds().contains(worldId)) {
                target.companionWorldIds().add(worldId);
            }
        }
        for (final String instanceId : source.companionInstanceIds()) {
            if (!target.companionInstanceIds().contains(instanceId)) {
                target.companionInstanceIds().add(instanceId);
            }
        }
        for (final String detached : source.detachedAddresses()) {
            if (!target.addresses().contains(detached)
                && !target.detachedAddresses().contains(detached)) {
                target.detachedAddresses().add(detached);
            }
        }
    }

    /**
     * Detaches {@code addressId} from its server. The canonical id itself cannot be unlinked: it
     * names the directory the data lives in. Returns false when the address was not linked.
     */
    public boolean unlinkAddress(final String addressId) {
        Objects.requireNonNull(addressId, "addressId");
        final Optional<String> owner = canonicalForAddress(addressId);
        if (owner.isEmpty()) {
            return false;
        }
        if (owner.get().equals(addressId)) {
            throw new IllegalArgumentException("cannot unlink the canonical address " + addressId);
        }
        final Entry entry = servers().get(owner.get());
        if (!entry.detachedAddresses().contains(addressId)) {
            entry.detachedAddresses().add(addressId);
        }
        return entry.addresses().remove(addressId);
    }

    /** Every address spelling linked to {@code canonicalId}, canonical one first. */
    public List<String> addresses(final String canonicalId) {
        final Entry entry = servers().get(canonicalId);
        return entry == null ? List.of() : List.copyOf(entry.addresses());
    }

    /** Addresses the player detached from {@code canonicalId}, which learning now skips. */
    public List<String> detachedAddresses(final String canonicalId) {
        final Entry entry = servers().get(canonicalId);
        return entry == null ? List.of() : List.copyOf(entry.detachedAddresses());
    }

    /**
     * Position of {@code worldId} among the worlds seen on this server, counting from 1, or 0 when
     * it has not been recorded. Callers use it to label an unnamed world; it is stable because
     * companion world ids are only ever appended.
     */
    public int worldOrdinal(final String canonicalId, final String worldId) {
        final Entry entry = servers().get(canonicalId);
        return entry == null ? 0 : entry.companionWorldIds().indexOf(worldId) + 1;
    }

    public List<String> canonicalIds() {
        return List.copyOf(servers().keySet());
    }

    /** Drops malformed entries left by hand-edited or future-schema files. */
    public void normalize() {
        if (schemaVersion > SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported server alias schema " + schemaVersion);
        }
        schemaVersion = SCHEMA_VERSION;
        final Map<String, Entry> normalized = new LinkedHashMap<>();
        final List<String> claimed = new ArrayList<>();
        for (final Map.Entry<String, Entry> server : servers().entrySet()) {
            final String canonicalId = server.getKey();
            if (canonicalId == null || canonicalId.isBlank() || server.getValue() == null) {
                continue;
            }
            final Entry entry = server.getValue();
            entry.normalize(canonicalId, claimed);
            claimed.addAll(entry.addresses());
            normalized.put(canonicalId, entry);
        }
        servers = normalized;
    }

    private Entry require(final String canonicalId) {
        final Entry entry = servers().get(Objects.requireNonNull(canonicalId, "canonicalId"));
        if (entry == null) {
            throw new IllegalArgumentException("unknown canonical server id " + canonicalId);
        }
        return entry;
    }

    private Map<String, Entry> servers() {
        if (servers == null) {
            servers = new LinkedHashMap<>();
        }
        return servers;
    }

    /**
     * One server's address spellings, the instance ids proving they are that server, and the
     * world UUIDs it has hosted. The two id lists answer different questions: an instance id says
     * <em>which server</em>, a world UUID says <em>which world on it</em>, and only the former
     * survives having the world copied elsewhere.
     */
    public static final class Entry {
        private List<String> addresses = new ArrayList<>();
        private List<String> companionWorldIds = new ArrayList<>();
        private List<String> companionInstanceIds = new ArrayList<>();
        private List<String> detachedAddresses = new ArrayList<>();

        List<String> addresses() {
            if (addresses == null) {
                addresses = new ArrayList<>();
            }
            return addresses;
        }

        List<String> companionWorldIds() {
            if (companionWorldIds == null) {
                companionWorldIds = new ArrayList<>();
            }
            return companionWorldIds;
        }

        List<String> companionInstanceIds() {
            if (companionInstanceIds == null) {
                companionInstanceIds = new ArrayList<>();
            }
            return companionInstanceIds;
        }

        List<String> detachedAddresses() {
            if (detachedAddresses == null) {
                detachedAddresses = new ArrayList<>();
            }
            return detachedAddresses;
        }

        /**
         * Keeps the canonical id first and drops blanks, duplicates, and addresses another entry
         * already claimed, so a hand-edited file cannot make one address resolve two ways.
         */
        void normalize(final String canonicalId, final List<String> claimed) {
            addresses = dedupe(addresses(), canonicalId, claimed);
            companionWorldIds = dedupe(companionWorldIds(), null, List.of());
            companionInstanceIds = dedupe(companionInstanceIds(), null, List.of());
            detachedAddresses = dedupe(detachedAddresses(), null, addresses);
        }

        private static List<String> dedupe(
            final List<String> values,
            final String first,
            final List<String> excluded
        ) {
            final List<String> unique = new ArrayList<>();
            if (first != null) {
                unique.add(first);
            }
            for (final String value : values) {
                if (value != null && !value.isBlank()
                    && !unique.contains(value) && !excluded.contains(value)) {
                    unique.add(value);
                }
            }
            return unique;
        }
    }
}
