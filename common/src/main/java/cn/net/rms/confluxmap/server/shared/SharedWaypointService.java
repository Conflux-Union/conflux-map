package cn.net.rms.confluxmap.server.shared;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointProto;
import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import cn.net.rms.confluxmap.core.shared.SharedWaypointGroup;
import cn.net.rms.confluxmap.core.shared.SharedWaypointLocationKey;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.apache.logging.log4j.Logger;

/**
 * Server-authoritative shared-waypoint mutation service.
 *
 * <p>All request data is treated as untrusted. The service owns IDs, publisher metadata,
 * timestamps, revisions, permissions, quotas, rate limiting, idempotency and durable commit.
 *
 * <p>Disconnects deliberately retain applied idempotency results so a reconnect retry cannot
 * duplicate a successful mutation. The access-ordered player map bounds retained state globally.
 */
public final class SharedWaypointService {
    public static final int IDEMPOTENCY_RESULTS_PER_PLAYER = 128;
    public static final int MAX_TRACKED_PLAYERS = 256;
    public static final int MUTATION_BURST = 10;

    public enum MutationStatus {
        APPLIED,
        REJECTED
    }

    public enum MutationError {
        NONE,
        INVALID_REQUEST,
        REVISION_CONFLICT,
        NOT_FOUND,
        FORBIDDEN,
        WORLD_QUOTA_EXCEEDED,
        PLAYER_QUOTA_EXCEEDED,
        RATE_LIMITED,
        DUPLICATE_LOCATION,
        PERSISTENCE_FAILED,
        ID_GENERATION_FAILED,
        GROUP_QUOTA_EXCEEDED
    }

    public enum Action {
        CREATE,
        UPDATE,
        DELETE,
        GROUP_CREATE,
        GROUP_RENAME,
        GROUP_DELETE
    }

    public enum AccessPolicy {
        OPERATOR_ONLY,
        OWNER_MANAGED
    }

    public record Limits(int maxPerWorld, int maxPerPlayer, int mutationsPerMinute) {
        public Limits {
            if (maxPerWorld < 1 || maxPerPlayer < 1 || maxPerPlayer > maxPerWorld || mutationsPerMinute < 1) {
                throw new IllegalArgumentException("invalid shared-waypoint limits");
            }
        }
    }

    public record Actor(UUID playerId, String playerName, boolean operator) {
        public Actor {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(playerName, "playerName");
        }
    }

    public record CreateRequest(
        UUID operationId,
        long expectedRevision,
        String name,
        DimensionId dimensionId,
        double x,
        double y,
        double z,
        int colorArgb,
        Waypoint.Type type,
        String iconItemId,
        String markerLabel,
        String description,
        String imageId,
        UUID groupId
    ) {
        public CreateRequest {
            Objects.requireNonNull(operationId, "operationId");
        }

        public CreateRequest(
            final UUID operationId,
            final long expectedRevision,
            final String name,
            final DimensionId dimensionId,
            final double x,
            final double y,
            final double z,
            final int colorArgb,
            final Waypoint.Type type,
            final String iconItemId,
            final String markerLabel,
            final UUID groupId
        ) {
            this(
                operationId, expectedRevision, name, dimensionId, x, y, z, colorArgb, type,
                iconItemId, markerLabel, "", "", groupId
            );
        }

        public CreateRequest(
            final UUID operationId,
            final long expectedRevision,
            final String name,
            final DimensionId dimensionId,
            final double x,
            final double y,
            final double z,
            final int colorArgb,
            final Waypoint.Type type,
            final String iconItemId,
            final String markerLabel
        ) {
            this(
                operationId, expectedRevision, name, dimensionId, x, y, z, colorArgb, type,
                iconItemId, markerLabel, null
            );
        }

        public CreateRequest(
            final UUID operationId,
            final long expectedRevision,
            final String name,
            final DimensionId dimensionId,
            final double x,
            final double y,
            final double z,
            final int colorArgb,
            final Waypoint.Type type
        ) {
            this(operationId, expectedRevision, name, dimensionId, x, y, z, colorArgb, type, "", "", null);
        }
    }

    public record DeleteRequest(UUID operationId, long expectedRevision, UUID waypointId) {
        public DeleteRequest {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(waypointId, "waypointId");
        }
    }

    public record GroupCreateRequest(UUID operationId, long expectedRevision, String name) {
        public GroupCreateRequest {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(name, "name");
        }
    }

    public record GroupRenameRequest(
        UUID operationId,
        long expectedRevision,
        UUID groupId,
        String name
    ) {
        public GroupRenameRequest {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(groupId, "groupId");
            Objects.requireNonNull(name, "name");
        }
    }

    public record GroupDeleteRequest(UUID operationId, long expectedRevision, UUID groupId) {
        public GroupDeleteRequest {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(groupId, "groupId");
        }
    }

    /** Moves one waypoint into a group, or out of every group when {@code groupId} is null. */
    public record SetGroupRequest(
        UUID operationId,
        long expectedRevision,
        UUID waypointId,
        UUID groupId
    ) {
        public SetGroupRequest {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(waypointId, "waypointId");
        }
    }

