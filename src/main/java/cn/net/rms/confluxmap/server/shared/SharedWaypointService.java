package cn.net.rms.confluxmap.server.shared;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import java.io.IOException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.apache.logging.log4j.Logger;

/**
 * Server-authoritative shared-waypoint mutation service.
 *
 * <p>All request data is treated as untrusted. The service owns IDs, publisher metadata,
 * timestamps, revisions, permissions, quotas, rate limiting, idempotency and durable commit.
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
        PERSISTENCE_FAILED,
        ID_GENERATION_FAILED
    }

    public enum Action {
        CREATE,
        DELETE,
        LOCK,
        UNLOCK
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
        Waypoint.Type type
    ) {
        public CreateRequest {
            Objects.requireNonNull(operationId, "operationId");
        }
    }

    public record DeleteRequest(UUID operationId, long expectedRevision, UUID waypointId) {
        public DeleteRequest {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(waypointId, "waypointId");
        }
    }

    public record LockRequest(UUID operationId, long expectedRevision, UUID waypointId, boolean locked) {
        public LockRequest {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(waypointId, "waypointId");
        }
    }

    public record MutationResult(
        UUID operationId,
        MutationStatus status,
        MutationError error,
        SharedWaypointStore.Snapshot snapshot,
        SharedWaypointStore.Delta delta,
        boolean replayed
    ) {
        public MutationResult {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(error, "error");
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(delta, "delta");
        }

        public boolean applied() {
            return status == MutationStatus.APPLIED;
        }

        private MutationResult asReplay() {
            return replayed
                ? this
                : new MutationResult(operationId, status, error, snapshot, delta, true);
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
        final AuditSink auditSink,
        final Logger logger
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.idSupplier = Objects.requireNonNull(idSupplier, "idSupplier");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink");
        this.logger = Objects.requireNonNull(logger, "logger");
        for (final SharedWaypoint waypoint : store.snapshot().waypoints()) {
            if (validator.validate(
                waypoint.name(), waypoint.dimensionId(), waypoint.x(), waypoint.y(), waypoint.z(),
                waypoint.colorArgb(), waypoint.type()
            ).isEmpty() || !validPublisherName(waypoint.publisherName())) {
                throw new IllegalArgumentException("loaded shared waypoint is invalid for the active world");
            }
        }
    }

    public synchronized SharedWaypointStore.Snapshot snapshot() {
        return store.snapshot();
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
            request.colorArgb(), request.type()
        );
        final String publisherName = actor.playerName().strip();
        if (validated.isEmpty() || !validPublisherName(publisherName)) {
            return finish(player, request, actor, request.operationId(), Action.CREATE, null,
                rejected(request.operationId(), MutationError.INVALID_REQUEST), now);
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
        final SharedWaypointValidator.ValidatedCreate value = validated.get();
        final long nextRevision;
        try {
            nextRevision = Math.addExact(store.snapshot().revision(), 1);
        } catch (final ArithmeticException e) {
            return finish(player, request, actor, request.operationId(), Action.CREATE, null,
                rejected(request.operationId(), MutationError.PERSISTENCE_FAILED), now);
        }
        final SharedWaypoint waypoint = new SharedWaypoint(
            id, actor.playerId(), publisherName, value.name(), value.dimensionId(), value.x(), value.y(), value.z(),
            value.colorArgb(), value.type(), false, now, nextRevision
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
        if (existing.get().locked() && !actor.operator()) {
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

    public synchronized MutationResult setLocked(final Actor actor, final LockRequest request) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(request, "request");
        final PlayerState player = playerState(actor);
        final Action action = request.locked() ? Action.LOCK : Action.UNLOCK;
        final MutationResult replay = replayOrRejectReuse(
            player, actor, request.operationId(), action, request.waypointId(), request
        );
        if (replay != null) {
            return replay;
        }
        final long now = clock.millis();
        if (!player.bucket.tryConsume(now)) {
            return finish(player, request, actor, request.operationId(), action, request.waypointId(),
                rejected(request.operationId(), MutationError.RATE_LIMITED), now);
        }
        if (!actor.operator()) {
            return finish(player, request, actor, request.operationId(), action, request.waypointId(),
                rejected(request.operationId(), MutationError.FORBIDDEN), now);
        }
        final Optional<SharedWaypoint> existing = store.find(request.waypointId());
        if (existing.isEmpty()) {
            return finish(player, request, actor, request.operationId(), action, request.waypointId(),
                rejected(request.operationId(), MutationError.NOT_FOUND), now);
        }
        if (request.expectedRevision() != existing.get().revision()) {
            return finish(player, request, actor, request.operationId(), action, request.waypointId(),
                rejected(request.operationId(), MutationError.REVISION_CONFLICT), now);
        }
        if (existing.get().locked() == request.locked()) {
            final SharedWaypointStore.Snapshot snapshot = store.snapshot();
            final MutationResult result = new MutationResult(
                request.operationId(), MutationStatus.APPLIED, MutationError.NONE, snapshot,
                SharedWaypointStore.Delta.noop(snapshot.revision()), false
            );
            return finish(player, request, actor, request.operationId(), action, request.waypointId(), result, now);
        }
        final SharedWaypointStore.PreparedMutation mutation;
        try {
            mutation = store.prepareLocked(request.waypointId(), request.locked());
        } catch (final ArithmeticException e) {
            return finish(player, request, actor, request.operationId(), action, request.waypointId(),
                rejected(request.operationId(), MutationError.PERSISTENCE_FAILED), now);
        }
        return persist(player, request, actor, request.operationId(), action, request.waypointId(),
            mutation, now);
    }

    /**
     * Disconnects deliberately retain idempotency results so a reconnect retry cannot duplicate a
     * successful mutation. The access-ordered player map bounds retained state globally.
     */
    public synchronized void forgetPlayer(final UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
    }

    synchronized int trackedPlayerCount() {
        return players.size();
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
            logger.error("Shared waypoint persistence failed for operation {} by actor {}", operationId, actor.playerId(), e);
            return finish(player, request, actor, operationId, action, waypointId,
                rejected(operationId, MutationError.PERSISTENCE_FAILED), now);
        }
        store.commit(mutation);
        final MutationResult result = new MutationResult(
            operationId, MutationStatus.APPLIED, MutationError.NONE, mutation.snapshot(), mutation.delta(), false
        );
        return finish(player, request, actor, operationId, action, waypointId, result, now);
    }

    private MutationResult rejected(final UUID operationId, final MutationError error) {
        final SharedWaypointStore.Snapshot snapshot = store.snapshot();
        return new MutationResult(
            operationId, MutationStatus.REJECTED, error, snapshot,
            SharedWaypointStore.Delta.noop(snapshot.revision()), false
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
        player.results.put(operationId, new CachedMutation(request, result));
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
            logger.error("Shared waypoint audit sink failed for operation {} by actor {}", operationId, actor.playerId(), e);
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

    private static boolean validPublisherName(final String name) {
        return SharedWaypointValidator.validDisplayText(name, 64);
    }
}
