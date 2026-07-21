package cn.net.rms.confluxmap.server.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;

class SharedWaypointServiceTest {
    private static final Logger LOGGER = LogManager.getLogger("SharedWaypointServiceTest");
    private static final SharedWaypointService.Actor PLAYER = actor(1, "PlayerOne", false);
    private static final SharedWaypointService.Actor OTHER = actor(2, "PlayerTwo", false);
    private static final SharedWaypointService.Actor OPERATOR = actor(3, "Operator", true);

    @Test
    void createInjectsAuthorityFieldsPersistsThenCommitsAndReplaysIdempotently() {
        final Fixture fixture = fixture(new SharedWaypointService.Limits(20, 10, 30));
        final UUID operationId = uuid(100);
        final SharedWaypointService.CreateRequest request = createRequest(operationId, 0, "  Home  ");

        final SharedWaypointService.MutationResult first = fixture.service.create(PLAYER, request);
        final SharedWaypointService.MutationResult replay = fixture.service.create(PLAYER, request);

        assertTrue(first.applied());
        assertEquals(SharedWaypointStore.DeltaKind.UPSERT, first.delta().kind());
        assertEquals(1L, first.snapshot().revision());
        final SharedWaypoint waypoint = first.delta().waypoint();
        assertNotNull(waypoint);
        assertEquals(uuid(1_000), waypoint.id());
        assertEquals(PLAYER.playerId(), waypoint.publisherId());
        assertEquals(PLAYER.playerName(), waypoint.publisherName());
        assertEquals("Home", waypoint.name());
        assertEquals(10_000L, waypoint.createdAtEpochMs());
        assertEquals(1L, waypoint.revision());
        assertEquals(first.operationId(), replay.operationId());
        assertEquals(first.status(), replay.status());
        assertEquals(first.error(), replay.error());
        assertEquals(first.snapshot(), replay.snapshot());
        assertEquals(first.delta(), replay.delta());
        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(1, fixture.persistence.saves);
        assertEquals(1, fixture.audit.size());
        assertEquals(first.snapshot(), fixture.persistence.saved);
    }

    @Test
    void operationIdIsBoundToTheOriginalActionAndPayload() {
        final Fixture fixture = fixture(new SharedWaypointService.Limits(20, 10, 30));
        final UUID operationId = uuid(105);
        final SharedWaypointService.CreateRequest original = createRequest(operationId, 0, "Home");
        final SharedWaypointService.MutationResult first = fixture.service.create(PLAYER, original);

        final SharedWaypointService.MutationResult changedPayload = fixture.service.create(
            PLAYER, createRequest(operationId, 1, "Different")
        );
        final SharedWaypointService.MutationResult changedAction = fixture.service.delete(
            PLAYER,
            new SharedWaypointService.DeleteRequest(
                operationId,
                first.delta().waypoint().revision(),
                first.delta().waypoint().id()
            )
        );
        final SharedWaypointService.MutationResult originalReplay = fixture.service.create(PLAYER, original);

        assertEquals(SharedWaypointService.MutationError.INVALID_REQUEST, changedPayload.error());
        assertEquals(SharedWaypointService.MutationError.INVALID_REQUEST, changedAction.error());
        assertFalse(changedPayload.replayed());
        assertFalse(changedAction.replayed());
        assertTrue(originalReplay.applied());
        assertTrue(originalReplay.replayed());
        assertEquals(1L, fixture.service.snapshot().revision());
        assertEquals(1, fixture.persistence.saves);
        assertEquals(2, fixture.audit.size());
    }

    @Test
    void failedPersistenceDoesNotExposeMutation() {
        final Fixture fixture = fixture(new SharedWaypointService.Limits(20, 10, 30));
        fixture.persistence.fail = true;

        final SharedWaypointService.MutationResult result = fixture.service.create(
            PLAYER, createRequest(uuid(101), 0, "Home")
        );

        assertFalse(result.applied());
        assertEquals(SharedWaypointService.MutationError.PERSISTENCE_FAILED, result.error());
        assertEquals(SharedWaypointStore.DeltaKind.NOOP, result.delta().kind());
        assertEquals(0L, fixture.service.snapshot().revision());
        assertTrue(fixture.service.snapshot().waypoints().isEmpty());
    }