    /**
     * All-or-nothing multi-waypoint group move. The global revision guards a
     * stale catalog; each entry still carries its own waypoint revision so a
     * concurrently edited member rejects the whole batch.
     */
    public record MoveBatchRequest(UUID operationId, long expectedRevision, List<Entry> entries) {
        public MoveBatchRequest {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(entries, "entries");
        }

        public record Entry(UUID waypointId, long expectedWaypointRevision, UUID groupId) {
            public Entry {
                Objects.requireNonNull(waypointId, "waypointId");
            }
        }
    }

    public record UpdateRequest(
        UUID operationId,
        long expectedRevision,
        UUID waypointId,
        String name,
        DimensionId dimensionId,
        double x,
        double y,
        double z,
        int colorArgb,
        Waypoint.Type type,
        String iconItemId,
        String markerLabel,
        String description,
        String imageId,
        UUID groupId,
        boolean groupSpecified
    ) {
        public UpdateRequest {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(waypointId, "waypointId");
        }

        public UpdateRequest(
            final UUID operationId,
            final long expectedRevision,
            final UUID waypointId,
            final String name,
            final DimensionId dimensionId,
            final double x,
            final double y,
            final double z,
            final int colorArgb,
            final Waypoint.Type type,
            final String iconItemId,
            final String markerLabel,
            final UUID groupId,
            final boolean groupSpecified
        ) {
            this(
                operationId, expectedRevision, waypointId, name, dimensionId,
                x, y, z, colorArgb, type, iconItemId, markerLabel, "", "", groupId, groupSpecified
            );
        }

        public UpdateRequest(
            final UUID operationId,
            final long expectedRevision,
            final UUID waypointId,
            final String name,
            final DimensionId dimensionId,
            final double x,
            final double y,
            final double z,
            final int colorArgb,
            final Waypoint.Type type,
            final String iconItemId,
            final String markerLabel
        ) {
            this(
                operationId, expectedRevision, waypointId, name, dimensionId,
                x, y, z, colorArgb, type, iconItemId, markerLabel, null, false
            );
        }

        public UpdateRequest(
            final UUID operationId,
            final long expectedRevision,
            final UUID waypointId,
            final String name,
            final DimensionId dimensionId,
            final double x,
            final double y,
            final double z,
            final int colorArgb,
            final Waypoint.Type type
        ) {
            this(
                operationId, expectedRevision, waypointId, name, dimensionId,
                x, y, z, colorArgb, type, "", "", null, false
            );
        }
    }

    public record MutationResult(
        UUID operationId,
        MutationStatus status,
        MutationError error,
        SharedWaypointStore.Snapshot snapshot,
        List<SharedWaypointStore.Delta> deltas,
        boolean replayed
    ) {
        public MutationResult {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(error, "error");
            Objects.requireNonNull(snapshot, "snapshot");
            deltas = List.copyOf(deltas);
        }

        public boolean applied() {
            return status == MutationStatus.APPLIED;
        }

        private MutationResult asReplay() {
            return replayed
                ? this
                : new MutationResult(operationId, status, error, snapshot, deltas, true);
        }
    }

    /** Deliberately excludes waypoint/publisher names and coordinates. */
    public record AuditEvent(
        UUID operationId,
        UUID actorId,
        Action action,
        MutationStatus status,
        MutationError error,
        UUID waypointId,
        long revision,
        long timestampEpochMs
    ) {
    }

    @FunctionalInterface
    public interface AuditSink {
        void record(AuditEvent event);
    }

    private static final class PlayerState {
        private final Map<UUID, CachedMutation> results = new LinkedHashMap<UUID, CachedMutation>() {
            @Override
            protected boolean removeEldestEntry(final Map.Entry<UUID, CachedMutation> eldest) {
                return size() > IDEMPOTENCY_RESULTS_PER_PLAYER;
            }
        };
        private final MutationTokenBucket bucket;

        private PlayerState(final int mutationsPerMinute, final long nowEpochMs) {
            bucket = new MutationTokenBucket(MUTATION_BURST, mutationsPerMinute, nowEpochMs);
        }
    }

    private static final class CachedMutation {
        private final Object request;
        private final MutationResult result;
        private boolean collisionAudited;

        private CachedMutation(final Object request, final MutationResult result) {
            this.request = request;
            this.result = result;
        }
    }

    private static final class MutationTokenBucket {
        private final double capacity;
        private final double refillPerMillisecond;
        private double tokens;
        private long lastEpochMs;

        private MutationTokenBucket(final int capacity, final int refillPerMinute, final long nowEpochMs) {
            this.capacity = capacity;
            refillPerMillisecond = (double) refillPerMinute / 60_000d;
            tokens = capacity;
            lastEpochMs = nowEpochMs;
        }

        private boolean tryConsume(final long nowEpochMs) {
            if (nowEpochMs > lastEpochMs) {
                tokens = Math.min(capacity, tokens + (nowEpochMs - lastEpochMs) * refillPerMillisecond);
                lastEpochMs = nowEpochMs;
            }
            if (tokens < 1d) {
                return false;
            }
            tokens -= 1d;
            return true;
        }
    }

