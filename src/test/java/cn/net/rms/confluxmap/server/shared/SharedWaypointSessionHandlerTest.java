package cn.net.rms.confluxmap.server.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.net.shared.CreateC2S;
import cn.net.rms.confluxmap.core.net.shared.DeleteC2S;
import cn.net.rms.confluxmap.core.net.shared.HelloC2S;
import cn.net.rms.confluxmap.core.net.shared.LockC2S;
import cn.net.rms.confluxmap.core.net.shared.ResultS2C;
import cn.net.rms.confluxmap.core.net.shared.RemoveS2C;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointProto;
import cn.net.rms.confluxmap.core.net.shared.SnapshotS2C;
import cn.net.rms.confluxmap.core.net.shared.StatusS2C;
import cn.net.rms.confluxmap.core.net.shared.SubscribeC2S;
import cn.net.rms.confluxmap.core.net.shared.UpsertS2C;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;

class SharedWaypointSessionHandlerTest {
    private static final SharedWaypointSessionHandler.Peer PLAYER = peer(1, "Player", false);
    private static final SharedWaypointSessionHandler.Peer OPERATOR = peer(2, "Operator", true);

    @Test
    void requiresCompatibleHelloBeforeStatusSubscriptionOrMutation() {
        final Fixture fixture = fixture(true);

        assertTrue(fixture.handler.handle(PLAYER, new SubscribeC2S(), fixture.environment).direct().isEmpty());
        final SharedWaypointSessionHandler.Dispatch unsupported = fixture.handler.handle(
            PLAYER,
            new HelloC2S(SharedWaypointProto.PROTO_MAJOR + 1, 0),
            fixture.environment
        );
        final StatusS2C unsupportedStatus = assertInstanceOf(StatusS2C.class, unsupported.direct().get(0));
        assertFalse(unsupportedStatus.supported());
        assertFalse(unsupportedStatus.enabled());
        assertFalse(fixture.handler.isCompatible(PLAYER.id()));

        final SharedWaypointSessionHandler.Dispatch hello = compatibleHello(fixture, PLAYER);
        final StatusS2C status = assertInstanceOf(StatusS2C.class, hello.direct().get(0));
        assertTrue(status.supported());
        assertTrue(status.enabled());
        assertFalse(status.operator());
        assertEquals("world-id", status.worldId());
        assertEquals(0L, status.revision());
        assertEquals(20, status.maxWorld());
        assertEquals(10, status.maxPlayer());

        final SnapshotS2C snapshot = assertInstanceOf(
            SnapshotS2C.class,
            fixture.handler.handle(PLAYER, new SubscribeC2S(), fixture.environment).direct().get(0)
        );
        assertEquals(0L, snapshot.revision());
        assertFalse(snapshot.operator());
        assertTrue(snapshot.list().isEmpty());
        assertTrue(fixture.handler.isSubscribed(PLAYER.id()));

        compatibleHello(fixture, OPERATOR);
        final SnapshotS2C operatorSnapshot = assertInstanceOf(
            SnapshotS2C.class,
            fixture.handler.handle(OPERATOR, new SubscribeC2S(), fixture.environment).direct().get(0)
        );
        assertTrue(operatorSnapshot.operator());
        fixture.handler.clearSubscriptions();
        assertFalse(fixture.handler.isSubscribed(PLAYER.id()));
        assertFalse(fixture.handler.isSubscribed(OPERATOR.id()));
    }

    @Test
    void appliedMutationReturnsResultAndBroadcastsOneDelta() {
        final Fixture fixture = fixture(true);
        compatibleHello(fixture, PLAYER);
        fixture.handler.handle(PLAYER, new SubscribeC2S(), fixture.environment);
        final UUID operationId = uuid(100);

        final SharedWaypointSessionHandler.Dispatch dispatch = fixture.handler.handle(
            PLAYER,
            create(operationId, 0L, "Spawn"),
            fixture.environment
        );

        final ResultS2C result = assertInstanceOf(ResultS2C.class, dispatch.direct().get(0));
        assertEquals(operationId, result.operationId());
        assertEquals(SharedWaypointProto.RESULT_STATUS_APPLIED, result.statusCode());
        assertEquals(SharedWaypointProto.RESULT_ERROR_NONE, result.errorCode());
        final UpsertS2C upsert = assertInstanceOf(UpsertS2C.class, dispatch.broadcast());
        assertEquals(1L, upsert.revision());
        assertEquals("Spawn", upsert.waypoint().name());
    }

