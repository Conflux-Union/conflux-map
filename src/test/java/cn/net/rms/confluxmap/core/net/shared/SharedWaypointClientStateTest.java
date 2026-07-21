package cn.net.rms.confluxmap.core.net.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SharedWaypointClientStateTest {
    private static final UUID ID = UUID.fromString("12345678-1234-5678-9abc-def012345678");
    private static final UUID PUBLISHER = UUID.fromString("fedcba98-7654-3210-fedc-ba9876543210");

    @Test
    void unavailableChannelAndHandshakeTimeoutSafelyBecomeUnsupported() {
        final SharedWaypointClientState state = new SharedWaypointClientState();

        assertFalse(state.beginConnection(false));
        assertEquals(SharedWaypointClientState.State.UNSUPPORTED, state.view().state());

        assertTrue(state.beginConnection(true));
        assertEquals(SharedWaypointClientState.State.HANDSHAKE, state.view().state());
        for (int i = 1; i < SharedWaypointClientState.HANDSHAKE_TIMEOUT_TICKS; i++) {
            assertFalse(state.tick());
        }
        assertTrue(state.tick());
        assertEquals(SharedWaypointClientState.State.UNSUPPORTED, state.view().state());

        state.reset();
        assertEquals(SharedWaypointClientState.State.UNKNOWN, state.view().state());
        assertTrue(state.view().list().isEmpty());
    }

    @Test
    void disabledServerRequestsOneAdministratorNoticePerConnection() {
        final SharedWaypointClientState state = enabledHandshake(state());
        final StatusS2C disabled = status(false, 7L, false, 32, 8);

        final SharedWaypointClientState.Action first = state.onStatus(disabled);
        assertTrue(first.notifyDisabled());
        assertEquals(SharedWaypointClientState.State.SUPPORTED_DISABLED, state.view().state());
        assertEquals(32, state.view().maxWorld());
        assertEquals(8, state.view().maxPlayer());

        assertFalse(state.onStatus(disabled).notifyDisabled());
        state.reset();
        state.beginConnection(true);
        assertTrue(state.onStatus(disabled).notifyDisabled());
    }

    @Test
    void enabledStatusSubscribesAndSnapshotIsImmutable() {
        final SharedWaypointClientState state = enabledHandshake(state());
        final SharedWaypointClientState.Action statusAction = state.onStatus(status(true, 4L, true, 32, 8));

        assertTrue(statusAction.subscribe());
        assertEquals(SharedWaypointClientState.State.ENABLED, state.view().state());
        assertFalse(state.canMutate());

        state.onSnapshot(new SnapshotS2C(4L, true, List.of(waypoint(ID, "Spawn", 3L))));
        assertTrue(state.canMutate());
        assertTrue(state.view().operator());
        assertEquals(4L, state.view().revision());
        assertEquals("Spawn", state.view().list().get(0).name());
        assertThrows(UnsupportedOperationException.class, () -> state.view().list().clear());
    }

    @Test
    void deltasRequireExactlyTheNextRevisionAndGapResubscribes() {
        final SharedWaypointClientState state = synchronizedState(4L);

        state.onUpsert(new UpsertS2C(4L, waypoint(ID, "Duplicate", 4L)));
        assertEquals("Spawn", state.view().list().get(0).name());

        state.onUpsert(new UpsertS2C(5L, waypoint(ID, "Updated", 5L)));
        assertEquals(5L, state.view().revision());
        assertEquals("Updated", state.view().list().get(0).name());

        final SharedWaypointClientState.Action gap = state.onRemove(new RemoveS2C(7L, ID));
        assertTrue(gap.subscribe());
        assertFalse(state.canMutate());
        assertTrue(state.view().list().isEmpty());

        state.onSnapshot(new SnapshotS2C(7L, false, List.of(waypoint(ID, "Recovered", 7L))));
        assertTrue(state.canMutate());
        assertEquals("Recovered", state.view().list().get(0).name());

        state.onRemove(new RemoveS2C(8L, ID));
        assertEquals(8L, state.view().revision());
        assertTrue(state.view().list().isEmpty());
    }

    @Test
    void revisionConflictInvalidatesCatalogAndRequestsSnapshot() {
        final SharedWaypointClientState state = synchronizedState(4L);

        final SharedWaypointClientState.Action action = state.onRevisionConflict();

        assertTrue(action.subscribe());
        assertFalse(state.canMutate());
        assertTrue(state.view().list().isEmpty());
    }

    @Test
    void missingSnapshotRetriesSubscriptionUntilSynchronizationCompletes() {
        final SharedWaypointClientState state = synchronizedState(4L);
        state.onRevisionConflict();

        for (int i = 1; i < SharedWaypointClientState.SUBSCRIPTION_RETRY_TICKS; i++) {
            assertFalse(state.tickSubscriptionRetry());
        }
        assertTrue(state.tickSubscriptionRetry());
        assertFalse(state.tickSubscriptionRetry());

        state.onSnapshot(new SnapshotS2C(4L, false, List.of(waypoint(ID, "Recovered", 4L))));
        for (int i = 0; i < SharedWaypointClientState.SUBSCRIPTION_RETRY_TICKS; i++) {
            assertFalse(state.tickSubscriptionRetry());
        }
    }

    @Test
    void incompatibleStatusAndSemanticPayloadViolationsDoNotEscape() {
        final SharedWaypointClientState state = enabledHandshake(state());
        state.onStatus(new StatusS2C(
            SharedWaypointProto.PROTO_MAJOR + 1, 0, true, true, false,
            "world", 0L, 32, 8
        ));
        assertEquals(SharedWaypointClientState.State.UNSUPPORTED, state.view().state());

        state.beginConnection(true);
        final SharedWaypointClientState.Action badQuota = state.onStatus(
            status(true, 0L, false, SharedWaypointProto.MAX_SNAPSHOT_WAYPOINTS + 1, 8)
        );
        assertTrue(badQuota.protocolRejected());
        assertEquals(SharedWaypointClientState.State.UNSUPPORTED, state.view().state());

        final SharedWaypointClientState rollbackState = enabledHandshake(state());
        rollbackState.onStatus(status(true, 5L, false, 32, 8));
        final SharedWaypointClientState.Action rollback = rollbackState.onSnapshot(
            new SnapshotS2C(4L, false, List.of(waypoint(ID, "Old", 4L)))
        );
        assertTrue(rollback.protocolRejected());
        assertEquals(SharedWaypointClientState.State.UNSUPPORTED, rollbackState.view().state());

        final SharedWaypointClientState duplicateState = synchronizedState(4L);
        final SharedWaypoint duplicate = waypoint(ID, "Duplicate", 4L);
        final SharedWaypointClientState.Action duplicateResult = duplicateState.onSnapshot(
            new SnapshotS2C(5L, false, List.of(duplicate, duplicate))
        );
        assertTrue(duplicateResult.protocolRejected());
        assertEquals(SharedWaypointClientState.State.UNSUPPORTED, duplicateState.view().state());
        assertTrue(duplicateState.view().list().isEmpty());

        final SharedWaypointClientState coordinateState = synchronizedState(4L);
        final SharedWaypoint hostile = new SharedWaypoint(
            ID, PUBLISHER, "Player", "Too far", DimensionId.OVERWORLD,
            30_000_001d, 0d, 0d, 0xFFFFFFFF, Waypoint.Type.NORMAL, false, 1L, 5L
        );
        assertTrue(coordinateState.onUpsert(new UpsertS2C(5L, hostile)).protocolRejected());
        assertEquals(SharedWaypointClientState.State.UNSUPPORTED, coordinateState.view().state());

        final SharedWaypointClientState colorState = synchronizedState(4L);
        final SharedWaypoint transparent = new SharedWaypoint(
            ID, PUBLISHER, "Player", "Transparent", DimensionId.OVERWORLD,
            0d, 0d, 0d, 0x0055AAFF, Waypoint.Type.NORMAL, false, 1L, 5L
        );
        assertTrue(colorState.onUpsert(new UpsertS2C(5L, transparent)).protocolRejected());
        assertEquals(SharedWaypointClientState.State.UNSUPPORTED, colorState.view().state());

        final SharedWaypointClientState formattedNameState = synchronizedState(4L);
        final SharedWaypoint formattedName = new SharedWaypoint(
            ID, PUBLISHER, "Player", "\u00a7kHidden", DimensionId.OVERWORLD,
            0d, 0d, 0d, 0xFF55AAFF, Waypoint.Type.NORMAL, false, 1L, 5L
        );
        assertTrue(formattedNameState.onUpsert(new UpsertS2C(5L, formattedName)).protocolRejected());
        assertEquals(SharedWaypointClientState.State.UNSUPPORTED, formattedNameState.view().state());
    }

    private static SharedWaypointClientState state() {
        return new SharedWaypointClientState();
    }

    private static SharedWaypointClientState enabledHandshake(final SharedWaypointClientState state) {
        state.beginConnection(true);
        return state;
    }

    private static SharedWaypointClientState synchronizedState(final long revision) {
        final SharedWaypointClientState state = enabledHandshake(state());
        state.onStatus(status(true, revision, false, 32, 8));
        state.onSnapshot(new SnapshotS2C(
            revision,
            false,
            List.of(waypoint(ID, "Spawn", Math.max(0L, revision - 1L)))
        ));
        return state;
    }

    private static StatusS2C status(
        final boolean enabled,
        final long revision,
        final boolean operator,
        final int maxWorld,
        final int maxPlayer
    ) {
        return new StatusS2C(
            SharedWaypointProto.PROTO_MAJOR,
            SharedWaypointProto.PROTO_MINOR,
            true,
            enabled,
            operator,
            "world-id",
            revision,
            maxWorld,
            maxPlayer
        );
    }

    private static SharedWaypoint waypoint(final UUID id, final String name, final long revision) {
        return new SharedWaypoint(
            id,
            PUBLISHER,
            "Player",
            name,
            DimensionId.OVERWORLD,
            1.5d,
            64d,
            -3.25d,
            0xFF55AAFF,
            Waypoint.Type.NORMAL,
            false,
            1L,
            revision
        );
    }
}
