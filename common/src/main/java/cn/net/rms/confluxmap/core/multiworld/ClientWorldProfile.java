package cn.net.rms.confluxmap.core.multiworld;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** One client-owned logical world namespace beneath a multiplayer address. */
public final class ClientWorldProfile {
    private static final int MAX_BINDINGS = ClientWorldPolicy.MAX_MAX_BINDINGS_PER_PROFILE;
    private static final int MAX_TEXT_LENGTH = 128;
    static final int MAX_TERRAIN_ANCHORS = 8;
    private String id;
    private String storageId;
    /** Physical server folder retained when a default-port registry alias is merged. */
    private String storageServerId;
    private String displayName;
    private List<Binding> bindings;
    private List<String> switchCommands;
    private Map<String, ClientWorldVisit> visits;
    private boolean recognitionDisabled;
    private String velocityServerName;

    ClientWorldProfile() {
        // Gson
    }

    ClientWorldProfile(final String id, final String storageId, final String displayName) {
        this.id = requireText(id, "id");
        this.storageId = requireText(storageId, "storageId");
        this.displayName = requireText(displayName, "displayName");
        this.bindings = new ArrayList<>();
        this.switchCommands = new ArrayList<>();
        this.visits = new LinkedHashMap<>();
    }

    public String id() {
        return id;
    }

    public String storageId() {
        return storageId;
    }

    public String storageServerId(final String logicalServerId) {
        return storageServerId == null || storageServerId.isBlank() ? logicalServerId : storageServerId;
    }

    void retainStorageServer(final String serverId) {
        if (storageServerId == null || storageServerId.isBlank()) {
            storageServerId = requireText(serverId, "storageServerId");
        }
    }

    void reidentify(final String replacementId) {
        id = requireText(replacementId, "id");
    }

    public String displayName() {
        return displayName;
    }

    public int bindingCount() {
        return bindings == null ? 0 : bindings.size();
    }

    /** Redacted seed signatures suitable for UI diagnostics; the raw server seed is never exposed. */
    public List<String> seedSignatures() {
        return bindings().stream()
            .filter(binding -> binding.hasSeed)
            .map(binding -> ClientWorldSignalHasher.hash(Long.toUnsignedString(binding.seedHash)).substring(0, 12))
            .distinct()
            .toList();
    }

    /** Exact normalized commands that select this profile on its owning server. */
    public List<String> switchCommands() {
        return List.copyOf(mutableSwitchCommands());
    }

    /** Per-dimension terrain/context history. Only {@link #lastObservedVisit()} owns position. */
    public List<ClientWorldVisit> visits() {
        return List.copyOf(mutableVisits().values());
    }

    public ClientWorldVisit visit(final String dimensionId) {
        return mutableVisits().get(dimensionId);
    }

    /** The profile has exactly one latest positional state across every dimension. */
    public ClientWorldVisit lastObservedVisit() {
        ClientWorldVisit latest = null;
        for (final ClientWorldVisit visit : mutableVisits().values()) {
            if (visit.hasContinuityEvidence()
                && (latest == null || visit.lastVisitedAtEpochMs() >= latest.lastVisitedAtEpochMs())) {
                latest = visit;
            }
        }
        return latest;
    }

    /** Positional continuity is available only when the latest state is in this dimension. */
    public ClientWorldVisit lastObservedVisit(final String dimensionId) {
        final ClientWorldVisit latest = lastObservedVisit();
        return latest != null && latest.dimensionId().equals(dimensionId) ? latest : null;
    }

    void rename(final String name) {
        displayName = requireText(name, "name");
    }

    void clearBindings() {
        bindings().clear();
        velocityServerName = null;
        recognitionDisabled = true;
    }

    boolean recognitionDisabled() {
        return recognitionDisabled;
    }

    boolean hasKnownSeedBinding() {
        return bindings().stream().anyMatch(binding -> binding.hasSeed);
    }