    @Test
    void noopAndIdempotentReplayOnlyReturnResult() {
        final Fixture fixture = fixture(true);
        compatibleHello(fixture, PLAYER);
        final UUID operationId = uuid(110);
        final CreateC2S request = create(operationId, 0L, "Spawn");
        final SharedWaypointSessionHandler.Dispatch first = fixture.handler.handle(
            PLAYER, request, fixture.environment
        );
        final UpsertS2C created = assertInstanceOf(UpsertS2C.class, first.broadcast());

        fixture.handler.disconnect(PLAYER.id());
        compatibleHello(fixture, PLAYER);
        final SharedWaypointSessionHandler.Dispatch replay = fixture.handler.handle(
            PLAYER, request, fixture.environment
        );
        assertInstanceOf(ResultS2C.class, replay.direct().get(0));
        assertNull(replay.broadcast());
        assertEquals(1L, fixture.service.snapshot().revision());

        compatibleHello(fixture, OPERATOR);
        final SharedWaypointSessionHandler.Dispatch noop = fixture.handler.handle(
            OPERATOR,
            new LockC2S(uuid(111), created.waypoint().id(), created.waypoint().revision(), false),
            fixture.environment
        );
        final ResultS2C noopResult = assertInstanceOf(ResultS2C.class, noop.direct().get(0));
        assertEquals(SharedWaypointProto.RESULT_STATUS_APPLIED, noopResult.statusCode());
        assertNull(noop.broadcast());
        assertEquals(1L, fixture.service.snapshot().revision());
    }

    @Test
    void deleteAndLockRequestsUseCurrentOperatorPermissionAndTargetRevision() {
        final Fixture fixture = fixture(true);
        compatibleHello(fixture, PLAYER);
        compatibleHello(fixture, OPERATOR);
        final UpsertS2C created = assertInstanceOf(
            UpsertS2C.class,
            fixture.handler.handle(
                PLAYER, create(uuid(115), 0L, "Spawn"), fixture.environment
            ).broadcast()
        );

        final ResultS2C forbiddenLock = assertInstanceOf(
            ResultS2C.class,
            fixture.handler.handle(
                PLAYER,
                new LockC2S(uuid(116), created.waypoint().id(), created.waypoint().revision(), true),
                fixture.environment
            ).direct().get(0)
        );
        assertEquals(SharedWaypointProto.RESULT_ERROR_FORBIDDEN, forbiddenLock.errorCode());

        final UpsertS2C locked = assertInstanceOf(
            UpsertS2C.class,
            fixture.handler.handle(
                OPERATOR,
                new LockC2S(uuid(117), created.waypoint().id(), created.waypoint().revision(), true),
                fixture.environment
            ).broadcast()
        );
        assertTrue(locked.waypoint().locked());

        final ResultS2C forbiddenDelete = assertInstanceOf(
            ResultS2C.class,
            fixture.handler.handle(
                PLAYER,
                new DeleteC2S(uuid(118), locked.waypoint().id(), locked.waypoint().revision()),
                fixture.environment
            ).direct().get(0)
        );
        assertEquals(SharedWaypointProto.RESULT_ERROR_FORBIDDEN, forbiddenDelete.errorCode());

        final RemoveS2C removed = assertInstanceOf(
            RemoveS2C.class,
            fixture.handler.handle(
                OPERATOR,
                new DeleteC2S(uuid(119), locked.waypoint().id(), locked.waypoint().revision()),
                fixture.environment
            ).broadcast()
        );
        assertEquals(locked.waypoint().id(), removed.id());
        assertEquals(3L, removed.revision());
    }

    @Test
    void disabledFeatureRejectsWritesAndClearsSubscriptions() {
        final Fixture fixture = fixture(false);
        final StatusS2C status = assertInstanceOf(
            StatusS2C.class,
            compatibleHello(fixture, PLAYER).direct().get(0)
        );
        assertTrue(status.supported());
        assertFalse(status.enabled());

        final SharedWaypointSessionHandler.Dispatch subscribe = fixture.handler.handle(
            PLAYER, new SubscribeC2S(), fixture.environment
        );
        assertInstanceOf(StatusS2C.class, subscribe.direct().get(0));
        assertFalse(fixture.handler.isSubscribed(PLAYER.id()));

        final SharedWaypointSessionHandler.Dispatch rejected = fixture.handler.handle(
            PLAYER, create(uuid(120), 0L, "Spawn"), fixture.environment
        );
        final ResultS2C result = assertInstanceOf(ResultS2C.class, rejected.direct().get(0));
        assertEquals(SharedWaypointProto.RESULT_STATUS_REJECTED, result.statusCode());
        assertEquals(SharedWaypointProto.RESULT_ERROR_DISABLED, result.errorCode());
        assertInstanceOf(StatusS2C.class, rejected.direct().get(1));
        assertNull(rejected.broadcast());
        assertEquals(0L, fixture.service.snapshot().revision());
    }

