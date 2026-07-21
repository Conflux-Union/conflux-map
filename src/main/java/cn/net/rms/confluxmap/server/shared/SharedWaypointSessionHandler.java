package cn.net.rms.confluxmap.server.shared;

import cn.net.rms.confluxmap.core.net.shared.CreateC2S;
import cn.net.rms.confluxmap.core.net.shared.DeleteC2S;
import cn.net.rms.confluxmap.core.net.shared.HelloC2S;
import cn.net.rms.confluxmap.core.net.shared.LockC2S;
import cn.net.rms.confluxmap.core.net.shared.RemoveS2C;
import cn.net.rms.confluxmap.core.net.shared.ResultS2C;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointMessage;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointProto;
import cn.net.rms.confluxmap.core.net.shared.SnapshotS2C;
import cn.net.rms.confluxmap.core.net.shared.StatusS2C;
import cn.net.rms.confluxmap.core.net.shared.SubscribeC2S;
import cn.net.rms.confluxmap.core.net.shared.UpsertS2C;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Minecraft-free shared-waypoint protocol state machine.
 *
 * <p>The Fabric adapter invokes this class on the server thread. A connection is eligible for
 * snapshots or deltas only after a compatible {@link HelloC2S}; subscriptions and malformed
 * strikes are connection state, while mutation idempotency remains owned by the longer-lived
 * {@link SharedWaypointService}.
 */
public final class SharedWaypointSessionHandler {
    public static final int MAX_TRACKED_SESSIONS = 2_048;
    public static final int MAX_MALFORMED_STRIKES = 3;
    public static final int CONTROL_REQUEST_BURST = 8;
    public static final int CONTROL_REQUESTS_PER_MINUTE = 60;

    /** Current player properties. Operator status is deliberately supplied for every request. */
    public record Peer(UUID id, String name, boolean operator) {
        public Peer {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
        }
    }

    /** Current world capability. {@code enabled} is the effective flag, not only configuration. */
    public record Environment(
        boolean enabled,
        String worldId,
        int maxWorld,
        int maxPlayer,
        SharedWaypointService service
    ) {
        public Environment {
            Objects.requireNonNull(worldId, "worldId");
            if (maxWorld < 1 || maxWorld > SharedWaypointProto.MAX_SNAPSHOT_WAYPOINTS
                || maxPlayer < 1 || maxPlayer > maxWorld) {
                throw new IllegalArgumentException("invalid shared-waypoint quotas");
            }
            if (enabled && service == null) {
                throw new IllegalArgumentException("enabled shared waypoints require a service");
            }
        }

        long revision() {
            return service == null ? 0L : service.snapshot().revision();
        }
    }

    /** Direct replies plus at most one applied state delta for subscribed peers. */
    public record Dispatch(List<SharedWaypointMessage> direct, SharedWaypointMessage broadcast) {
        public Dispatch {
            direct = List.copyOf(Objects.requireNonNull(direct, "direct"));
        }

        static Dispatch direct(final SharedWaypointMessage message) {
            return new Dispatch(List.of(message), null);
        }

        static Dispatch none() {
            return new Dispatch(List.of(), null);
        }
    }

    public record MalformedOutcome(int strikes, boolean muted, boolean newlyMuted) {
    }

    private static final class Session {
        private final SessionTokenBucket controlRequests;
        private boolean compatible;
        private boolean subscribed;
        private boolean operator;
        private int malformedStrikes;
        private boolean muted;

        private Session(final long nowEpochMs) {
            controlRequests = new SessionTokenBucket(
                CONTROL_REQUEST_BURST,
                CONTROL_REQUESTS_PER_MINUTE,
                nowEpochMs
            );
        }
    }

    private static final class SessionTokenBucket {
        private final double capacity;
        private final double refillPerMillisecond;
        private double tokens;
        private long lastEpochMs;

