package cn.net.rms.confluxmap.core.waypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WaypointStoreTest {
    private static final WorldIdentity WORLD = new WorldIdentity("server", "world");

    @Test
    void legacyGroupsBecomePersistentSetsWithoutChangingMembership() {
        final Waypoint home = waypoint("Home", "Bases", 1.0);
        final WaypointStore store = new WaypointStore(WORLD, List.of(home));

        assertEquals(List.of(WaypointSet.DEFAULT, new WaypointSet("Bases")), store.sets());
        assertEquals("Bases", store.list().get(0).group);

        final Waypoint edited = store.list().get(0);
        edited.group = "Mining";
        store.update(edited);

        // update() never creates sets; an unknown group falls back to the entry's current set.
        assertEquals(List.of(WaypointSet.DEFAULT, new WaypointSet("Bases")), store.sets());
        assertEquals("Bases", store.list().get(0).group);
    }

    @Test
    void staleEditCopyCannotResurrectARenamedSet() {
        final Waypoint home = waypoint("Home", "Bases", 1.0);
        final WaypointStore store = new WaypointStore(WORLD, List.of(home));
        final Waypoint staleCopy = store.list().get(0);

        assertEquals(WaypointStore.MutationResult.APPLIED, store.renameSet("Bases", "Mining"));
        staleCopy.name = "Edited";
        store.update(staleCopy);

        assertEquals(List.of(WaypointSet.DEFAULT, new WaypointSet("Mining")), store.sets());
        assertEquals("Mining", store.list().get(0).group);
        assertEquals("Edited", store.list().get(0).name);
    }

    @Test
    void createRenameAndDeleteSetCascadesToMemberWaypoints() {
        final Waypoint first = waypoint("First", "", 1.0);
        final Waypoint second = waypoint("Second", "", 2.0);
        final Waypoint untouched = waypoint("Untouched", "", 3.0);
        final WaypointStore store = new WaypointStore(WORLD, List.of(first, second, untouched));
        final AtomicInteger notifications = new AtomicInteger();
        store.addListener(ignored -> notifications.incrementAndGet());

        assertEquals(WaypointStore.MutationResult.APPLIED, store.createSet("  Mining  "));
        assertEquals(WaypointStore.MutationResult.APPLIED, store.assignToSet(first.id, "Mining"));
        assertEquals(WaypointStore.MutationResult.APPLIED, store.assignToSet(second.id, "Mining"));
        assertEquals(WaypointStore.MutationResult.APPLIED, store.renameSet("Mining", "Ores"));
        assertEquals(List.of("Ores", "Ores", ""), groups(store));
        assertEquals(2, store.waypointCount("Ores"));

        final WaypointStore.DeleteSetResult deleted = store.deleteSet("Ores");

        assertEquals(WaypointStore.MutationResult.APPLIED, deleted.result());
        assertEquals(2, deleted.deletedWaypoints());
        assertEquals(List.of("Untouched"), names(store));
        assertEquals(List.of(WaypointSet.DEFAULT), store.sets());
        assertEquals(5, notifications.get());
    }

    @Test
    void defaultSetCannotBeRenamedOrDeleted() {
        final WaypointStore store = new WaypointStore(WORLD, List.of());

        assertEquals(
            WaypointStore.MutationResult.DEFAULT_SET_PROTECTED,
            store.renameSet(WaypointSet.DEFAULT_NAME, "Other")
        );
        assertEquals(
            WaypointStore.MutationResult.DEFAULT_SET_PROTECTED,
            store.deleteSet(WaypointSet.DEFAULT_NAME).result()
        );
        assertEquals(WaypointStore.MutationResult.INVALID_NAME, store.createSet("   "));
    }

    @Test
    void batchMoveIsAtomicAndReportsOnlyChangedWaypoints() {
        final Waypoint first = waypoint("First", "", 1.0);
        final Waypoint second = waypoint("Second", "Target", 2.0);
        final WaypointStore store = new WaypointStore(WORLD, List.of(first, second));
        final AtomicInteger notifications = new AtomicInteger();
        store.addListener(ignored -> notifications.incrementAndGet());

        final WaypointStore.BatchMoveResult moved = store.moveToSet(
            List.of(first.id, second.id, first.id), "Target"
        );

        assertEquals(WaypointStore.MutationResult.APPLIED, moved.result());
        assertEquals(1, moved.movedWaypoints());
        assertEquals(List.of("Target", "Target"), groups(store));
        assertEquals(1, notifications.get());
        assertEquals(1L, store.revision());

        final WaypointStore.BatchMoveResult missingWaypoint = store.moveToSet(
            List.of(first.id, UUID.randomUUID()), WaypointSet.DEFAULT_NAME
        );
        assertEquals(WaypointStore.MutationResult.WAYPOINT_NOT_FOUND, missingWaypoint.result());
        assertEquals(0, missingWaypoint.movedWaypoints());
        assertEquals(List.of("Target", "Target"), groups(store));
        assertEquals(1, notifications.get());

        final WaypointStore.BatchMoveResult missingSet = store.moveToSet(
            List.of(first.id, second.id), "Missing"
        );
        assertEquals(WaypointStore.MutationResult.SET_NOT_FOUND, missingSet.result());
        assertEquals(0, missingSet.movedWaypoints());
        assertEquals(List.of("Target", "Target"), groups(store));
        assertEquals(1, notifications.get());
    }

    @Test
    void batchMoveRejectsMalformedRequestsAndDoesNotNotifyForNoOps() {
        final Waypoint waypoint = waypoint("Already there", "Target", 1.0);
        final WaypointStore store = new WaypointStore(WORLD, List.of(waypoint));
        final AtomicInteger notifications = new AtomicInteger();
        store.addListener(ignored -> notifications.incrementAndGet());

        assertEquals(
            WaypointStore.MutationResult.INVALID_REQUEST,
            store.moveToSet(null, "Target").result()
        );
        assertEquals(
            WaypointStore.MutationResult.INVALID_REQUEST,
            store.moveToSet(Collections.singletonList(null), "Target").result()
        );
        assertEquals(
            WaypointStore.MutationResult.NO_CHANGE,
            store.moveToSet(List.of(), "Target").result()
        );
        assertEquals(
            WaypointStore.MutationResult.NO_CHANGE,
            store.moveToSet(List.of(waypoint.id), "Target").result()
        );
        assertEquals(List.of("Target"), groups(store));
        assertEquals(0, notifications.get());
        assertEquals(0L, store.revision());
    }

    @Test
    void setCollapsedNotifiesOncePersistsThroughStateAndSurvivesRename() {
        final Waypoint home = waypoint("Home", "Bases", 1.0);
        final WaypointStore store = new WaypointStore(WORLD, List.of(home));
        final AtomicInteger notifications = new AtomicInteger();
        store.addListener(ignored -> notifications.incrementAndGet());

        assertEquals(WaypointStore.MutationResult.SET_NOT_FOUND, store.setCollapsed("Missing", true));
        assertEquals(WaypointStore.MutationResult.SET_NOT_FOUND, store.setCollapsed(null, true));
        assertEquals(WaypointStore.MutationResult.NO_CHANGE, store.setCollapsed("Bases", false));
        assertEquals(0, notifications.get());

        assertEquals(WaypointStore.MutationResult.APPLIED, store.setCollapsed("Bases", true));
        assertEquals(WaypointStore.MutationResult.APPLIED, store.setCollapsed(WaypointSet.DEFAULT_NAME, true));

        assertEquals(2, notifications.get());
        assertEquals(
            List.of(new WaypointSet("", true), new WaypointSet("Bases", true)),
            store.state().sets()
        );

        assertEquals(WaypointStore.MutationResult.APPLIED, store.renameSet("Bases", "Mining"));

        assertEquals(
            List.of(new WaypointSet("", true), new WaypointSet("Mining", true)),
            store.sets()
        );
    }

    @Test
    void readOnlyFutureSchemaStateRejectsEveryMutation() {
        final Waypoint original = waypoint("Original", "", 1.0);
        final WaypointStore store = new WaypointStore(
            WORLD, new WaypointStore.State(List.of(), List.of(original), false)
        );

        store.add(waypoint("Added", "", 2.0));
        final Waypoint edited = original.copy();
        edited.name = "Edited";
        store.update(edited);
        store.remove(original.id);

        assertEquals(WaypointStore.MutationResult.READ_ONLY, store.createSet("Other"));
        assertEquals(WaypointStore.MutationResult.READ_ONLY, store.renameSet("Missing", "Other"));
        assertEquals(WaypointStore.MutationResult.READ_ONLY, store.deleteSet("Missing").result());
        assertEquals(WaypointStore.MutationResult.READ_ONLY, store.setCollapsed(WaypointSet.DEFAULT_NAME, true));
        assertEquals(
            WaypointStore.MutationResult.READ_ONLY,
            store.moveToSet(List.of(original.id), WaypointSet.DEFAULT_NAME).result()
        );
        assertEquals(List.of("Original"), names(store));
        assertFalse(store.persistenceWritable());
    }

    @Test
    void temporaryWaypointsStayInMemoryButNeverEnterThePersistenceSnapshot() {
        final Waypoint home = waypoint("Home", "", 1.0);
        final WaypointStore store = new WaypointStore(WORLD, List.of(home));
        final Waypoint stop = waypoint("Stop", "", 2.0);
        stop.type = Waypoint.Type.TEMPORARY;
        store.add(stop);

        assertEquals(List.of("Home", "Stop"), names(store));
        assertEquals(List.of("Home"), stateNames(store));
    }

    @Test
    void convertingBetweenNormalAndTemporaryMovesTheWaypointAcrossThePersistenceBoundary() {
        final Waypoint home = waypoint("Home", "", 1.0);
        final WaypointStore store = new WaypointStore(WORLD, List.of(home));

        final Waypoint toTemporary = store.list().get(0);
        toTemporary.type = Waypoint.Type.TEMPORARY;
        store.update(toTemporary);
        assertTrue(store.state().waypoints().isEmpty());

        final Waypoint toNormal = store.list().get(0);
        toNormal.type = Waypoint.Type.NORMAL;
        store.update(toNormal);
        assertEquals(List.of("Home"), stateNames(store));
    }

    @Test
    void temporaryWaypointsInAnInitialListNeverMaterialize() {
        final Waypoint stop = waypoint("Stop", "Bases", 1.0);
        stop.type = Waypoint.Type.TEMPORARY;
        final WaypointStore store = new WaypointStore(WORLD, List.of(stop));

        assertTrue(store.list().isEmpty());
    }

    @Test
    void dataViewSnapshotContainsImmutableValuesOnly() {
        final Waypoint waypoint = waypoint("Home", "Bases", 10.0);
        final WaypointStore store = new WaypointStore(WORLD, List.of(waypoint));

        final WaypointDataView.Snapshot snapshot = store.dataSnapshot();

        assertEquals(WORLD, snapshot.world());
        assertEquals(List.of(WaypointSet.DEFAULT, new WaypointSet("Bases")), snapshot.sets());
        assertEquals("Home", snapshot.waypoints().get(0).name());
        assertEquals("Bases", snapshot.waypoints().get(0).setName());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.sets().add(new WaypointSet("Other")));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.waypoints().clear());
    }

    private static Waypoint waypoint(final String name, final String group, final double x) {
        return Waypoint.create(
            name, DimensionId.OVERWORLD, x, 64.0, -x, 0xFF336699, group, Waypoint.Type.NORMAL
        );
    }

    private static List<String> groups(final WaypointStore store) {
        return store.list().stream().map(waypoint -> waypoint.group).toList();
    }

    private static List<String> names(final WaypointStore store) {
        return store.list().stream().map(waypoint -> waypoint.name).toList();
    }

    private static List<String> stateNames(final WaypointStore store) {
        return store.state().waypoints().stream().map(waypoint -> waypoint.name).toList();
    }
}