    @Test
    void rateLimitsSnapshotAndDisabledMutationStateWorkPerSession() {
        final MutableClock clock = new MutableClock(10_000L);
        final Fixture enabled = fixture(true, clock);
        compatibleHello(enabled, PLAYER);

        for (int i = 1; i < SharedWaypointSessionHandler.CONTROL_REQUEST_BURST; i++) {
            assertInstanceOf(
                SnapshotS2C.class,
                enabled.handler.handle(PLAYER, new SubscribeC2S(), enabled.environment).direct().get(0)
            );
        }
        assertTrue(enabled.handler.handle(
            PLAYER, new SubscribeC2S(), enabled.environment
        ).direct().isEmpty());

        // A new hello must not reset the bucket and allow a snapshot-rate bypass.
        assertTrue(compatibleHello(enabled, PLAYER).direct().isEmpty());
        assertTrue(enabled.handler.handle(
            PLAYER, new SubscribeC2S(), enabled.environment
        ).direct().isEmpty());
        clock.advanceMillis(1_000L);
        assertInstanceOf(
            SnapshotS2C.class,
            enabled.handler.handle(PLAYER, new SubscribeC2S(), enabled.environment).direct().get(0)
        );

        final Fixture disabled = fixture(false, clock);
        compatibleHello(disabled, PLAYER);
        for (int i = 1; i < SharedWaypointSessionHandler.CONTROL_REQUEST_BURST; i++) {
            final SharedWaypointSessionHandler.Dispatch dispatch = disabled.handler.handle(
                PLAYER,
                create(uuid(1_000L + i), 0L, "Disabled"),
                disabled.environment
            );
            final ResultS2C result = assertInstanceOf(ResultS2C.class, dispatch.direct().get(0));
            assertEquals(SharedWaypointProto.RESULT_ERROR_DISABLED, result.errorCode());
            assertEquals(2, dispatch.direct().size());
        }

        final SharedWaypointSessionHandler.Dispatch limited = disabled.handler.handle(
            PLAYER,
            create(uuid(2_000L), 0L, "Limited"),
            disabled.environment
        );
        final ResultS2C result = assertInstanceOf(ResultS2C.class, limited.direct().get(0));
        assertEquals(SharedWaypointProto.RESULT_ERROR_RATE_LIMITED, result.errorCode());
        assertEquals(1, limited.direct().size());

        disabled.handler.disconnect(PLAYER.id());
        assertFalse(compatibleHello(disabled, PLAYER).direct().isEmpty());
        final ResultS2C afterReconnect = assertInstanceOf(
            ResultS2C.class,
            disabled.handler.handle(
                PLAYER,
                create(uuid(2_001L), 0L, "After reconnect"),
                disabled.environment
            ).direct().get(0)
        );
        assertEquals(SharedWaypointProto.RESULT_ERROR_DISABLED, afterReconnect.errorCode());
    }

    @Test
    void malformedStrikesAreSaturatingBoundedAndResetOnlyOnDisconnect() {
        final Fixture fixture = fixture(true);
        compatibleHello(fixture, PLAYER);
        assertEquals(1, fixture.handler.recordMalformed(PLAYER.id()).strikes());
        assertEquals(2, fixture.handler.recordMalformed(PLAYER.id()).strikes());
        final SharedWaypointSessionHandler.MalformedOutcome third =
            fixture.handler.recordMalformed(PLAYER.id());
        assertTrue(third.muted());
        assertTrue(third.newlyMuted());
        assertTrue(fixture.handler.isMuted(PLAYER.id()));
        assertEquals(SharedWaypointSessionHandler.MAX_MALFORMED_STRIKES,
            fixture.handler.recordMalformed(PLAYER.id()).strikes());
        assertTrue(compatibleHello(fixture, PLAYER).direct().isEmpty());

        fixture.handler.disconnect(PLAYER.id());
        assertFalse(fixture.handler.isMuted(PLAYER.id()));
        assertFalse(compatibleHello(fixture, PLAYER).direct().isEmpty());

        for (int i = 0; i < SharedWaypointSessionHandler.MAX_TRACKED_SESSIONS + 10; i++) {
            fixture.handler.recordMalformed(uuid(10_000L + i));
        }
        assertEquals(
            SharedWaypointSessionHandler.MAX_TRACKED_SESSIONS,
            fixture.handler.trackedSessionCount()
        );
    }

    @Test
    void detectsLiveOperatorPermissionChangesWithoutReconnect() {
        final Fixture fixture = fixture(true);
        compatibleHello(fixture, PLAYER);

        assertFalse(fixture.handler.updateOperator(PLAYER));
        final SharedWaypointSessionHandler.Peer promoted = peer(1, "Player", true);
        assertTrue(fixture.handler.updateOperator(promoted));
        assertFalse(fixture.handler.updateOperator(promoted));
        assertTrue(fixture.handler.status(promoted, fixture.environment).operator());

        assertTrue(fixture.handler.updateOperator(PLAYER));
        assertFalse(fixture.handler.status(PLAYER, fixture.environment).operator());
    }

