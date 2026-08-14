package cn.net.rms.confluxmap.core.multiworld;

import cn.net.rms.confluxmap.core.model.WorldIdentity;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Maps the address a player typed to the storage namespace their data actually lives in.
 *
 * <p>Two spellings of one server merge in two ways. Cosmetic differences (case, trailing dot,
 * explicit default port) collapse through {@link ServerAddressNormalizer}. Genuinely different
 * hostnames merge only on evidence: a companion server advertises an instance id stored outside
 * its world save, so meeting that id again identifies the same server whatever address reached it.
 * Without a companion there is no such evidence and the link stays a user decision.
 *
 * <p>The world UUID is deliberately not that evidence. It lives inside the save and so travels
 * with a copy: a mirror server synced from a survival server advertises the survival world's UUID
 * while being a different server, and behind a proxy the two share an address as well. Merging on
 * it would fold their map data together with no way back.
 *
 * <p>Learning never silently merges two directories that both already hold data — that would mix
 * two histories with no way back. It only adopts an address that has written nothing yet; an
 * address with its own data keeps it and is reported as {@link Origin#CONFLICT} so the caller can
 * offer an explicit, confirmed migration.
 */
public final class ServerAliasResolver {
    private final ServerAliasRegistry registry;
    private final Predicate<String> storageExists;
    private final Runnable onChange;

    /**
     * @param storageExists whether a storage id already holds data on disk; decides which spelling
     *                      becomes canonical and blocks learning that would strand a directory
     */
    public ServerAliasResolver(
        final ServerAliasRegistry registry,
        final Predicate<String> storageExists,
        final Runnable onChange
    ) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.storageExists = Objects.requireNonNull(storageExists, "storageExists");
        this.onChange = Objects.requireNonNull(onChange, "onChange");
    }

    /** How {@code canonicalId} was reached; drives whether the caller prompts the player. */
    public enum Origin {
        /** The address was already linked to this server. */
        KNOWN,
        /** First time this address is seen; it now owns a fresh namespace. */
        NEW,
        /** The address inherited the namespace of an unnormalized spelling that holds data. */
        ADOPTED_LEGACY,
        /** A companion instance id proved this address is a server already known under another name. */
        LEARNED,
        /** Same proof as {@link #LEARNED}, but both namespaces hold data, so nothing was merged. */
        CONFLICT
    }

    /**
     * @param canonicalId storage id to namespace this session under
     * @param mergeTarget for {@link Origin#CONFLICT}, the namespace this server is known as
     */
    public record Resolution(String canonicalId, Origin origin, String mergeTarget) {
        public Resolution {
            Objects.requireNonNull(canonicalId, "canonicalId");
            Objects.requireNonNull(origin, "origin");
        }
    }

    /** Resolves without companion evidence. */
    public String resolve(final String rawAddress) {
        return resolve(rawAddress, null, null).canonicalId();
    }

    /**
     * @param companionInstanceId identity of the server itself, or null when it runs no companion,
     *                            has not completed its handshake, or predates the capability;
     *                            this is the only evidence that merges two addresses
     * @param companionWorldId    world UUID the companion advertised, recorded so the worlds one
     *                            server has hosted can be listed - never used to merge, because a
     *                            copied world carries it to a different server
     */
    public Resolution resolve(
        final String rawAddress,
        final String companionInstanceId,
        final String companionWorldId
    ) {
        final String addressId = addressId(rawAddress);
        final String legacyId = WorldIdentity.serverId(rawAddress);

        final Optional<String> known = registry.canonicalForAddress(addressId);
        if (known.isPresent()) {
            final String canonicalId = known.get();
            learnCompanionIds(canonicalId, companionInstanceId, companionWorldId);
            return new Resolution(canonicalId, Origin.KNOWN, null);
        }

        final Optional<String> sameInstance = companionInstanceId == null
            ? Optional.empty()
            : registry.canonicalForCompanionInstance(companionInstanceId)
                .filter(candidate -> !registry.isDetached(candidate, addressId));
        if (sameInstance.isPresent() && !holdsData(addressId) && !holdsData(legacyId)) {
            final String canonicalId = sameInstance.get();
            linkIfUnowned(canonicalId, addressId);
            linkIfUnowned(canonicalId, legacyId);
            learnCompanionWorld(canonicalId, companionWorldId);
            onChange.run();
            return new Resolution(canonicalId, Origin.LEARNED, null);
        }

        final boolean adoptLegacy = !legacyId.equals(addressId)
            && holdsData(legacyId)
            && !holdsData(addressId);
        final String canonicalId = adoptLegacy ? legacyId : addressId;
        registry.create(canonicalId);
        linkIfUnowned(canonicalId, addressId);
        linkIfUnowned(canonicalId, legacyId);
        learnCompanionIds(canonicalId, companionInstanceId, companionWorldId);
        onChange.run();
        if (sameInstance.isPresent()) {
            return new Resolution(canonicalId, Origin.CONFLICT, sameInstance.get());
        }
        return new Resolution(canonicalId, adoptLegacy ? Origin.ADOPTED_LEGACY : Origin.NEW, null);
    }

    /**
     * Links an address to a server by explicit user choice, for servers with no companion to prove
     * the relationship. Refuses rather than silently doing nothing when the address is spoken for:
     * an address linked elsewhere needs {@link #unlink}, and one that owns stored data of its own
     * needs {@link #absorb} after that data has been migrated.
     */
    public void link(final String canonicalId, final String rawAddress) {
        requireKnown(canonicalId);
        final String addressId = addressId(rawAddress);
        final String legacyId = WorldIdentity.serverId(rawAddress);
        requireUnowned(canonicalId, addressId);
        requireUnowned(canonicalId, legacyId);
        final boolean linkedNormalized = linkIfUnowned(canonicalId, addressId);
        final boolean linkedLegacy = linkIfUnowned(canonicalId, legacyId);
        if (linkedNormalized || linkedLegacy) {
            onChange.run();
        }
    }

    /**
     * Folds the namespace {@code rawAddress} currently resolves to into {@code canonicalId}, so
     * every address that reached the old namespace now reaches this one. Call only after the
     * stored data has been migrated: the old namespace becomes unreachable.
     */
    public void absorb(final String canonicalId, final String rawAddress) {
        requireKnown(canonicalId);
        final String addressId = addressId(rawAddress);
        final String source = registry.canonicalForAddress(addressId)
            .orElseThrow(() -> new IllegalArgumentException("unknown address " + addressId));
        registry.absorb(canonicalId, source);
        onChange.run();
    }

    /** Detaches an address so it resolves to its own namespace again. */
    public void unlink(final String rawAddress) {
        final boolean changed = registry.unlinkAddress(addressId(rawAddress));
        if (changed) {
            onChange.run();
        }
    }

    public List<String> addresses(final String canonicalId) {
        return registry.addresses(canonicalId);
    }

    public List<String> detachedAddresses(final String canonicalId) {
        return registry.detachedAddresses(canonicalId);
    }

    /**
     * 1-based position among the worlds seen on this server; 0 when it has not been recorded.
     * This is identity, not presentation: it orders the world ids this server has advertised.
     * What the player named a world lives with the other client-side world records instead.
     */
    public int worldOrdinal(final String canonicalId, final String worldId) {
        return registry.worldOrdinal(canonicalId, worldId);
    }

    /** What an address is currently doing, so a caller can offer the one action that applies. */
    public enum AddressState {
        /** Linked to the server asked about. */
        LINKED,
        /** Linked nowhere and storing nothing: {@link #link} will accept it. */
        FREE,
        /** Linked to a different server; it has to be detached there first. */
        TAKEN,
        /** Names a namespace holding its own map data, which linking would strand. */
        HOLDS_DATA
    }

    /** @param owner server the address is linked to, or null when it is linked nowhere */
    public record AddressStatus(String addressId, AddressState state, String owner) {
    }

    /** Reports what linking {@code rawAddress} to {@code canonicalId} would mean right now. */
    public AddressStatus inspect(final String canonicalId, final String rawAddress) {
        final String addressId = addressId(rawAddress);
        final String legacyId = WorldIdentity.serverId(rawAddress);
        final String owner = registry.canonicalForAddress(addressId)
            .or(() -> registry.canonicalForAddress(legacyId))
            .orElse(null);
        if (canonicalId.equals(owner)) {
            return new AddressStatus(addressId, AddressState.LINKED, owner);
        }
        if (owner != null) {
            // A namespace named after the address itself is where that address stores its data.
            final boolean ownsItsNamespace = owner.equals(addressId) || owner.equals(legacyId);
            return new AddressStatus(
                addressId, ownsItsNamespace ? AddressState.HOLDS_DATA : AddressState.TAKEN, owner
            );
        }
        if (holdsData(addressId) || holdsData(legacyId)) {
            return new AddressStatus(addressId, AddressState.HOLDS_DATA, null);
        }
        return new AddressStatus(addressId, AddressState.FREE, null);
    }

    public List<String> canonicalIds() {
        return registry.canonicalIds();
    }

    /** Storage id for {@code rawAddress} before any alias lookup. */
    public static String addressId(final String rawAddress) {
        return WorldIdentity.serverId(ServerAddressNormalizer.normalize(rawAddress));
    }

    private boolean linkIfUnowned(final String canonicalId, final String addressId) {
        return registry.canonicalForAddress(addressId).isEmpty()
            && registry.linkAddress(canonicalId, addressId);
    }

    private void requireKnown(final String canonicalId) {
        if (registry.addresses(canonicalId).isEmpty()) {
            throw new IllegalArgumentException("unknown canonical server id " + canonicalId);
        }
    }

    private void requireUnowned(final String canonicalId, final String addressId) {
        final Optional<String> owner = registry.canonicalForAddress(addressId);
        if (owner.isEmpty() || owner.get().equals(canonicalId)) {
            return;
        }
        throw new IllegalArgumentException(owner.get().equals(addressId)
            ? "address " + addressId + " owns stored data; migrate and absorb it instead"
            : "address " + addressId + " is already linked to " + owner.get());
    }

    private void learnCompanionIds(
        final String canonicalId,
        final String companionInstanceId,
        final String companionWorldId
    ) {
        learnCompanionInstance(canonicalId, companionInstanceId);
        learnCompanionWorld(canonicalId, companionWorldId);
    }

    /**
     * An instance id may point at one namespace only. When it already names another — the player
     * detached this address, or both namespaces hold data — the new namespace stays unmarked, so
     * later addresses keep learning the one namespace that owns the server.
     */
    private void learnCompanionInstance(final String canonicalId, final String companionInstanceId) {
        if (companionInstanceId == null) {
            return;
        }
        final Optional<String> owner = registry.canonicalForCompanionInstance(companionInstanceId);
        if (owner.isPresent() && !owner.get().equals(canonicalId)) {
            return;
        }
        if (registry.linkCompanionInstance(canonicalId, companionInstanceId)) {
            onChange.run();
        }
    }

    /**
     * Records which worlds a server has hosted, for listing them. Unlike an instance id the same
     * world UUID may legitimately appear under several servers once a save has been copied, so
     * this deliberately does not check for another owner.
     */
    private void learnCompanionWorld(final String canonicalId, final String companionWorldId) {
        if (companionWorldId == null) {
            return;
        }
        if (registry.linkCompanionWorld(canonicalId, companionWorldId)) {
            onChange.run();
        }
    }

    private boolean holdsData(final String storageId) {
        return storageExists.test(storageId);
    }
}
