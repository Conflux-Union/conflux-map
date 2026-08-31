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
    void operatorOnlyPolicyRejectsEveryNonOperatorMutation() {
        final Fixture fixture = fixture(
            new SharedWaypointService.Limits(20, 10, 30),
            SharedWaypointService.AccessPolicy.OPERATOR_ONLY
        );

        final SharedWaypointService.MutationResult forbiddenCreate = fixture.service.create(
            PLAYER, createRequest(uuid(90), 0, "Player point")
        );
        assertEquals(SharedWaypointService.MutationError.FORBIDDEN, forbiddenCreate.error());

        final SharedWaypoint created = fixture.service.create(
            OPERATOR, createRequest(uuid(91), 0, "Operator point")
        ).delta().waypoint();
        final SharedWaypointService.MutationResult forbiddenUpdate = fixture.service.update(
            PLAYER,
            new SharedWaypointService.UpdateRequest(
                uuid(92), created.revision(), created.id(), "Changed", DimensionId.OVERWORLD,
                created.x(), created.y(), created.z(), created.colorArgb(), created.type()
            )
        );
        final SharedWaypointService.MutationResult forbiddenDelete = fixture.service.delete(
            PLAYER,
            new SharedWaypointService.DeleteRequest(uuid(93), created.revision(), created.id())
        );

        assertEquals(SharedWaypointService.MutationError.FORBIDDEN, forbiddenUpdate.error());
        assertEquals(SharedWaypointService.MutationError.FORBIDDEN, forbiddenDelete.error());
        assertEquals(List.of(created), fixture.service.snapshot().waypoints());
    }

    @Test
    void ownerManagedPolicyLetsPlayersManageOnlyTheirOwnWaypoints() {
        final Fixture fixture = fixture(
            new SharedWaypointService.Limits(20, 10, 30),
            SharedWaypointService.AccessPolicy.OWNER_MANAGED
        );
        final SharedWaypoint created = fixture.service.create(
            PLAYER, createRequest(uuid(94), 0, "Player point")
        ).delta().waypoint();

        final SharedWaypointService.MutationResult forbidden = fixture.service.update(
            OTHER,
            new SharedWaypointService.UpdateRequest(
                uuid(95), created.revision(), created.id(), "Stolen", DimensionId.OVERWORLD,
                created.x(), created.y(), created.z(), created.colorArgb(), created.type()
            )
        );
        final SharedWaypointService.MutationResult updated = fixture.service.update(
            PLAYER,
            new SharedWaypointService.UpdateRequest(
                uuid(96), created.revision(), created.id(), "Owned", DimensionId.OVERWORLD,
                created.x(), created.y(), created.z(), created.colorArgb(), created.type()
            )
        );
        final SharedWaypointService.MutationResult deleted = fixture.service.delete(
            PLAYER,
            new SharedWaypointService.DeleteRequest(
                uuid(97), updated.delta().waypoint().revision(), created.id()
            )
        );

        assertEquals(SharedWaypointService.MutationError.FORBIDDEN, forbidden.error());
        assertTrue(updated.applied());
        assertTrue(deleted.applied());
        assertTrue(deleted.snapshot().waypoints().isEmpty());
    }

    @Test
    void createInjectsAuthorityFieldsPersistsThenCommitsAndReplaysIdempotently() {
        final Fixture fixture = fixture(new SharedWaypointService.Limits(20, 10, 30));
        final UUID operationId = uuid(100);
        final SharedWaypointService.CreateRequest request = new SharedWaypointService.CreateRequest(
            operationId, 0, "  Home  ", DimensionId.OVERWORLD,
            100d, 64d, -8.25d, 0xFF33AA66, Waypoint.Type.NORMAL,
            "minecraft:diamond", "H\uD83D\uDE80"
        );

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
        assertEquals("minecraft:diamond", waypoint.iconItemId());
        assertEquals("H\uD83D\uDE80", waypoint.markerLabel());
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
    void duplicateBlockLocationIsRejectedAcrossPlayersWithoutPersistence() {
        final Fixture fixture = fixture(new SharedWaypointService.Limits(20, 10, 30));
        final SharedWaypointService.CreateRequest first = createRequestAt(
            uuid(102), 0L, "First", DimensionId.OVERWORLD, 12.1d, 64.9d, -8.1d
        );
        final SharedWaypointService.CreateRequest duplicate = createRequestAt(
            uuid(103), 1L, "Second", DimensionId.OVERWORLD, 12.9d, 64.1d, -8.9d
        );

        assertTrue(fixture.service.create(PLAYER, first).applied());
        final SharedWaypointService.MutationResult result = fixture.service.create(OTHER, duplicate);

        assertEquals(SharedWaypointService.MutationError.DUPLICATE_LOCATION, result.error());
        assertEquals(1L, result.snapshot().revision());
        assertEquals(1, result.snapshot().waypoints().size());
        assertEquals(1, fixture.persistence.saves);
    }

    @Test
    void sameCoordinatesInDifferentDimensionsAndDifferentHeightsRemainDistinct() {
        final Fixture fixture = fixture(new SharedWaypointService.Limits(20, 10, 30));

        assertTrue(fixture.service.create(PLAYER, createRequestAt(
            uuid(104), 0L, "Surface", DimensionId.OVERWORLD, 12d, 64d, -8d
        )).applied());
        assertTrue(fixture.service.create(OTHER, createRequestAt(
            uuid(106), 1L, "Cave", DimensionId.OVERWORLD, 12d, 63d, -8d
        )).applied());
        assertTrue(fixture.service.create(OPERATOR, createRequestAt(
            uuid(107), 2L, "Nether", DimensionId.NETHER, 12d, 64d, -8d
        )).applied());

        assertEquals(3, fixture.service.snapshot().waypoints().size());
        assertEquals(3, fixture.persistence.saves);
    }

    @Test
    void deletingTheOnlyWaypointAtALocationAllowsRepublishing() {
        final Fixture fixture = fixture(new SharedWaypointService.Limits(20, 10, 30));
        final SharedWaypoint created = fixture.service.create(
            PLAYER, createRequestAt(uuid(108), 0L, "Old", DimensionId.OVERWORLD, 3d, 70d, 4d)
        ).delta().waypoint();

        assertTrue(fixture.service.delete(
            PLAYER, new SharedWaypointService.DeleteRequest(uuid(109), 1L, created.id())
        ).applied());
        assertTrue(fixture.service.create(
            OTHER, createRequestAt(uuid(115), 2L, "New", DimensionId.OVERWORLD, 3.9d, 70.1d, 4.2d)
        ).applied());
    }

    @Test
    void historicalDuplicateLocationsRemainReadableButBlockNewDuplicates() {
        final SharedWaypoint first = new SharedWaypoint(
            uuid(1_600), PLAYER.playerId(), PLAYER.playerName(), "First", DimensionId.OVERWORLD,
            8.1d, 70.2d, -4.1d, 0xFF33AA66, Waypoint.Type.NORMAL, 1L, 1L
        );
        final SharedWaypoint second = new SharedWaypoint(
            uuid(1_601), OTHER.playerId(), OTHER.playerName(), "Second", DimensionId.OVERWORLD,
            8.9d, 70.8d, -4.9d, 0xFF33AA66, Waypoint.Type.NORMAL, 2L, 2L
        );
        final Fixture fixture = fixture(
            new SharedWaypointStore.Snapshot(2L, List.of(first, second)),
            new SharedWaypointService.Limits(20, 10, 30)
        );

        assertEquals(2, fixture.service.snapshot().waypoints().size());
        final SharedWaypointService.MutationResult result = fixture.service.create(
            OPERATOR,
            createRequestAt(uuid(1_602), 2L, "Third", DimensionId.OVERWORLD, 8.5d, 70.5d, -4.5d)
        );

        assertEquals(SharedWaypointService.MutationError.DUPLICATE_LOCATION, result.error());
        assertEquals(2, result.snapshot().waypoints().size());
        assertEquals(0, fixture.persistence.saves);
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
    void exhaustedRevisionRejectsDeleteWithoutPersisting() {
        final SharedWaypoint waypoint = new SharedWaypoint(
            uuid(1_500), PLAYER.playerId(), PLAYER.playerName(), "Limit", DimensionId.OVERWORLD,
            12.5d, 64d, -8.25d, 0xFF33AA66, Waypoint.Type.NORMAL, 1L, Long.MAX_VALUE
        );
        final Fixture fixture = fixture(
            new SharedWaypointStore.Snapshot(Long.MAX_VALUE, List.of(waypoint)),
            new SharedWaypointService.Limits(20, 10, 30)
        );

        final SharedWaypointService.MutationResult deleted = fixture.service.delete(
            PLAYER, new SharedWaypointService.DeleteRequest(uuid(1_501), Long.MAX_VALUE, waypoint.id())
        );
        assertEquals(SharedWaypointService.MutationError.PERSISTENCE_FAILED, deleted.error());
        assertEquals(Long.MAX_VALUE, fixture.service.snapshot().revision());
        assertEquals(List.of(waypoint), fixture.service.snapshot().waypoints());
        assertEquals(0, fixture.persistence.saves);
    }

    @Test
    void ownerManagedDeleteIsAllowedForPublisherAndOperatorButNotOtherPlayers() {
        final Fixture fixture = fixture(new SharedWaypointService.Limits(20, 10, 30));
        final SharedWaypoint created = fixture.service.create(
            PLAYER, createRequest(uuid(120), 0, "Public")
        ).delta().waypoint();

        final SharedWaypointService.MutationResult forbidden = fixture.service.delete(
            OTHER, new SharedWaypointService.DeleteRequest(uuid(121), 1, created.id())
        );
        assertEquals(SharedWaypointService.MutationError.FORBIDDEN, forbidden.error());
        assertEquals(1, fixture.service.snapshot().waypoints().size());

        final SharedWaypointService.MutationResult deletedByPublisher = fixture.service.delete(
            PLAYER, new SharedWaypointService.DeleteRequest(uuid(125), 1, created.id())
        );
        assertTrue(deletedByPublisher.applied());
        assertEquals(2L, deletedByPublisher.snapshot().revision());

        final SharedWaypoint republished = fixture.service.create(
            OTHER, createRequest(uuid(126), 2, "Public again")
        ).delta().waypoint();
        final SharedWaypointService.MutationResult deletedByOperator = fixture.service.delete(
            OPERATOR, new SharedWaypointService.DeleteRequest(uuid(127), 3, republished.id())
        );
        assertTrue(deletedByOperator.applied());
        assertTrue(deletedByOperator.snapshot().waypoints().isEmpty());
    }

    @Test
    void operatorCanUpdateAWaypointWithoutChangingItsOwnershipOrCreationTime() {
        final Fixture fixture = fixture(new SharedWaypointService.Limits(20, 10, 30));
        final SharedWaypoint created = fixture.service.create(
            PLAYER, createRequest(uuid(1_300), 0, "Old name")
        ).delta().waypoint();

        final SharedWaypointService.MutationResult result = fixture.service.update(
            OPERATOR,
            new SharedWaypointService.UpdateRequest(
                uuid(1_301), created.revision(), created.id(), "  New name  ",
                DimensionId.NETHER, 20.5d, 70d, -30.25d, 0xFF3498DB, Waypoint.Type.NORMAL,
                "minecraft:compass", "NEW"
            )
        );

        assertTrue(result.applied());
        assertEquals(SharedWaypointStore.DeltaKind.UPSERT, result.delta().kind());
        final SharedWaypoint updated = result.delta().waypoint();
        assertEquals(created.id(), updated.id());
        assertEquals(created.publisherId(), updated.publisherId());
        assertEquals(created.publisherName(), updated.publisherName());
        assertEquals(created.createdAtEpochMs(), updated.createdAtEpochMs());
        assertEquals("New name", updated.name());
        assertEquals(DimensionId.NETHER, updated.dimensionId());
        assertEquals(20.5d, updated.x());
        assertEquals(70d, updated.y());
        assertEquals(-30.25d, updated.z());
        assertEquals(0xFF3498DB, updated.colorArgb());
        assertEquals("minecraft:compass", updated.iconItemId());
        assertEquals("NEW", updated.markerLabel());
        assertEquals(2L, updated.revision());
        assertEquals(2, fixture.persistence.saves);
        assertEquals(SharedWaypointService.Action.UPDATE, fixture.audit.get(1).action());
    }

    @Test
    void updateRejectsOtherPlayersAndAnOccupiedDestination() {
        final Fixture fixture = fixture(new SharedWaypointService.Limits(20, 10, 30));
        final SharedWaypoint first = fixture.service.create(
            PLAYER, createRequestAt(uuid(1_310), 0, "First", DimensionId.OVERWORLD, 1d, 64d, 1d)
        ).delta().waypoint();
        fixture.service.create(
            OTHER, createRequestAt(uuid(1_311), 1, "Second", DimensionId.OVERWORLD, 2d, 64d, 2d)
        );

        final SharedWaypointService.UpdateRequest request = new SharedWaypointService.UpdateRequest(
            uuid(1_312), first.revision(), first.id(), "Moved", DimensionId.OVERWORLD,
            2.9d, 64.1d, 2.9d, 0xFF33AA66, Waypoint.Type.NORMAL
        );
        final SharedWaypointService.MutationResult forbidden = fixture.service.update(OTHER, request);
        final SharedWaypointService.MutationResult duplicate = fixture.service.update(
            OPERATOR,
            new SharedWaypointService.UpdateRequest(
                uuid(1_313), first.revision(), first.id(), "Moved", DimensionId.OVERWORLD,
                2.9d, 64.1d, 2.9d, 0xFF33AA66, Waypoint.Type.NORMAL
            )
        );

        assertEquals(SharedWaypointService.MutationError.FORBIDDEN, forbidden.error());
        assertEquals(SharedWaypointService.MutationError.DUPLICATE_LOCATION, duplicate.error());
        assertEquals(2L, fixture.service.snapshot().revision());
        assertEquals(2, fixture.persistence.saves);
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
            PLAYER, new SharedWaypointService.DeleteRequest(uuid(124), first.revision(), first.id())
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
            new SharedWaypointService.CreateRequest(uuid(145), 0, "Name", DimensionId.OVERWORLD, 0, 64, 0, 0x0033AA66, Waypoint.Type.NORMAL),
            new SharedWaypointService.CreateRequest(uuid(148), 0, "Name", DimensionId.OVERWORLD, 0, 64, 0, 0xFFFFFFFF, Waypoint.Type.NORMAL, "example:diamond", ""),
            new SharedWaypointService.CreateRequest(uuid(149), 0, "Name", DimensionId.OVERWORLD, 0, 64, 0, 0xFFFFFFFF, Waypoint.Type.NORMAL, "", "FOUR")
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
    void appliedResultsSurviveForReplayAndTrackedPlayersAreGloballyBounded() {
        final Fixture fixture = fixture(new SharedWaypointService.Limits(20, 10, 30));
        final UUID operationId = uuid(235);
        final SharedWaypointService.CreateRequest request = createRequest(operationId, 0, "Home");
        final SharedWaypointService.MutationResult first = fixture.service.create(
            PLAYER, request
        );

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

    @Test
    void rateLimitedRejectionIsNotCachedSoTheSameOperationSucceedsAfterRefill() {
        final Fixture fixture = fixture(new SharedWaypointService.Limits(20, 10, 30));
        for (int i = 0; i < SharedWaypointService.MUTATION_BURST; i++) {
            fixture.service.delete(
                PLAYER, new SharedWaypointService.DeleteRequest(uuid(300 + i), 0, uuid(900 + i))
            );
        }
        final UUID operationId = uuid(320);
        final SharedWaypointService.CreateRequest request = createRequest(operationId, 0, "Home");

        final SharedWaypointService.MutationResult limited = fixture.service.create(PLAYER, request);
        fixture.clock.advanceMillis(2_000);
        final SharedWaypointService.MutationResult retried = fixture.service.create(PLAYER, request);

        assertEquals(SharedWaypointService.MutationError.RATE_LIMITED, limited.error());
        assertTrue(retried.applied());
        assertFalse(retried.replayed());
    }

    @Test
    void persistenceFailureIsNotCachedSoTheSameOperationSucceedsOnceStorageRecovers() {
        final Fixture fixture = fixture(new SharedWaypointService.Limits(20, 10, 30));
        final UUID operationId = uuid(330);
        final SharedWaypointService.CreateRequest request = createRequest(operationId, 0, "Home");

        fixture.persistence.fail = true;
        final SharedWaypointService.MutationResult failed = fixture.service.create(PLAYER, request);
        fixture.persistence.fail = false;
        final SharedWaypointService.MutationResult retried = fixture.service.create(PLAYER, request);

        assertEquals(SharedWaypointService.MutationError.PERSISTENCE_FAILED, failed.error());
        assertTrue(retried.applied());
        assertFalse(retried.replayed());
        assertEquals(1L, retried.snapshot().revision());
    }

    @Test
    void sanitizeLoadedQuarantinesInvalidPersistedEntriesInsteadOfDisablingTheFeature() {
        final SharedWaypoint valid = new SharedWaypoint(
            uuid(1_700), PLAYER.playerId(), PLAYER.playerName(), "Kept", DimensionId.OVERWORLD,
            8d, 70d, -4d, 0xFF33AA66, Waypoint.Type.NORMAL, 1L, 1L
        );
        final SharedWaypoint removedDimension = new SharedWaypoint(
            uuid(1_701), OTHER.playerId(), OTHER.playerName(), "Dropped", DimensionId.END,
            8d, 70d, -4d, 0xFF33AA66, Waypoint.Type.NORMAL, 2L, 2L
        );
        final SharedWaypointValidator validator = new SharedWaypointValidator(Map.of(
            DimensionId.OVERWORLD, new SharedWaypointValidator.HeightRange(0, 256)
        ));

        final SharedWaypointStore.Snapshot sanitized = SharedWaypointService.sanitizeLoaded(
            new SharedWaypointStore.Snapshot(2L, List.of(valid, removedDimension)), validator, LOGGER
        );

        assertEquals(2L, sanitized.revision());
        assertEquals(List.of(valid), sanitized.waypoints());
        final Fixture fixture = fixture(sanitized, new SharedWaypointService.Limits(20, 10, 30));
        assertEquals(1, fixture.service.snapshot().waypoints().size());
    }

    private static Fixture fixture(final SharedWaypointService.Limits limits) {
        return fixture(
            new SharedWaypointStore.Snapshot(0, List.of()),
            limits,
            SharedWaypointService.AccessPolicy.OWNER_MANAGED
        );
    }

    private static Fixture fixture(
        final SharedWaypointService.Limits limits,
        final SharedWaypointService.AccessPolicy accessPolicy
    ) {
        return fixture(new SharedWaypointStore.Snapshot(0, List.of()), limits, accessPolicy);
    }

    private static Fixture fixture(
        final SharedWaypointStore.Snapshot initial,
        final SharedWaypointService.Limits limits
    ) {
        return fixture(initial, limits, SharedWaypointService.AccessPolicy.OWNER_MANAGED);
    }

    private static Fixture fixture(
        final SharedWaypointStore.Snapshot initial,
        final SharedWaypointService.Limits limits,
        final SharedWaypointService.AccessPolicy accessPolicy
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
            store, persistence, validator, clock, () -> uuid(ids.getAndIncrement()), limits,
            accessPolicy, audit::add, LOGGER
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
            operationId.getLeastSignificantBits(), 64d, -8.25d, 0xFF33AA66, Waypoint.Type.NORMAL
        );
    }

    private static SharedWaypointService.CreateRequest createRequestAt(
        final UUID operationId,
        final long expectedRevision,
        final String name,
        final DimensionId dimensionId,
        final double x,
        final double y,
        final double z
    ) {
        return new SharedWaypointService.CreateRequest(
            operationId, expectedRevision, name, dimensionId,
            x, y, z, 0xFF33AA66, Waypoint.Type.NORMAL
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