    @Test
    void resultCodesAreExplicitlyMappedRatherThanUsingEnumOrdinals() {
        assertEquals(0, SharedWaypointSessionHandler.statusCode(SharedWaypointService.MutationStatus.APPLIED));
        assertEquals(1, SharedWaypointSessionHandler.statusCode(SharedWaypointService.MutationStatus.REJECTED));
        final int[] expected = {0, 1, 2, 3, 4, 5, 6, 7, 11, 8, 9};
        final SharedWaypointService.MutationError[] errors = {
            SharedWaypointService.MutationError.NONE,
            SharedWaypointService.MutationError.INVALID_REQUEST,
            SharedWaypointService.MutationError.REVISION_CONFLICT,
            SharedWaypointService.MutationError.NOT_FOUND,
            SharedWaypointService.MutationError.FORBIDDEN,
            SharedWaypointService.MutationError.WORLD_QUOTA_EXCEEDED,
            SharedWaypointService.MutationError.PLAYER_QUOTA_EXCEEDED,
            SharedWaypointService.MutationError.RATE_LIMITED,
            SharedWaypointService.MutationError.DUPLICATE_LOCATION,
            SharedWaypointService.MutationError.PERSISTENCE_FAILED,
            SharedWaypointService.MutationError.ID_GENERATION_FAILED
        };
        for (int i = 0; i < errors.length; i++) {
            assertEquals(expected[i], SharedWaypointSessionHandler.errorCode(errors[i]));
        }
    }

    private static SharedWaypointSessionHandler.Dispatch compatibleHello(
        final Fixture fixture,
        final SharedWaypointSessionHandler.Peer peer
    ) {
        return fixture.handler.handle(
            peer,
            new HelloC2S(SharedWaypointProto.PROTO_MAJOR, SharedWaypointProto.PROTO_MINOR),
            fixture.environment
        );
    }

    private static CreateC2S create(final UUID operationId, final long revision, final String name) {
        return new CreateC2S(
            operationId,
            revision,
            name,
            DimensionId.OVERWORLD,
            12.5d,
            64d,
            -8.25d,
            0xFF33AA66,
            Waypoint.Type.NORMAL
        );
    }

    private static Fixture fixture(final boolean enabled) {
        return fixture(enabled, Clock.fixed(Instant.ofEpochMilli(10_000L), ZoneOffset.UTC));
    }

    private static Fixture fixture(final boolean enabled, final Clock clock) {
        final SharedWaypointStore store = SharedWaypointStore.empty();
        final AtomicLong ids = new AtomicLong(1_000L);
        final SharedWaypointService service = new SharedWaypointService(
            store,
            new MemoryPersistence(),
            new SharedWaypointValidator(Map.of(
                DimensionId.OVERWORLD,
                new SharedWaypointValidator.HeightRange(-64, 320)
            )),
            clock,
            () -> uuid(ids.getAndIncrement()),
            new SharedWaypointService.Limits(20, 10, 30),
            event -> { },
            LogManager.getLogger("SharedWaypointSessionHandlerTest")
        );
        return new Fixture(
            new SharedWaypointSessionHandler(clock),
            service,
            new SharedWaypointSessionHandler.Environment(enabled, "world-id", 20, 10, service)
        );
    }

    private static SharedWaypointSessionHandler.Peer peer(
        final long id,
        final String name,
        final boolean operator
    ) {
        return new SharedWaypointSessionHandler.Peer(uuid(id), name, operator);
    }

    private static UUID uuid(final long value) {
        return new UUID(0L, value);
    }

    private record Fixture(
        SharedWaypointSessionHandler handler,
        SharedWaypointService service,
        SharedWaypointSessionHandler.Environment environment
    ) {
    }

    private static final class MemoryPersistence implements SharedWaypointPersistence {
        private SharedWaypointStore.Snapshot snapshot = new SharedWaypointStore.Snapshot(0L, List.of());

        @Override
        public SharedWaypointStore.Snapshot load() {
            return snapshot;
        }

        @Override
        public void save(final SharedWaypointStore.Snapshot next) {
            snapshot = next;
        }
    }

    private static final class MutableClock extends Clock {
        private long epochMillis;

        private MutableClock(final long epochMillis) {
            this.epochMillis = epochMillis;
        }

        private void advanceMillis(final long millis) {
            epochMillis += millis;
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
            return Instant.ofEpochMilli(epochMillis);
        }

        @Override
        public long millis() {
            return epochMillis;
        }
    }
}