    boolean matchesSeed(final long seedHash) {
        for (final Binding binding : bindings()) {
            if (binding.hasSeed && binding.seedHash == seedHash) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true only when every stored binding disagrees with at least one signal it also
     * knows about. A seed collision must not silently absorb a world whose observable metadata
     * contradicts every previous visit.
     */
    boolean hasSignalConflict(final ClientWorldObservation observation) {
        if (observation.signals().isEmpty() || bindings().isEmpty()) {
            return false;
        }
        boolean compared = false;
        for (final Binding binding : bindings()) {
            boolean conflicts = false;
            boolean hasSharedSignal = false;
            for (final Map.Entry<String, String> signal : observation.signals().entrySet()) {
                if (isVisitSignal(signal.getKey())) {
                    continue;
                }
                final String known = binding.signals.get(signal.getKey());
                if (known == null) {
                    continue;
                }
                compared = true;
                hasSharedSignal = true;
                if (!known.equals(signal.getValue())) {
                    conflicts = true;
                    break;
                }
            }
            if (!hasSharedSignal || !conflicts) {
                return false;
            }
        }
        return compared;
    }

    /** Number of matching profile-level signals in the best compatible historical binding. */
    int matchingIdentitySignalCount(final ClientWorldObservation observation) {
        return identitySignalMatch(observation).matches();
    }

    IdentitySignalMatch identitySignalMatch(final ClientWorldObservation observation) {
        int best = 0;
        int comparable = 0;
        boolean sawConflict = false;
        for (final Binding binding : bindings()) {
            int matches = 0;
            int shared = 0;
            boolean conflict = false;
            for (final Map.Entry<String, String> signal : observation.signals().entrySet()) {
                if (isVisitSignal(signal.getKey())) {
                    continue;
                }
                final String known = binding.signals.get(signal.getKey());
                if (known == null) {
                    continue;
                }
                shared++;
                if (known.equals(signal.getValue())) {
                    matches++;
                } else {
                    conflict = true;
                }
            }
            comparable = Math.max(comparable, shared);
            sawConflict |= conflict && shared > 0;
            if (!conflict) {
                best = Math.max(best, matches);
            }
        }
        return new IdentitySignalMatch(best, comparable, sawConflict && best == 0);
    }

    record IdentitySignalMatch(int matches, int comparable, boolean conflict) { }

    void bind(final ClientWorldObservation observation) {
        bind(observation, ClientWorldPolicy.DEFAULT_MAX_BINDINGS_PER_PROFILE);
    }

    void bind(final ClientWorldObservation observation, final int configuredLimit) {
        final int bindingLimit = Math.max(1, Math.min(MAX_BINDINGS, configuredLimit));
        final Binding next = Binding.from(observation);
        if (!bindings().contains(next)) {
            // A newly lowered setting must not purge many historical records in one mutation.
            // Evict at most one oldest binding while preventing any further collection growth.
            if (bindings().size() >= bindingLimit) {
                bindings().remove(0);
            }
            bindings.add(next);
        }
        rememberVisit(observation);
        recognitionDisabled = false;
    }

    /**
     * Refreshes display and scoring evidence after a profile is already confirmed. This does not
     * add a new identity binding, because positions and terrain fingerprints naturally evolve
     * during a visit.
     */
    void rememberVisit(final ClientWorldObservation observation) {
        if (observation.dimensionId() != null) {
            final ClientWorldVisit previous = mutableVisits().get(observation.dimensionId());
            final long visitedAt = nextVisitTimestamp();
            final ClientWorldTerrainFingerprint observedTerrain = observation.terrainFingerprint();
            final boolean usableTerrain = observedTerrain != null
                && observedTerrain.complete() && observedTerrain.hasCenter()
                && terrainCenterMatchesPosition(observedTerrain, observation.position());
            final ClientWorldVisit next = new ClientWorldVisit(
                observation.dimensionId(),
                observation.gameMode() == null && previous != null ? previous.gameMode() : observation.gameMode(),
                observation.position() == null
                    ? previous == null ? null : previous.lastPosition() : observation.position(),
                visitedAt,
                previous == null ? null : previous.terrainFingerprint(),
                ClientWorldVisit.mergeContextSignals(
                    previous == null ? Map.of() : previous.contextSignals(), observation.signals()
                )
            );
            if (previous != null) {
                next.copyPersistedEvidenceFrom(previous);
            }
            if (usableTerrain) {
                next.rememberTerrainAnchor(new ClientWorldTerrainAnchor(
                    observation.position(), observedTerrain, visitedAt
                ));
            }
            next.rememberTrajectory(observation.trajectory());
            // Terrain and context remain dimension-scoped history, but position/trajectory describe
            // only where this profile most recently existed. Old dimensions must never compete as
            // alternative "last" coordinates.
            for (final ClientWorldVisit historical : mutableVisits().values()) {
                if (historical != previous) {
                    historical.clearContinuityEvidence();
                }
            }
            mutableVisits().put(observation.dimensionId(), next);
        }
    }

    /** A persisted visit must pair its position with a fingerprint captured at that same chunk. */
    private static boolean terrainCenterMatchesPosition(
        final ClientWorldTerrainFingerprint terrain,
        final ClientWorldPosition position
    ) {
        if (position == null) {
            return false;
        }
        return (position.x() >> 4) == terrain.centerChunkX()
            && (position.z() >> 4) == terrain.centerChunkZ();
    }

    boolean unbind(final ClientWorldObservation observation) {
        return bindings().remove(Binding.from(observation));
    }

    boolean hasSwitchCommand(final String normalizedCommand) {
        return mutableSwitchCommands().contains(normalizedCommand);
    }

    boolean addSwitchCommand(final String normalizedCommand) {
        if (mutableSwitchCommands().contains(normalizedCommand)) {
            return false;
        }
        switchCommands.add(normalizedCommand);
        return true;
    }

    boolean removeSwitchCommand(final String normalizedCommand) {
        return mutableSwitchCommands().remove(normalizedCommand);
    }

    List<String> retainUnclaimedSwitchCommands(final java.util.Set<String> claimedCommands) {
        final List<String> discarded = new ArrayList<>();
        final List<String> retained = new ArrayList<>();
        for (final String command : mutableSwitchCommands()) {
            if (claimedCommands.add(command)) {
                retained.add(command);
            } else {
                discarded.add(command);
            }
        }
        switchCommands = retained;
        return discarded;
    }

    void normalize() {
        id = requireText(id, "id");
        storageId = requireText(storageId, "storageId");
        if (storageServerId != null && !storageServerId.isBlank()) {
            storageServerId = requireText(storageServerId, "storageServerId");
        } else {
            storageServerId = null;
        }
        displayName = requireText(displayName, "displayName");
        if (velocityServerName != null) {
            velocityServerName = requireText(velocityServerName, "velocityServerName");
        }
        final List<Binding> valid = new ArrayList<>();
        if (bindings != null) {
            for (final Binding binding : bindings) {
                if (binding != null) {
                    binding.normalize();
                    if (!valid.contains(binding)) {
                        valid.add(binding);
                    }
                }
            }
        }
        bindings = valid.size() <= MAX_BINDINGS
            ? valid
            : new ArrayList<>(valid.subList(valid.size() - MAX_BINDINGS, valid.size()));
        final List<String> normalizedCommands = new ArrayList<>();
        if (switchCommands != null) {
            for (final String command : switchCommands) {
                try {
                    final String normalized = ClientWorldCommand.normalizeConfigured(command);
                    if (!normalizedCommands.contains(normalized)) {
                        normalizedCommands.add(normalized);
                    }
                } catch (final IllegalArgumentException ignored) {
                    // Older files did not carry commands; malformed optional entries are ignored.
                }
            }
        }
        switchCommands = normalizedCommands;
        final Map<String, ClientWorldVisit> normalizedVisits = new LinkedHashMap<>();
        if (visits != null) {
            for (final ClientWorldVisit visit : visits.values()) {
                if (visit == null) {
                    continue;
                }
                try {
                    visit.normalize();
                    normalizedVisits.putIfAbsent(visit.dimensionId(), visit);
                } catch (final IllegalArgumentException ignored) {
                    // A malformed optional visit record must not discard the entire profile.
                }
            }
        }
        visits = normalizedVisits;
        // Schema 3 stored one positional endpoint per dimension. Migrate it in memory to the
        // single-endpoint model by retaining only the newest positional visit; terrain anchors and
        // context on the older dimensions remain intact.
        ClientWorldVisit latest = null;
        for (final ClientWorldVisit visit : visits.values()) {
            if (visit.hasContinuityEvidence()
                && (latest == null || visit.lastVisitedAtEpochMs() >= latest.lastVisitedAtEpochMs())) {
                latest = visit;
            }
        }
        for (final ClientWorldVisit visit : visits.values()) {
            if (visit != latest) {
                visit.clearContinuityEvidence();
            }
        }
    }

    ClientWorldProfile copy() {
        final ClientWorldProfile copy = new ClientWorldProfile();
        copy.id = id;
        copy.storageId = storageId;
        copy.storageServerId = storageServerId;
        copy.displayName = displayName;
        copy.bindings = new ArrayList<>();
        for (final Binding binding : bindings()) {
            copy.bindings.add(binding.copy());
        }
        copy.switchCommands = new ArrayList<>(mutableSwitchCommands());
        copy.visits = new LinkedHashMap<>();
        for (final Map.Entry<String, ClientWorldVisit> entry : mutableVisits().entrySet()) {
            copy.visits.put(entry.getKey(), entry.getValue().copy());
        }
        copy.recognitionDisabled = recognitionDisabled;
        return copy;
    }

    void replaceWith(final ClientWorldProfile source) {
        final ClientWorldProfile copy = source.copy();
        id = copy.id;
        storageId = copy.storageId;
        storageServerId = copy.storageServerId;
        displayName = copy.displayName;
        bindings = copy.bindings;
        switchCommands = copy.switchCommands;
        visits = copy.visits;
        recognitionDisabled = copy.recognitionDisabled;
    }

    private List<Binding> bindings() {
        if (bindings == null) {
            bindings = new ArrayList<>();
        }
        return bindings;
    }

    private List<String> mutableSwitchCommands() {
        if (switchCommands == null) {
            switchCommands = new ArrayList<>();
        }
        return switchCommands;
    }

    private Map<String, ClientWorldVisit> mutableVisits() {
        if (visits == null) {
            visits = new LinkedHashMap<>();
        }
        return visits;
    }

    private long nextVisitTimestamp() {
        long newest = -1L;
        for (final ClientWorldVisit visit : mutableVisits().values()) {
            newest = Math.max(newest, visit.lastVisitedAtEpochMs());
        }
        final long wallClock = System.currentTimeMillis();
        return newest == Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(wallClock, newest + 1L);
    }

    private static String requireText(final String value, final String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(field + " exceeds " + MAX_TEXT_LENGTH + " characters");
        }
        if ("storageId".equals(field) && !isSafeStorageId(value)) {
            throw new IllegalArgumentException("storageId is not a supported local namespace");
        }
        if ("storageServerId".equals(field)
            && (!value.matches("[a-z0-9._-]+") || ".".equals(value) || "..".equals(value))) {
            throw new IllegalArgumentException("storageServerId is not a supported local namespace");
        }
        return value;
    }

    private static boolean isSafeStorageId(final String value) {
        return "world".equals(value)
            || value.matches("client-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }

    /**
     * Dimension, spawn and border values describe a visit, not the logical profile. Keeping them
     * out of profile-level conflict checks lets one seed-backed profile learn Nether/End visits
     * without making the overworld binding contradict it.
     */
    private static boolean isVisitSignal(final String key) {
        return "dimension".equals(key)
            || "dimension_type".equals(key)
            || "world_shape".equals(key)
            || "difficulty".equals(key)
            || "spawn".equals(key)
            || "world_border".equals(key);
    }

    private static final class Binding {
        private boolean hasSeed;
        private long seedHash;
        private Map<String, String> signals;

        private Binding() {
            // Gson
        }

        static Binding from(final ClientWorldObservation observation) {
            final Binding binding = new Binding();
            final OptionalLong seed = observation.seedHash();
            binding.hasSeed = seed.isPresent();
            binding.seedHash = seed.orElse(0L);
            binding.signals = new LinkedHashMap<>(observation.signals());
            return binding;
        }

        void normalize() {
            signals = signals == null ? new LinkedHashMap<>() : new LinkedHashMap<>(signals);
            signals.entrySet().removeIf(entry -> entry.getKey() == null || entry.getKey().isBlank()
                || entry.getValue() == null || entry.getValue().isBlank());
        }

        Binding copy() {
            final Binding copy = new Binding();
            copy.hasSeed = hasSeed;
            copy.seedHash = seedHash;
            copy.signals = new LinkedHashMap<>(signals == null ? Map.of() : signals);
            return copy;
        }

        @Override
        public boolean equals(final Object other) {
            return this == other || other instanceof final Binding binding
                && hasSeed == binding.hasSeed
                && seedHash == binding.seedHash
                && Objects.equals(signals, binding.signals);
        }

        @Override
        public int hashCode() {
            return Objects.hash(hasSeed, seedHash, signals);
        }
    }
}