    @Test
    void exhaustedRevisionRejectsDeleteAndLockWithoutPersisting() {
        final SharedWaypoint waypoint = new SharedWaypoint(
            uuid(1_500), PLAYER.playerId(), PLAYER.playerName(), "Limit", DimensionId.OVERWORLD,
            12.5d, 64d, -8.25d, 0xFF33AA66, Waypoint.Type.NORMAL, false, 1L, Long.MAX_VALUE
        );
        final Fixture fixture = fixture(
            new SharedWaypointStore.Snapshot(Long.MAX_VALUE, List.of(waypoint)),
            new SharedWaypointService.Limits(20, 10, 30)
        );

        final SharedWaypointService.MutationResult deleted = fixture.service.delete(
            PLAYER, new SharedWaypointService.DeleteRequest(uuid(1_501), Long.MAX_VALUE, waypoint.id())
        );
        final SharedWaypointService.MutationResult locked = fixture.service.setLocked(
            OPERATOR, new SharedWaypointService.LockRequest(uuid(1_502), Long.MAX_VALUE, waypoint.id(), true)
        );

        assertEquals(SharedWaypointService.MutationError.PERSISTENCE_FAILED, deleted.error());
        assertEquals(SharedWaypointService.MutationError.PERSISTENCE_FAILED, locked.error());
        assertEquals(Long.MAX_VALUE, fixture.service.snapshot().revision());
        assertEquals(List.of(waypoint), fixture.service.snapshot().waypoints());
        assertEquals(0, fixture.persistence.saves);
    }

    @Test
    void unlockedMayBeDeletedByAnyoneButLockedRequiresOperator() {
        final Fixture fixture = fixture(new SharedWaypointService.Limits(20, 10, 30));
        final SharedWaypoint created = fixture.service.create(
            PLAYER, createRequest(uuid(110), 0, "Public")
        ).delta().waypoint();

        final SharedWaypointService.MutationResult nonOperatorLock = fixture.service.setLocked(
            PLAYER, new SharedWaypointService.LockRequest(uuid(111), 1, created.id(), true)
        );
        assertEquals(SharedWaypointService.MutationError.FORBIDDEN, nonOperatorLock.error());

        final SharedWaypointService.MutationResult locked = fixture.service.setLocked(
            OPERATOR, new SharedWaypointService.LockRequest(uuid(112), 1, created.id(), true)
        );
        assertTrue(locked.applied());
        assertTrue(locked.delta().waypoint().locked());
        assertEquals(2L, locked.snapshot().revision());

        final SharedWaypointService.MutationResult forbiddenDelete = fixture.service.delete(
            OTHER, new SharedWaypointService.DeleteRequest(uuid(113), 2, created.id())
        );
        assertEquals(SharedWaypointService.MutationError.FORBIDDEN, forbiddenDelete.error());

        final SharedWaypointService.MutationResult deleted = fixture.service.delete(
            OPERATOR, new SharedWaypointService.DeleteRequest(uuid(114), 2, created.id())
        );
        assertTrue(deleted.applied());
        assertEquals(SharedWaypointStore.DeltaKind.REMOVE, deleted.delta().kind());
        assertEquals(created.id(), deleted.delta().removedId());
        assertTrue(deleted.snapshot().waypoints().isEmpty());
    }

    @Test
    void anyoneCanDeleteAnUnlockedWaypoint() {
        final Fixture fixture = fixture(new SharedWaypointService.Limits(20, 10, 30));
        final SharedWaypoint created = fixture.service.create(
            PLAYER, createRequest(uuid(120), 0, "Public")
        ).delta().waypoint();

        final SharedWaypointService.MutationResult deleted = fixture.service.delete(
            OTHER, new SharedWaypointService.DeleteRequest(uuid(121), 1, created.id())
        );

        assertTrue(deleted.applied());
        assertEquals(2L, deleted.snapshot().revision());
    }

    @Test
    void targetRevisionRemainsValidWhenAnUnrelatedWaypointAdvancesGlobalRevision() {
        final Fixture fixture = fixture(new SharedWaypointService.Limits(20, 10, 30));
        final SharedWaypoint first = fixture.service.create(
            PLAYER, createRequest(uuid(122), 0, "First")
        ).delta().waypoint();
        assertTrue(fixture.service.create(
            OTHER, createRequest(uuid(123), 1, "Unrelated")
        ).applied());

        final SharedWaypointService.MutationResult deleted = fixture.service.delete(
            OTHER, new SharedWaypointService.DeleteRequest(uuid(124), first.revision(), first.id())
        );

        assertTrue(deleted.applied());
        assertEquals(3L, deleted.snapshot().revision());
        assertEquals(first.id(), deleted.delta().removedId());
    }