        private SessionTokenBucket(final int capacity, final int refillPerMinute, final long nowEpochMs) {
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

    private final Map<UUID, Session> sessions = new LinkedHashMap<>();
    private final Clock clock;

    public SharedWaypointSessionHandler() {
        this(Clock.systemUTC());
    }

    SharedWaypointSessionHandler(final Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized Dispatch handle(
        final Peer peer,
        final SharedWaypointMessage message,
        final Environment environment
    ) {
        Objects.requireNonNull(peer, "peer");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(environment, "environment");

        if (message instanceof final HelloC2S hello) {
            final Session session = session(peer.id());
            if (session == null || session.muted || !session.controlRequests.tryConsume(clock.millis())) {
                return Dispatch.none();
            }
            session.subscribed = false;
            session.compatible = hello.major() == SharedWaypointProto.PROTO_MAJOR && hello.minor() >= 0;
            session.operator = peer.operator();
            return Dispatch.direct(status(peer, environment, session.compatible));
        }

        final Session session = sessions.get(peer.id());
        if (session == null || !session.compatible || session.muted) {
            return Dispatch.none();
        }
        if (message instanceof SubscribeC2S) {
            // Snapshot construction is bounded independently from mutation throttling.
            if (!session.controlRequests.tryConsume(clock.millis())) {
                return Dispatch.none();
            }
            session.operator = peer.operator();
            if (!environment.enabled()) {
                session.subscribed = false;
                return Dispatch.direct(status(peer, environment, true));
            }
            session.subscribed = true;
            final SharedWaypointStore.Snapshot snapshot = environment.service().snapshot();
            return Dispatch.direct(new SnapshotS2C(
                snapshot.revision(), peer.operator(), snapshot.waypoints()
            ));
        }
        if (message instanceof final CreateC2S create) {
            if (!environment.enabled()) {
                return disabled(session, create.operationId(), peer, environment);
            }
            return mutation(environment.service().create(
                actor(peer),
                new SharedWaypointService.CreateRequest(
                    create.operationId(), create.expectedRevision(), create.name(), create.dimensionId(),
                    create.x(), create.y(), create.z(), create.color(), create.type()
                )
            ));
        }
        if (message instanceof final DeleteC2S delete) {
            if (!environment.enabled()) {
                return disabled(session, delete.operationId(), peer, environment);
            }
            return mutation(environment.service().delete(
                actor(peer),
                new SharedWaypointService.DeleteRequest(
                    delete.operationId(), delete.expectedRevision(), delete.id()
                )
            ));
        }
        if (message instanceof final LockC2S lock) {
            if (!environment.enabled()) {
                return disabled(session, lock.operationId(), peer, environment);
            }
            return mutation(environment.service().setLocked(
                actor(peer),
                new SharedWaypointService.LockRequest(
                    lock.operationId(), lock.expectedRevision(), lock.id(), lock.locked()
                )
            ));
        }
        return Dispatch.none();
    }

    /** Saturating strike accounting; no untrusted packet can grow state beyond the session cap. */
    public synchronized MalformedOutcome recordMalformed(final UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        final Session session = session(playerId);
        if (session == null) {
            return new MalformedOutcome(MAX_MALFORMED_STRIKES, true, false);
        }
        final boolean wasMuted = session.muted;
        session.malformedStrikes = Math.min(MAX_MALFORMED_STRIKES, session.malformedStrikes + 1);
        session.muted = session.malformedStrikes >= MAX_MALFORMED_STRIKES;
        return new MalformedOutcome(
            session.malformedStrikes,
            session.muted,
            !wasMuted && session.muted
        );
    }

    public synchronized boolean isCompatible(final UUID playerId) {
        final Session session = sessions.get(playerId);
        return session != null && session.compatible && !session.muted;
    }

    public synchronized boolean isMuted(final UUID playerId) {
        final Session session = sessions.get(playerId);
        return session != null && session.muted;
    }

    public synchronized boolean isSubscribed(final UUID playerId) {
        final Session session = sessions.get(playerId);
        return session != null && session.compatible && session.subscribed && !session.muted;
    }

    /** Records and reports a live permission change for an established compatible session. */
    public synchronized boolean updateOperator(final Peer peer) {
        Objects.requireNonNull(peer, "peer");
        final Session session = sessions.get(peer.id());
        if (session == null || !session.compatible || session.muted
            || session.operator == peer.operator()) {
            return false;
        }
        session.operator = peer.operator();
        return true;
    }

    /** A disable transition invalidates every subscription; re-enable requires a fresh subscribe. */
    public synchronized void clearSubscriptions() {
        for (final Session session : sessions.values()) {
            session.subscribed = false;
        }
    }

    public synchronized void disconnect(final UUID playerId) {
        sessions.remove(Objects.requireNonNull(playerId, "playerId"));
    }

    public synchronized void clear() {
        sessions.clear();
    }

    synchronized int trackedSessionCount() {
        return sessions.size();
    }

    public synchronized StatusS2C status(final Peer peer, final Environment environment) {
        final Session session = sessions.get(peer.id());
        if (session != null) {
            session.operator = peer.operator();
        }
        return status(peer, environment, true);
    }

    private Session session(final UUID playerId) {
        final Session existing = sessions.get(playerId);
        if (existing != null) {
            return existing;
        }
        if (sessions.size() >= MAX_TRACKED_SESSIONS) {
            return null;
        }
        final Session created = new Session(clock.millis());
        sessions.put(playerId, created);
        return created;
    }

    private static StatusS2C status(
        final Peer peer,
        final Environment environment,
        final boolean supported
    ) {
        return new StatusS2C(
            SharedWaypointProto.PROTO_MAJOR,
            SharedWaypointProto.PROTO_MINOR,
            supported,
            supported && environment.enabled(),
            peer.operator(),
            environment.worldId(),
            environment.revision(),
            environment.maxWorld(),
            environment.maxPlayer()
        );
    }

    private Dispatch disabled(
        final Session session,
        final UUID operationId,
        final Peer peer,
        final Environment environment
    ) {
        if (!session.controlRequests.tryConsume(clock.millis())) {
            return Dispatch.direct(new ResultS2C(
                operationId,
                SharedWaypointProto.RESULT_STATUS_REJECTED,
                SharedWaypointProto.RESULT_ERROR_RATE_LIMITED
            ));
        }
        session.operator = peer.operator();
        return new Dispatch(
            List.of(
                new ResultS2C(
                    operationId,
                    SharedWaypointProto.RESULT_STATUS_REJECTED,
                    SharedWaypointProto.RESULT_ERROR_DISABLED
                ),
                status(peer, environment, true)
            ),
            null
        );
    }

    private static SharedWaypointService.Actor actor(final Peer peer) {
        return new SharedWaypointService.Actor(peer.id(), peer.name(), peer.operator());
    }

    private static Dispatch mutation(final SharedWaypointService.MutationResult mutation) {
        final ResultS2C result = new ResultS2C(
            mutation.operationId(),
            statusCode(mutation.status()),
            errorCode(mutation.error())
        );
        final SharedWaypointMessage delta = switch (mutation.delta().kind()) {
            case UPSERT -> new UpsertS2C(mutation.delta().revision(), mutation.delta().waypoint());
            case REMOVE -> new RemoveS2C(mutation.delta().revision(), mutation.delta().removedId());
            case NOOP -> null;
        };
        return new Dispatch(
            List.of(result),
            mutation.applied() && !mutation.replayed() ? delta : null
        );
    }

    static int statusCode(final SharedWaypointService.MutationStatus status) {
        return switch (status) {
            case APPLIED -> SharedWaypointProto.RESULT_STATUS_APPLIED;
            case REJECTED -> SharedWaypointProto.RESULT_STATUS_REJECTED;
        };
    }

    static int errorCode(final SharedWaypointService.MutationError error) {
        return switch (error) {
            case NONE -> SharedWaypointProto.RESULT_ERROR_NONE;
            case INVALID_REQUEST -> SharedWaypointProto.RESULT_ERROR_INVALID_REQUEST;
            case REVISION_CONFLICT -> SharedWaypointProto.RESULT_ERROR_REVISION_CONFLICT;
            case NOT_FOUND -> SharedWaypointProto.RESULT_ERROR_NOT_FOUND;
            case FORBIDDEN -> SharedWaypointProto.RESULT_ERROR_FORBIDDEN;
            case WORLD_QUOTA_EXCEEDED -> SharedWaypointProto.RESULT_ERROR_WORLD_QUOTA_EXCEEDED;
            case PLAYER_QUOTA_EXCEEDED -> SharedWaypointProto.RESULT_ERROR_PLAYER_QUOTA_EXCEEDED;
            case RATE_LIMITED -> SharedWaypointProto.RESULT_ERROR_RATE_LIMITED;
            case DUPLICATE_LOCATION -> SharedWaypointProto.RESULT_ERROR_DUPLICATE_LOCATION;
            case PERSISTENCE_FAILED -> SharedWaypointProto.RESULT_ERROR_PERSISTENCE_FAILED;
            case ID_GENERATION_FAILED -> SharedWaypointProto.RESULT_ERROR_ID_GENERATION_FAILED;
        };
    }
}