    private final SharedWaypointStore store;
    private final SharedWaypointPersistence persistence;
    private final SharedWaypointValidator validator;
    private final Clock clock;
    private final Supplier<UUID> idSupplier;
    private final Limits limits;
    private final AccessPolicy accessPolicy;
    private final AuditSink auditSink;
    private final Logger logger;
    private final Map<UUID, PlayerState> players = new LinkedHashMap<UUID, PlayerState>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<UUID, PlayerState> eldest) {
            return size() > MAX_TRACKED_PLAYERS;
        }
    };

    public SharedWaypointService(
        final SharedWaypointStore store,
        final SharedWaypointPersistence persistence,
        final SharedWaypointValidator validator,
        final Clock clock,
        final Supplier<UUID> idSupplier,
        final Limits limits,
        final AccessPolicy accessPolicy,
        final AuditSink auditSink,
        final Logger logger
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.idSupplier = Objects.requireNonNull(idSupplier, "idSupplier");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink");
        this.logger = Objects.requireNonNull(logger, "logger");
        for (final SharedWaypoint waypoint : store.snapshot().waypoints()) {
            if (!validForActiveWorld(waypoint, validator)) {
                throw new IllegalArgumentException("loaded shared waypoint is invalid for the active world");
            }
        }
        for (final SharedWaypointGroup group : store.snapshot().groups()) {
            if (!SharedWaypointValidator.validName(group.name())) {
                throw new IllegalArgumentException("loaded shared waypoint group has an invalid name");
            }
        }
    }

    public AccessPolicy accessPolicy() {
        return accessPolicy;
    }

    /**
     * Drops persisted waypoints that no longer validate against the active world (for example
     * after a datapack dimension was removed) so one bad entry cannot disable the whole feature.
     * Surviving entries keep the loaded revision; the server stays authoritative for clients.
     */
    public static SharedWaypointStore.Snapshot sanitizeLoaded(
        final SharedWaypointStore.Snapshot loaded,
        final SharedWaypointValidator validator,
        final Logger logger
    ) {
        Objects.requireNonNull(loaded, "loaded");
        Objects.requireNonNull(validator, "validator");
        Objects.requireNonNull(logger, "logger");
        final List<SharedWaypoint> valid = new ArrayList<>(loaded.waypoints().size());
        for (final SharedWaypoint waypoint : loaded.waypoints()) {
            if (validForActiveWorld(waypoint, validator)) {
                valid.add(waypoint);
            } else {
                logger.warn(
                    "[ConfluxMap] Quarantining persisted shared waypoint {} in {}: invalid for the active world",
                    waypoint.id(), waypoint.dimensionId()
                );
            }
        }
        return valid.size() == loaded.waypoints().size()
            ? loaded
            : new SharedWaypointStore.Snapshot(loaded.revision(), valid, loaded.groups());
    }

    private static boolean validForActiveWorld(
        final SharedWaypoint waypoint,
        final SharedWaypointValidator validator
    ) {
        return validator.validate(
            waypoint.name(), waypoint.dimensionId(), waypoint.x(), waypoint.y(), waypoint.z(),
            waypoint.colorArgb(), waypoint.type(), waypoint.iconItemId(), waypoint.markerLabel(),
            waypoint.description(), waypoint.imageId()
        ).isPresent() && validPublisherName(waypoint.publisherName());
    }

    public synchronized SharedWaypointStore.Snapshot snapshot() {
        return store.snapshot();
    }

    public synchronized List<SharedWaypointGroup> groups() {
        return store.groups();
    }

    public synchronized MutationResult create(final Actor actor, final CreateRequest request) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(request, "request");
        final PlayerState player = playerState(actor);
        final MutationResult replay = replayOrRejectReuse(
            player, actor, request.operationId(), Action.CREATE, null, request
        );
        if (replay != null) {
            return replay;
        }
        final long now = clock.millis();
        if (!canCreate(actor)) {
            return finish(player, request, actor, request.operationId(), Action.CREATE, null,
                rejected(request.operationId(), MutationError.FORBIDDEN), now);
        }
        if (!player.bucket.tryConsume(now)) {
            return finish(player, request, actor, request.operationId(), Action.CREATE, null,
                rejected(request.operationId(), MutationError.RATE_LIMITED), now);
        }
        if (request.expectedRevision() != store.snapshot().revision()) {
            return finish(player, request, actor, request.operationId(), Action.CREATE, null,
                rejected(request.operationId(), MutationError.REVISION_CONFLICT), now);
        }
        final Optional<SharedWaypointValidator.ValidatedCreate> validated = validator.validate(
            request.name(), request.dimensionId(), request.x(), request.y(), request.z(),
            request.colorArgb(), request.type(), request.iconItemId(), request.markerLabel(),
            request.description(), request.imageId()
        );
        final String publisherName = actor.playerName().strip();
        if (validated.isEmpty() || !validPublisherName(publisherName)) {
            return finish(player, request, actor, request.operationId(), Action.CREATE, null,
                rejected(request.operationId(), MutationError.INVALID_REQUEST), now);
        }
        final SharedWaypointValidator.ValidatedCreate value = validated.get();
        final SharedWaypointLocationKey location = SharedWaypointLocationKey.from(
            value.dimensionId(), value.x(), value.y(), value.z()
        );
        if (store.findAt(location).isPresent()) {
            return finish(player, request, actor, request.operationId(), Action.CREATE, null,
                rejected(request.operationId(), MutationError.DUPLICATE_LOCATION), now);
        }
        if (store.size() >= limits.maxPerWorld()) {
            return finish(player, request, actor, request.operationId(), Action.CREATE, null,
                rejected(request.operationId(), MutationError.WORLD_QUOTA_EXCEEDED), now);
        }
        if (store.countPublishedBy(actor.playerId()) >= limits.maxPerPlayer()) {
            return finish(player, request, actor, request.operationId(), Action.CREATE, null,
                rejected(request.operationId(), MutationError.PLAYER_QUOTA_EXCEEDED), now);
        }
        final UUID id = uniqueId();
        if (id == null) {
            return finish(player, request, actor, request.operationId(), Action.CREATE, null,
                rejected(request.operationId(), MutationError.ID_GENERATION_FAILED), now);
        }
        if (request.groupId() != null && store.findGroup(request.groupId()).isEmpty()) {
            return finish(player, request, actor, request.operationId(), Action.CREATE, null,
                rejected(request.operationId(), MutationError.NOT_FOUND), now);
        }
        final long nextRevision;
        try {
            nextRevision = Math.addExact(store.snapshot().revision(), 1);
        } catch (final ArithmeticException e) {
            return finish(player, request, actor, request.operationId(), Action.CREATE, null,
                rejected(request.operationId(), MutationError.PERSISTENCE_FAILED), now);
        }
        final SharedWaypoint waypoint = new SharedWaypoint(
            id, actor.playerId(), publisherName, value.name(), value.dimensionId(), value.x(), value.y(), value.z(),
            value.colorArgb(), value.type(), value.iconItemId(), value.markerLabel(),
            value.description(), value.imageId(), now, nextRevision,
            request.groupId()
        );
        return persist(
            player, request, actor, request.operationId(), Action.CREATE, id,
            store.prepareCreate(waypoint), now
        );
    }

    public synchronized MutationResult delete(final Actor actor, final DeleteRequest request) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(request, "request");
        final PlayerState player = playerState(actor);
        final MutationResult replay = replayOrRejectReuse(
            player, actor, request.operationId(), Action.DELETE, request.waypointId(), request
        );
        if (replay != null) {
            return replay;
        }
        final long now = clock.millis();
        if (!player.bucket.tryConsume(now)) {
            return finish(player, request, actor, request.operationId(), Action.DELETE, request.waypointId(),
                rejected(request.operationId(), MutationError.RATE_LIMITED), now);
        }
        final Optional<SharedWaypoint> existing = store.find(request.waypointId());
        if (existing.isEmpty()) {
            return finish(player, request, actor, request.operationId(), Action.DELETE, request.waypointId(),
                rejected(request.operationId(), MutationError.NOT_FOUND), now);
        }
        if (request.expectedRevision() != existing.get().revision()) {
            return finish(player, request, actor, request.operationId(), Action.DELETE, request.waypointId(),
                rejected(request.operationId(), MutationError.REVISION_CONFLICT), now);
        }
        final SharedWaypoint target = existing.get();
        if (!canManage(actor, target)) {
            return finish(player, request, actor, request.operationId(), Action.DELETE, request.waypointId(),
                rejected(request.operationId(), MutationError.FORBIDDEN), now);
        }
        final SharedWaypointStore.PreparedMutation mutation;
        try {
            mutation = store.prepareDelete(request.waypointId());
        } catch (final ArithmeticException e) {
            return finish(player, request, actor, request.operationId(), Action.DELETE, request.waypointId(),
                rejected(request.operationId(), MutationError.PERSISTENCE_FAILED), now);
        }
        return persist(player, request, actor, request.operationId(), Action.DELETE, request.waypointId(),
            mutation, now);
    }

    public synchronized MutationResult update(final Actor actor, final UpdateRequest request) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(request, "request");
        final PlayerState player = playerState(actor);
        final MutationResult replay = replayOrRejectReuse(
            player, actor, request.operationId(), Action.UPDATE, request.waypointId(), request
        );
        if (replay != null) {
            return replay;
        }
        final long now = clock.millis();
        if (!player.bucket.tryConsume(now)) {
            return finish(player, request, actor, request.operationId(), Action.UPDATE, request.waypointId(),
                rejected(request.operationId(), MutationError.RATE_LIMITED), now);
        }
        final Optional<SharedWaypoint> existing = store.find(request.waypointId());
        if (existing.isEmpty()) {
            return finish(player, request, actor, request.operationId(), Action.UPDATE, request.waypointId(),
                rejected(request.operationId(), MutationError.NOT_FOUND), now);
        }
        final SharedWaypoint current = existing.get();
        if (!canManage(actor, current)) {
            return finish(player, request, actor, request.operationId(), Action.UPDATE, request.waypointId(),
                rejected(request.operationId(), MutationError.FORBIDDEN), now);
        }
        if (request.expectedRevision() != current.revision()) {
            return finish(player, request, actor, request.operationId(), Action.UPDATE, request.waypointId(),
                rejected(request.operationId(), MutationError.REVISION_CONFLICT), now);
        }
        final Optional<SharedWaypointValidator.ValidatedCreate> validated = validator.validate(
            request.name(), request.dimensionId(), request.x(), request.y(), request.z(),
            request.colorArgb(), request.type(), request.iconItemId(), request.markerLabel(),
            request.description(), request.imageId()
        );
        if (validated.isEmpty()) {
            return finish(player, request, actor, request.operationId(), Action.UPDATE, request.waypointId(),
                rejected(request.operationId(), MutationError.INVALID_REQUEST), now);
        }
        final SharedWaypointValidator.ValidatedCreate value = validated.get();
        final SharedWaypointLocationKey currentLocation = SharedWaypointLocationKey.from(current);
        final SharedWaypointLocationKey updatedLocation = SharedWaypointLocationKey.from(
            value.dimensionId(), value.x(), value.y(), value.z()
        );
        final Optional<SharedWaypoint> occupant = store.findAt(updatedLocation);
        if (!updatedLocation.equals(currentLocation)
            && occupant.isPresent() && !occupant.get().id().equals(current.id())) {
            return finish(player, request, actor, request.operationId(), Action.UPDATE, request.waypointId(),
                rejected(request.operationId(), MutationError.DUPLICATE_LOCATION), now);
        }
        final UUID targetGroup = request.groupSpecified() ? request.groupId() : current.groupId();
        if (targetGroup != null && store.findGroup(targetGroup).isEmpty()) {
            return finish(player, request, actor, request.operationId(), Action.UPDATE, request.waypointId(),
                rejected(request.operationId(), MutationError.NOT_FOUND), now);
        }
        final long nextRevision;
        try {
            nextRevision = Math.addExact(store.snapshot().revision(), 1);
        } catch (final ArithmeticException e) {
            return finish(player, request, actor, request.operationId(), Action.UPDATE, request.waypointId(),
                rejected(request.operationId(), MutationError.PERSISTENCE_FAILED), now);
        }
        final SharedWaypoint updated = new SharedWaypoint(
            current.id(), current.publisherId(), current.publisherName(), value.name(),
            value.dimensionId(), value.x(), value.y(), value.z(), value.colorArgb(), value.type(),
            value.iconItemId(), value.markerLabel(), value.description(), value.imageId(),
            current.createdAtEpochMs(), nextRevision,
            targetGroup
        );
        final SharedWaypointStore.PreparedMutation mutation;
        try {
            mutation = store.prepareUpdate(updated);
        } catch (final ArithmeticException e) {
            return finish(player, request, actor, request.operationId(), Action.UPDATE, request.waypointId(),
                rejected(request.operationId(), MutationError.PERSISTENCE_FAILED), now);
        }
        return persist(
            player, request, actor, request.operationId(), Action.UPDATE, request.waypointId(),
            mutation, now
        );
    }

    public synchronized MutationResult createGroup(final Actor actor, final GroupCreateRequest request) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(request, "request");
        final PlayerState player = playerState(actor);
        final MutationResult replay = replayOrRejectReuse(
            player, actor, request.operationId(), Action.GROUP_CREATE, null, request
        );
        if (replay != null) {
            return replay;
        }
        final long now = clock.millis();
        if (!canCreate(actor)) {
            return finish(player, request, actor, request.operationId(), Action.GROUP_CREATE, null,
                rejected(request.operationId(), MutationError.FORBIDDEN), now);
        }
        if (!player.bucket.tryConsume(now)) {
            return finish(player, request, actor, request.operationId(), Action.GROUP_CREATE, null,
                rejected(request.operationId(), MutationError.RATE_LIMITED), now);
        }
        if (request.expectedRevision() != store.snapshot().revision()) {
            return finish(player, request, actor, request.operationId(), Action.GROUP_CREATE, null,
                rejected(request.operationId(), MutationError.REVISION_CONFLICT), now);
        }
        final String name = request.name().strip();
        if (!SharedWaypointValidator.validName(name)) {
            return finish(player, request, actor, request.operationId(), Action.GROUP_CREATE, null,
                rejected(request.operationId(), MutationError.INVALID_REQUEST), now);
        }
        if (store.groupCount() >= SharedWaypointProto.MAX_SNAPSHOT_GROUPS) {
            return finish(player, request, actor, request.operationId(), Action.GROUP_CREATE, null,
                rejected(request.operationId(), MutationError.GROUP_QUOTA_EXCEEDED), now);
        }
        final UUID id = uniqueGroupId();
        if (id == null) {
            return finish(player, request, actor, request.operationId(), Action.GROUP_CREATE, null,
                rejected(request.operationId(), MutationError.ID_GENERATION_FAILED), now);
        }
        final long nextRevision;
        try {
            nextRevision = Math.addExact(store.snapshot().revision(), 1);
        } catch (final ArithmeticException e) {
            return finish(player, request, actor, request.operationId(), Action.GROUP_CREATE, null,
                rejected(request.operationId(), MutationError.PERSISTENCE_FAILED), now);
        }
        final SharedWaypointGroup group = new SharedWaypointGroup(
            id, actor.playerId(), name, now, nextRevision
        );
        return persist(
            player, request, actor, request.operationId(), Action.GROUP_CREATE, id,
            store.prepareGroupCreate(group), now
        );
    }

    public synchronized MutationResult renameGroup(final Actor actor, final GroupRenameRequest request) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(request, "request");
        final PlayerState player = playerState(actor);
        final MutationResult replay = replayOrRejectReuse(
            player, actor, request.operationId(), Action.GROUP_RENAME, request.groupId(), request
        );
        if (replay != null) {
            return replay;
        }
        final long now = clock.millis();
        if (!player.bucket.tryConsume(now)) {
            return finish(player, request, actor, request.operationId(), Action.GROUP_RENAME, request.groupId(),
                rejected(request.operationId(), MutationError.RATE_LIMITED), now);
        }
        final Optional<SharedWaypointGroup> existing = store.findGroup(request.groupId());
        if (existing.isEmpty()) {
            return finish(player, request, actor, request.operationId(), Action.GROUP_RENAME, request.groupId(),
                rejected(request.operationId(), MutationError.NOT_FOUND), now);
        }
        final SharedWaypointGroup current = existing.get();
        if (!canManageGroup(actor, current)) {
            return finish(player, request, actor, request.operationId(), Action.GROUP_RENAME, request.groupId(),
                rejected(request.operationId(), MutationError.FORBIDDEN), now);
        }
        if (request.expectedRevision() != current.revision()) {
            return finish(player, request, actor, request.operationId(), Action.GROUP_RENAME, request.groupId(),
                rejected(request.operationId(), MutationError.REVISION_CONFLICT), now);
        }
        final String name = request.name().strip();
        if (!SharedWaypointValidator.validName(name)) {
            return finish(player, request, actor, request.operationId(), Action.GROUP_RENAME, request.groupId(),
                rejected(request.operationId(), MutationError.INVALID_REQUEST), now);
        }
        final long nextRevision;
        try {
            nextRevision = Math.addExact(store.snapshot().revision(), 1);
        } catch (final ArithmeticException e) {
            return finish(player, request, actor, request.operationId(), Action.GROUP_RENAME, request.groupId(),
                rejected(request.operationId(), MutationError.PERSISTENCE_FAILED), now);
        }
        final SharedWaypointGroup renamed = new SharedWaypointGroup(
            current.id(), current.creatorId(), name, current.createdAtEpochMs(), nextRevision
        );
        return persist(
            player, request, actor, request.operationId(), Action.GROUP_RENAME, request.groupId(),
            store.prepareGroupUpdate(renamed), now
        );
    }

    public synchronized MutationResult deleteGroup(final Actor actor, final GroupDeleteRequest request) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(request, "request");
        final PlayerState player = playerState(actor);
        final MutationResult replay = replayOrRejectReuse(
            player, actor, request.operationId(), Action.GROUP_DELETE, request.groupId(), request
        );
        if (replay != null) {
            return replay;
        }
        final long now = clock.millis();
        if (!player.bucket.tryConsume(now)) {
            return finish(player, request, actor, request.operationId(), Action.GROUP_DELETE, request.groupId(),
                rejected(request.operationId(), MutationError.RATE_LIMITED), now);
        }
        final Optional<SharedWaypointGroup> existing = store.findGroup(request.groupId());
        if (existing.isEmpty()) {
            return finish(player, request, actor, request.operationId(), Action.GROUP_DELETE, request.groupId(),
                rejected(request.operationId(), MutationError.NOT_FOUND), now);
        }
        final SharedWaypointGroup current = existing.get();
        if (!canManageGroup(actor, current)) {
            return finish(player, request, actor, request.operationId(), Action.GROUP_DELETE, request.groupId(),
                rejected(request.operationId(), MutationError.FORBIDDEN), now);
        }
        if (request.expectedRevision() != current.revision()) {
            return finish(player, request, actor, request.operationId(), Action.GROUP_DELETE, request.groupId(),
                rejected(request.operationId(), MutationError.REVISION_CONFLICT), now);
        }
        final SharedWaypointStore.PreparedMutation mutation;
        try {
            mutation = store.prepareGroupDelete(request.groupId());
        } catch (final ArithmeticException e) {
            return finish(player, request, actor, request.operationId(), Action.GROUP_DELETE, request.groupId(),
                rejected(request.operationId(), MutationError.PERSISTENCE_FAILED), now);
        }
        return persist(
            player, request, actor, request.operationId(), Action.GROUP_DELETE, request.groupId(),
            mutation, now
        );
    }

    /** Moves one waypoint into a group (or out of every group with a null target). */
    public synchronized MutationResult setWaypointGroup(final Actor actor, final SetGroupRequest request) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(request, "request");
        final PlayerState player = playerState(actor);
        final MutationResult replay = replayOrRejectReuse(
            player, actor, request.operationId(), Action.UPDATE, request.waypointId(), request
        );
        if (replay != null) {
            return replay;
        }
        final long now = clock.millis();
        if (!player.bucket.tryConsume(now)) {
            return finish(player, request, actor, request.operationId(), Action.UPDATE, request.waypointId(),
                rejected(request.operationId(), MutationError.RATE_LIMITED), now);
        }
        final Optional<SharedWaypoint> existing = store.find(request.waypointId());
        if (existing.isEmpty()) {
            return finish(player, request, actor, request.operationId(), Action.UPDATE, request.waypointId(),
                rejected(request.operationId(), MutationError.NOT_FOUND), now);
        }
        final SharedWaypoint current = existing.get();
        if (!canManage(actor, current)) {
            return finish(player, request, actor, request.operationId(), Action.UPDATE, request.waypointId(),
                rejected(request.operationId(), MutationError.FORBIDDEN), now);
        }
        if (request.expectedRevision() != current.revision()) {
            return finish(player, request, actor, request.operationId(), Action.UPDATE, request.waypointId(),
                rejected(request.operationId(), MutationError.REVISION_CONFLICT), now);
        }
        if (request.groupId() != null && store.findGroup(request.groupId()).isEmpty()) {
            return finish(player, request, actor, request.operationId(), Action.UPDATE, request.waypointId(),
                rejected(request.operationId(), MutationError.NOT_FOUND), now);
        }
        final long nextRevision;
        try {
            nextRevision = Math.addExact(store.snapshot().revision(), 1);
        } catch (final ArithmeticException e) {
            return finish(player, request, actor, request.operationId(), Action.UPDATE, request.waypointId(),
                rejected(request.operationId(), MutationError.PERSISTENCE_FAILED), now);
        }
        final SharedWaypoint updated = new SharedWaypoint(
            current.id(), current.publisherId(), current.publisherName(), current.name(),
            current.dimensionId(), current.x(), current.y(), current.z(), current.colorArgb(),
            current.type(), current.iconItemId(), current.markerLabel(),
            current.description(), current.imageId(), current.createdAtEpochMs(),
            nextRevision, request.groupId()
        );
        final SharedWaypointStore.PreparedMutation mutation;
        try {
            mutation = store.prepareUpdate(updated);
        } catch (final ArithmeticException e) {
            return finish(player, request, actor, request.operationId(), Action.UPDATE, request.waypointId(),
                rejected(request.operationId(), MutationError.PERSISTENCE_FAILED), now);
        }
        return persist(
            player, request, actor, request.operationId(), Action.UPDATE, request.waypointId(),
            mutation, now
        );
    }

    /**
     * Moves several waypoints into one group atomically: every entry is
     * validated (existence, per-waypoint revision, permission, target group)
     * before anything changes, and one durable write commits the whole batch
     * with consecutive per-member revisions. Entries already in the target
     * group are skipped; a batch with nothing left to do is rejected.
     */
    public synchronized MutationResult moveBatch(final Actor actor, final MoveBatchRequest request) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(request, "request");
        final PlayerState player = playerState(actor);
        final MutationResult replay = replayOrRejectReuse(
            player, actor, request.operationId(), Action.UPDATE, null, request
        );
        if (replay != null) {
            return replay;
        }
        final long now = clock.millis();
        if (!player.bucket.tryConsume(now)) {
            return finish(player, request, actor, request.operationId(), Action.UPDATE, null,
                rejected(request.operationId(), MutationError.RATE_LIMITED), now);
        }
        if (request.entries().isEmpty()
            || request.entries().size() > SharedWaypointProto.MAX_BATCH_MOVE_ENTRIES
            || request.entries().stream().anyMatch(Objects::isNull)) {
            return finish(player, request, actor, request.operationId(), Action.UPDATE, null,
                rejected(request.operationId(), MutationError.INVALID_REQUEST), now);
        }
        final Set<UUID> distinctIds = new HashSet<>();
        for (final MoveBatchRequest.Entry entry : request.entries()) {
            if (!distinctIds.add(entry.waypointId())) {
                return finish(player, request, actor, request.operationId(), Action.UPDATE, null,
                    rejected(request.operationId(), MutationError.INVALID_REQUEST), now);
            }
        }
        final long baseRevision = store.snapshot().revision();
        if (request.expectedRevision() != baseRevision) {
            return finish(player, request, actor, request.operationId(), Action.UPDATE, null,
                rejected(request.operationId(), MutationError.REVISION_CONFLICT), now);
        }
        final List<SharedWaypoint> updated = new ArrayList<>(request.entries().size());
        for (final MoveBatchRequest.Entry entry : request.entries()) {
            final Optional<SharedWaypoint> existing = store.find(entry.waypointId());
            if (existing.isEmpty()) {
                return finish(player, request, actor, request.operationId(), Action.UPDATE, null,
                    rejected(request.operationId(), MutationError.NOT_FOUND), now);
            }
            final SharedWaypoint current = existing.get();
            if (!canManage(actor, current)) {
                return finish(player, request, actor, request.operationId(), Action.UPDATE, null,
                    rejected(request.operationId(), MutationError.FORBIDDEN), now);
            }
            if (entry.expectedWaypointRevision() != current.revision()) {
                return finish(player, request, actor, request.operationId(), Action.UPDATE, null,
                    rejected(request.operationId(), MutationError.REVISION_CONFLICT), now);
            }
            if (entry.groupId() != null && store.findGroup(entry.groupId()).isEmpty()) {
                return finish(player, request, actor, request.operationId(), Action.UPDATE, null,
                    rejected(request.operationId(), MutationError.NOT_FOUND), now);
            }
            if (Objects.equals(entry.groupId(), current.groupId())) {
                continue;
            }
            final long nextRevision;
            try {
                nextRevision = Math.addExact(baseRevision, updated.size() + 1L);
            } catch (final ArithmeticException e) {
                return finish(player, request, actor, request.operationId(), Action.UPDATE, null,
                    rejected(request.operationId(), MutationError.PERSISTENCE_FAILED), now);
            }
            updated.add(new SharedWaypoint(
                current.id(), current.publisherId(), current.publisherName(), current.name(),
                current.dimensionId(), current.x(), current.y(), current.z(), current.colorArgb(),
                current.type(), current.iconItemId(), current.markerLabel(),
                current.description(), current.imageId(), current.createdAtEpochMs(),
                nextRevision, entry.groupId()
            ));
        }
        if (updated.isEmpty()) {
            return finish(player, request, actor, request.operationId(), Action.UPDATE, null,
                rejected(request.operationId(), MutationError.INVALID_REQUEST), now);
        }
        final SharedWaypointStore.PreparedMutation mutation;
        try {
            mutation = store.prepareMoveBatch(updated);
        } catch (final IllegalArgumentException | ArithmeticException e) {
            return finish(player, request, actor, request.operationId(), Action.UPDATE, null,
                rejected(request.operationId(), MutationError.INVALID_REQUEST), now);
        }
        return persist(player, request, actor, request.operationId(), Action.UPDATE, null, mutation, now);
    }

    synchronized int trackedPlayerCount() {
        return players.size();
    }

    private boolean canCreate(final Actor actor) {
        return actor.operator() || accessPolicy == AccessPolicy.OWNER_MANAGED;
    }

    private boolean canManage(final Actor actor, final SharedWaypoint waypoint) {
        return actor.operator()
            || accessPolicy == AccessPolicy.OWNER_MANAGED
                && waypoint.publisherId().equals(actor.playerId());
    }

    /** Operators manage every group; under owner management only the creating player may change it. */
    private boolean canManageGroup(final Actor actor, final SharedWaypointGroup group) {
        return actor.operator()
            || accessPolicy == AccessPolicy.OWNER_MANAGED
                && group.creatorId().equals(actor.playerId());
    }

    private MutationResult persist(
        final PlayerState player,
        final Object request,
        final Actor actor,
        final UUID operationId,
        final Action action,
        final UUID waypointId,
        final SharedWaypointStore.PreparedMutation mutation,
        final long now
    ) {
        try {
            persistence.save(mutation.snapshot());
        } catch (final IOException | RuntimeException e) {
            logger.error("[ConfluxMap] Shared waypoint persistence failed for operation {} by actor {}", operationId, actor.playerId(), e);
            return finish(player, request, actor, operationId, action, waypointId,
                rejected(operationId, MutationError.PERSISTENCE_FAILED), now);
        }
        store.commit(mutation);
        final MutationResult result = new MutationResult(
            operationId, MutationStatus.APPLIED, MutationError.NONE, mutation.snapshot(), mutation.deltas(), false
        );
        return finish(player, request, actor, operationId, action, waypointId, result, now);
    }

    private MutationResult rejected(final UUID operationId, final MutationError error) {
        final SharedWaypointStore.Snapshot snapshot = store.snapshot();
        return new MutationResult(
            operationId, MutationStatus.REJECTED, error, snapshot,
            List.of(SharedWaypointStore.Delta.noop(snapshot.revision())), false
        );
    }

    private MutationResult finish(
        final PlayerState player,
        final Object request,
        final Actor actor,
        final UUID operationId,
        final Action action,
        final UUID waypointId,
        final MutationResult result,
        final long now
    ) {
        // Only applied results are idempotency-cached. Rejections are re-evaluated on retry:
        // caching them would replay transient failures (RATE_LIMITED, PERSISTENCE_FAILED)
        // forever and retain a full snapshot copy per rejection.
        if (result.applied()) {
            player.results.put(operationId, new CachedMutation(request, result));
        }
        audit(actor, operationId, action, waypointId, result, now);
        return result;
    }

    private MutationResult replayOrRejectReuse(
        final PlayerState player,
        final Actor actor,
        final UUID operationId,
        final Action action,
        final UUID waypointId,
        final Object request
    ) {
        final CachedMutation cached = player.results.get(operationId);
        if (cached == null) {
            return null;
        }
        if (cached.request.equals(request)) {
            return cached.result.asReplay();
        }
        final MutationResult collision = rejected(operationId, MutationError.INVALID_REQUEST);
        if (!cached.collisionAudited) {
            cached.collisionAudited = true;
            audit(actor, operationId, action, waypointId, collision, clock.millis());
        }
        return collision;
    }

    private void audit(
        final Actor actor,
        final UUID operationId,
        final Action action,
        final UUID waypointId,
        final MutationResult result,
        final long now
    ) {
        final AuditEvent event = new AuditEvent(
            operationId, actor.playerId(), action, result.status(), result.error(), waypointId,
            result.snapshot().revision(), now
        );
        try {
            auditSink.record(event);
        } catch (final RuntimeException e) {
            logger.error("[ConfluxMap] Shared waypoint audit sink failed for operation {} by actor {}", operationId, actor.playerId(), e);
        }
    }

    private PlayerState playerState(final Actor actor) {
        return players.computeIfAbsent(
            actor.playerId(), ignored -> new PlayerState(limits.mutationsPerMinute(), clock.millis())
        );
    }

    private UUID uniqueId() {
        for (int attempt = 0; attempt < 16; attempt++) {
            final UUID candidate = idSupplier.get();
            if (candidate != null && store.find(candidate).isEmpty()) {
                return candidate;
            }
        }
        return null;
    }

    private UUID uniqueGroupId() {
        for (int attempt = 0; attempt < 16; attempt++) {
            final UUID candidate = idSupplier.get();
            if (candidate != null && store.findGroup(candidate).isEmpty()) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean validPublisherName(final String name) {
        return SharedWaypointValidator.validDisplayText(name, 64);
    }
}
