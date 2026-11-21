package cn.net.rms.confluxmap.core.waypoint.list;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Behavioral contract every {@link WaypointListModel} must satisfy, run
 * against both the local and the shared adapter. A feature added to this
 * contract has to land on both tabs; deliberate per-side differences (delete
 * semantics, denial reason keys) are expressed through the model's own
 * declarations instead of branching assertions.
 */
abstract class WaypointListModelContract {
    protected static final DimensionId DIMENSION = DimensionId.OVERWORLD;

    protected abstract WaypointListModel model();

    /** Creates a real group through the model and returns its ref as the model reports it. */
    protected abstract GroupRef seedGroup(String name);

    /** Creates an ungrouped waypoint through the model and returns its row as the model reports it. */
    protected abstract WaypointRow seedWaypoint(String name);

    /**
     * The model state in which {@link #unmovableRow()} must be denied: the
     * same model for per-row policies (shared), or a read-only variant for
     * store-wide policies (local).
     */
    protected abstract WaypointListModel denialModel();

    /** A row {@link #denialModel()} must refuse to move; the contract asserts denial, not the reason. */
    protected abstract WaypointRow unmovableRow();

    @Test
    void ungroupedBucketExistsAndItsFoldStatePersists() {
        assertEquals(GroupRef.UNGROUPED_KEY, model().ungrouped().key());
        assertFalse(model().ungrouped().collapsed());

        final long before = model().stateVersion();
        assertTrue(model().toggleGroupCollapsed(model().ungrouped()));
        assertTrue(model().ungrouped().collapsed());
        assertNotEquals(before, model().stateVersion());
    }

    @Test
    void realGroupsCarryFoldStateToo() {
        final GroupRef group = seedGroup("Bases");
        assertFalse(group.collapsed());

        assertTrue(model().toggleGroupCollapsed(group));
        assertTrue(model().groupByKey(group.key()).collapsed());
    }

    @Test
    void movingRowsIntoAGroupAndBackToUngroupedUpdatesRowKeys() {
        final GroupRef group = seedGroup("Bases");
        final WaypointRow first = seedWaypoint("Home");
        final WaypointRow second = seedWaypoint("Camp");

        assertTrue(model().moveWaypoints(List.of(first, second), group));
        assertEquals(group.key(), rowByName("Home").groupKey());
        assertEquals(group.key(), rowByName("Camp").groupKey());
        assertEquals(2, model().memberCount(group.key()));

        assertTrue(model().moveWaypoints(List.of(rowByName("Home")), model().ungrouped()));
        assertEquals(GroupRef.UNGROUPED_KEY, rowByName("Home").groupKey());
        assertEquals(1, model().memberCount(group.key()));
    }

    @Test
    void hiddenToggleFlipsTheEyeStateAndAdvancesTheVersion() {
        final WaypointRow row = seedWaypoint("Home");
        assertFalse(row.hidden());

        final long before = model().stateVersion();
        assertTrue(model().toggleWaypointHidden(row));
        assertTrue(rowByName("Home").hidden());
        assertNotEquals(before, model().stateVersion());

        assertTrue(model().toggleWaypointHidden(rowByName("Home")));
        assertFalse(rowByName("Home").hidden());
    }

    @Test
    void createUpdateAndDeleteWaypointsThroughTheDraft() {
        assertTrue(model().createWaypoint(draft("Port", GroupRef.UNGROUPED_KEY)));
        final WaypointRow created = rowByName("Port");
        assertNotNull(created);

        assertTrue(model().updateWaypoint(created, draft("Harbor", GroupRef.UNGROUPED_KEY)));
        assertNotNull(rowByName("Harbor"));

        assertTrue(model().deleteWaypoint(rowByName("Harbor")));
        assertEquals(null, rowByName("Harbor"));
    }

    @Test
    void unmovableRowsAreDeniedWithoutChangingAnything() {
        final WaypointListModel denialModel = denialModel();
        final WaypointRow unmovable = unmovableRow();
        final GroupRef group = seedGroup("Bases");

        assertFalse(denialModel.canMoveWaypoint(unmovable).granted());
        assertNotNull(denialModel.canMoveWaypoint(unmovable).reasonKey());
        assertFalse(denialModel.moveWaypoints(List.of(unmovable), group));
    }

    @Test
    void deletingAGroupFollowsTheDeclaredMemberBehavior() {
        final GroupRef group = seedGroup("Bases");
        final WaypointRow member = seedWaypoint("Home");
        assertTrue(model().moveWaypoints(List.of(member), group));

        assertTrue(model().deleteGroup(group));
        assertEquals(null, model().groupByKey(group.key()));
        if (model().groupDeleteBehavior() == WaypointListModel.GroupDeleteBehavior.UNGROUP_MEMBERS) {
            assertEquals(GroupRef.UNGROUPED_KEY, rowByName("Home").groupKey());
        } else {
            assertEquals(null, rowByName("Home"));
        }
    }

    protected static EditDraft draft(final String name, final String groupKey) {
        return new EditDraft(
            name, DIMENSION, 1d, 64d, 2d, 0xFF33AA66, Waypoint.Type.NORMAL,
            "", "", "", "", false, null, groupKey
        );
    }

    private WaypointRow rowByName(final String name) {
        for (final WaypointRow row : model().waypoints()) {
            if (row.name().equals(name)) {
                return row;
            }
        }
        return null;
    }
}