    @Test
    void conflictsAndBothQuotasAreRejected() {
        final Fixture fixture = fixture(new SharedWaypointService.Limits(2, 1, 30));
        assertTrue(fixture.service.create(PLAYER, createRequest(uuid(130), 0, "One")).applied());

        final SharedWaypointService.MutationResult conflict = fixture.service.create(
            OTHER, createRequest(uuid(131), 0, "Stale")
        );
        assertEquals(SharedWaypointService.MutationError.REVISION_CONFLICT, conflict.error());

        final SharedWaypointService.MutationResult playerQuota = fixture.service.create(
            PLAYER, createRequest(uuid(132), 1, "Two")
        );
        assertEquals(SharedWaypointService.MutationError.PLAYER_QUOTA_EXCEEDED, playerQuota.error());

        assertTrue(fixture.service.create(OTHER, createRequest(uuid(133), 1, "Other")).applied());
        final SharedWaypointService.MutationResult worldQuota = fixture.service.create(
            OPERATOR, createRequest(uuid(134), 2, "Full")
        );
        assertEquals(SharedWaypointService.MutationError.WORLD_QUOTA_EXCEEDED, worldQuota.error());
    }

    @Test
    void validatesNameDimensionCoordinatesHeightAndOpaqueColor() {
        final Fixture fixture = fixture(new SharedWaypointService.Limits(20, 10, 30));
        final List<SharedWaypointService.CreateRequest> invalid = List.of(
            createRequest(uuid(140), 0, "   "),
            createRequest(uuid(146), 0, "\u00a7kHidden"),
            createRequest(uuid(147), 0, "safe\u202Ename"),
            new SharedWaypointService.CreateRequest(uuid(141), 0, "Name", DimensionId.END, 0, 64, 0, 0xFFFFFFFF, Waypoint.Type.NORMAL),
            new SharedWaypointService.CreateRequest(uuid(142), 0, "Name", DimensionId.OVERWORLD, Double.NaN, 64, 0, 0xFFFFFFFF, Waypoint.Type.NORMAL),
            new SharedWaypointService.CreateRequest(uuid(143), 0, "Name", DimensionId.OVERWORLD, 30_000_000, 64, 0, 0xFFFFFFFF, Waypoint.Type.NORMAL),
            new SharedWaypointService.CreateRequest(uuid(144), 0, "Name", DimensionId.OVERWORLD, 0, 256, 0, 0xFFFFFFFF, Waypoint.Type.NORMAL),
            new SharedWaypointService.CreateRequest(uuid(145), 0, "Name", DimensionId.OVERWORLD, 0, 64, 0, 0x0033AA66, Waypoint.Type.NORMAL)
        );

        for (final SharedWaypointService.CreateRequest request : invalid) {
            final SharedWaypointService.MutationResult result = fixture.service.create(PLAYER, request);
            assertEquals(SharedWaypointService.MutationError.INVALID_REQUEST, result.error());
            assertEquals(0L, result.snapshot().revision());
        }
    }

    @Test
    void rateLimitAllowsBurstAndRefillsAtConfiguredRate() {
        final Fixture fixture = fixture(new SharedWaypointService.Limits(20, 10, 30));
        for (int i = 0; i < SharedWaypointService.MUTATION_BURST; i++) {
            final SharedWaypointService.MutationResult result = fixture.service.delete(
                PLAYER, new SharedWaypointService.DeleteRequest(uuid(200 + i), 0, uuid(900 + i))
            );
            assertEquals(SharedWaypointService.MutationError.NOT_FOUND, result.error());
        }

        final SharedWaypointService.MutationResult limited = fixture.service.delete(
            PLAYER, new SharedWaypointService.DeleteRequest(uuid(220), 0, uuid(920))
        );
        assertEquals(SharedWaypointService.MutationError.RATE_LIMITED, limited.error());

        fixture.clock.advanceMillis(2_000);
        final SharedWaypointService.MutationResult refilled = fixture.service.delete(
            PLAYER, new SharedWaypointService.DeleteRequest(uuid(221), 0, uuid(921))
        );
        assertEquals(SharedWaypointService.MutationError.NOT_FOUND, refilled.error());
    }

    @Test
    void operatorSettingSameLockStateReturnsSuccessfulNoopWithoutSaving() {
        final Fixture fixture = fixture(new SharedWaypointService.Limits(20, 10, 30));
        final SharedWaypoint waypoint = fixture.service.create(
            PLAYER, createRequest(uuid(230), 0, "Home")
        ).delta().waypoint();

        final SharedWaypointService.MutationResult result = fixture.service.setLocked(
            OPERATOR, new SharedWaypointService.LockRequest(uuid(231), 1, waypoint.id(), false)
        );

        assertTrue(result.applied());
        assertEquals(SharedWaypointStore.DeltaKind.NOOP, result.delta().kind());
        assertEquals(1L, result.snapshot().revision());
        assertEquals(1, fixture.persistence.saves);
    }

    @Test
    void disconnectRetainsReplayResultAndTrackedPlayersAreGloballyBounded() {
        final Fixture fixture = fixture(new SharedWaypointService.Limits(20, 10, 30));
        final UUID operationId = uuid(235);
        final SharedWaypointService.CreateRequest request = createRequest(operationId, 0, "Home");
        final SharedWaypointService.MutationResult first = fixture.service.create(
            PLAYER, request
        );
        fixture.service.forgetPlayer(PLAYER.playerId());

        final SharedWaypointService.MutationResult replay = fixture.service.create(
            PLAYER, request
        );
        assertEquals(first.snapshot(), replay.snapshot());
        assertEquals(first.delta(), replay.delta());
        assertTrue(replay.replayed());
        assertEquals(1, fixture.persistence.saves);

        for (int i = 0; i <= SharedWaypointService.MAX_TRACKED_PLAYERS; i++) {
            final SharedWaypointService.Actor actor = actor(10_000 + i, "Player" + i, false);
            fixture.service.delete(
                actor,
                new SharedWaypointService.DeleteRequest(uuid(20_000 + i), 0, uuid(30_000 + i))
            );
        }
        assertEquals(SharedWaypointService.MAX_TRACKED_PLAYERS, fixture.service.trackedPlayerCount());
    }

    @Test
    void auditEventContainsNoNamesOrCoordinates() {
        final Fixture fixture = fixture(new SharedWaypointService.Limits(20, 10, 30));
        fixture.service.create(PLAYER, createRequest(uuid(240), 0, "Sensitive location"));

        final SharedWaypointService.AuditEvent event = fixture.audit.get(0);
        assertEquals(uuid(240), event.operationId());
        assertEquals(PLAYER.playerId(), event.actorId());
        assertEquals(SharedWaypointService.Action.CREATE, event.action());
        assertEquals(1L, event.revision());
        assertEquals(10_000L, event.timestampEpochMs());
    }

    private static Fixture fixture(final SharedWaypointService.Limits limits) {
        return fixture(new SharedWaypointStore.Snapshot(0, List.of()), limits);
    }

    private static Fixture fixture(
        final SharedWaypointStore.Snapshot initial,
        final SharedWaypointService.Limits limits
    ) {
        final SharedWaypointStore store = new SharedWaypointStore(initial);
        final MemoryPersistence persistence = new MemoryPersistence();
        final MutableClock clock = new MutableClock(10_000L);
        final AtomicLong ids = new AtomicLong(1_000L);
        final List<SharedWaypointService.AuditEvent> audit = new ArrayList<>();
        final SharedWaypointValidator validator = new SharedWaypointValidator(Map.of(
            DimensionId.OVERWORLD, new SharedWaypointValidator.HeightRange(0, 256),
            DimensionId.NETHER, new SharedWaypointValidator.HeightRange(0, 128)
        ));
        final SharedWaypointService service = new SharedWaypointService(
            store, persistence, validator, clock, () -> uuid(ids.getAndIncrement()), limits, audit::add, LOGGER
        );
        return new Fixture(service, persistence, clock, audit);
    }

    private static SharedWaypointService.CreateRequest createRequest(
        final UUID operationId,
        final long expectedRevision,
        final String name
    ) {
        return new SharedWaypointService.CreateRequest(
            operationId, expectedRevision, name, DimensionId.OVERWORLD,
            12.5d, 64d, -8.25d, 0xFF33AA66, Waypoint.Type.NORMAL
        );
    }

    private static SharedWaypointService.Actor actor(final long id, final String name, final boolean operator) {
        return new SharedWaypointService.Actor(uuid(id), name, operator);
    }

    private static UUID uuid(final long value) {
        return new UUID(0L, value);
    }

    private record Fixture(
        SharedWaypointService service,
        MemoryPersistence persistence,
        MutableClock clock,
        List<SharedWaypointService.AuditEvent> audit
    ) {
    }

    private static final class MemoryPersistence implements SharedWaypointPersistence {
        private int saves;
        private boolean fail;
        private SharedWaypointStore.Snapshot saved = new SharedWaypointStore.Snapshot(0, List.of());

        @Override
        public SharedWaypointStore.Snapshot load() {
            return saved;
        }

        @Override
        public void save(final SharedWaypointStore.Snapshot snapshot) throws IOException {
            saves++;
            if (fail) {
                throw new IOException("injected failure");
            }
            saved = snapshot;
        }
    }

    private static final class MutableClock extends Clock {
        private long epochMs;

        private MutableClock(final long epochMs) {
            this.epochMs = epochMs;
        }

        private void advanceMillis(final long millis) {
            epochMs += millis;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(epochMs);
        }

        @Override
        public long millis() {
            return epochMs;
        }
    }
}
