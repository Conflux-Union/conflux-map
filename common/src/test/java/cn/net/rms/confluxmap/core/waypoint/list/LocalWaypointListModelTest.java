package cn.net.rms.confluxmap.core.waypoint.list;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.waypoint.SiblingWaypoint;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointSet;
import cn.net.rms.confluxmap.core.waypoint.WaypointStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LocalWaypointListModelTest {
    private static final WorldIdentity WORLD = new WorldIdentity("singleplayer", "world");

    private final WaypointStore store = new WaypointStore(WORLD, new WaypointStore.State(
        List.of(new WaypointSet("Bases")),
        List.of(
            waypoint("Home", "Bases", true),
            waypoint("Camp", "", false)
        ),
        true
    ));

    private final LocalWaypointListModel model = new LocalWaypointListModel(
        () -> store,
        () -> List.of(new SiblingWaypoint(waypoint("Twin", "", true), "Twin world")),
        () -> true,
        LocalWaypointListModelTest::localize
    );

    private static String localize(final String key, final Object... args) {
        return "[" + key + (args.length == 0 ? "" : ":" + String.join(",", String.valueOf(args[0]))) + "]";
    }

    @Test
    void rowsProjectStoreFieldsWithLocalizedUngroupedSecondary() {
        final WaypointRow home = model.rowById(idOf("Home"));
        assertEquals("Bases", home.groupKey());
        assertEquals("Bases", home.secondaryText());
        assertFalse(home.hidden());
        assertFalse(home.guest());

        final WaypointRow camp = model.rowById(idOf("Camp"));
        assertEquals(GroupRef.UNGROUPED_KEY, camp.groupKey());
        assertEquals("[confluxmap.screen.waypoints.set_unassigned]", camp.secondaryText());
        assertTrue(camp.hidden());
    }

    @Test
    void guestRowsCarryOriginLabelAndStayOutsideOwnWaypoints() {
        final List<WaypointRow> guests = model.guestWaypoints();
        assertEquals(1, guests.size());
        assertTrue(guests.get(0).guest());
        assertEquals("Twin world", guests.get(0).worldLabel());
        assertEquals(
            "[confluxmap.screen.waypoints.cross_world_origin:Twin world]",
            guests.get(0).secondaryText()
        );
        assertFalse(model.waypoints().contains(guests.get(0)));

        final LocalWaypointListModel hidden = new LocalWaypointListModel(
            () -> store, () -> List.of(), () -> false, (key, args) -> key
        );
        assertTrue(hidden.guestWaypoints().isEmpty());
    }

    @Test
    void groupsExcludeUngroupedBucketWhichCarriesItsOwnFoldState() {
        assertEquals(List.of(new GroupRef("Bases", "Bases", false)), model.groups());
        assertEquals(GroupRef.ungrouped(false), model.ungrouped());
        assertEquals(
            new GroupRef("Bases", "Bases", false),
            model.groupByKey("Bases")
        );

        assertTrue(model.toggleGroupCollapsed(model.ungrouped()));
        assertTrue(store.sets().contains(new WaypointSet("", true)));
        assertTrue(model.ungrouped().collapsed());
    }

    @Test
    void moveWaypointsIsOneStoreTransactionForGroupAndUngrouped() {
        assertTrue(model.moveWaypoints(
            List.of(model.rowById(idOf("Home")), model.rowById(idOf("Camp"))),
            model.groupByKey("Bases")
        ));
        assertEquals("Bases", groupOf("Camp"));

        assertTrue(model.moveWaypoints(
            List.of(model.rowById(idOf("Home"))), model.ungrouped()
        ));
        assertEquals("", groupOf("Home"));
    }

    @Test
    void toggleHiddenFlipsTheStoredVisibilityFlag() {
        final WaypointRow home = model.rowById(idOf("Home"));
        assertTrue(model.toggleWaypointHidden(home));
        assertFalse(store.list().stream()
            .filter(waypoint -> waypoint.name.equals("Home"))
            .findFirst().orElseThrow().visible);
        assertTrue(model.rowById(idOf("Home")).hidden());
    }

    @Test
    void deleteGroupCascadesMembers() {
        assertEquals(WaypointListModel.GroupDeleteBehavior.CASCADE_MEMBERS, model.groupDeleteBehavior());
        assertEquals(1, model.memberCount("Bases"));
        assertTrue(model.deleteGroup(model.groupByKey("Bases")));
        assertTrue(store.sets().stream().noneMatch(set -> set.name().equals("Bases")));
        assertTrue(store.list().stream().noneMatch(waypoint -> waypoint.name.equals("Home")));
    }

    @Test
    void createWaypointAppliesDraftIncludingGroupAndMarkerStyle() {
        assertTrue(model.createWaypoint(new EditDraft(
            "New", DimensionId.OVERWORLD, 1, 2, 3, 0xFF00FF00, Waypoint.Type.NORMAL,
            "minecraft:compass", "NN", true, 64.0, "Bases"
        )));
        final Waypoint created = store.list().stream()
            .filter(waypoint -> waypoint.name.equals("New"))
            .findFirst().orElseThrow();
        assertEquals("Bases", created.group);
        assertEquals("minecraft:compass", created.iconItemId);
        assertEquals("NN", created.markerLabel);
        assertTrue(created.visible);
        assertEquals(64.0, created.crossDimensionY);
    }

    @Test
    void temporaryDraftCreatesInMemoryOnlyRowAndTypeFlipsCrossTheBoundary() {
        assertTrue(model.createWaypoint(new EditDraft(
            "Pin", DimensionId.OVERWORLD, 1, 2, 3, 0xFF00FF00, Waypoint.Type.TEMPORARY,
            "", "", false, null, ""
        )));
        assertEquals(Waypoint.Type.TEMPORARY, model.rowById(idOf("Pin")).type());
        assertTrue(store.list().stream().anyMatch(waypoint -> waypoint.name.equals("Pin")));
        assertTrue(store.state().waypoints().stream().noneMatch(waypoint -> waypoint.name.equals("Pin")));

        assertTrue(model.updateWaypoint(model.rowById(idOf("Pin")), new EditDraft(
            "Pin", DimensionId.OVERWORLD, 1, 2, 3, 0xFF00FF00, Waypoint.Type.NORMAL,
            "", "", false, null, ""
        )));
        assertTrue(store.state().waypoints().stream().anyMatch(waypoint -> waypoint.name.equals("Pin")));
    }

    @Test
    void readOnlyStoreDeniesEverythingWithReasonKey() {
        final Waypoint original = waypoint("Locked", "", true);
        final WaypointStore readOnly = new WaypointStore(WORLD, new WaypointStore.State(
            List.of(), List.of(original), false
        ));
        final LocalWaypointListModel model = new LocalWaypointListModel(
            () -> readOnly, List::of, () -> false, (key, args) -> key
        );

        assertFalse(model.canCreateWaypoint().granted());
        assertEquals(
            "confluxmap.screen.waypoints.read_only",
            model.canEditWaypoint(model.rowById(original.id)).reasonKey()
        );
        assertFalse(model.canToggleHidden(model.rowById(original.id)).granted());
        assertFalse(model.canCreateGroup().granted());
        assertFalse(model.moveWaypoints(
            List.of(model.rowById(original.id)), model.ungrouped()
        ));
        assertFalse(model.createWaypoint(new EditDraft(
            "X", DimensionId.OVERWORLD, 0, 0, 0, 0xFFFFFFFF,
            Waypoint.Type.NORMAL, "", "", false, null, ""
        )));
    }

    @Test
    void guestRowsCannotBeMutated() {
        final WaypointRow guest = model.guestWaypoints().get(0);
        assertFalse(model.canEditWaypoint(guest).granted());
        assertEquals(
            "confluxmap.screen.waypoints.cross_world_read_only",
            model.canDeleteWaypoint(guest).reasonKey()
        );
    }

    @Test
    void stateVersionAdvancesWithEveryStoreMutation() {
        final long before = model.stateVersion();
        model.toggleGroupCollapsed(model.groupByKey("Bases"));
        assertTrue(model.stateVersion() != before);
    }

    private UUID idOf(final String name) {
        return store.list().stream()
            .filter(waypoint -> waypoint.name.equals(name))
            .findFirst().orElseThrow().id;
    }

    private String groupOf(final String name) {
        return store.list().stream()
            .filter(waypoint -> waypoint.name.equals(name))
            .findFirst().orElseThrow().group;
    }

    private static Waypoint waypoint(final String name, final String group, final boolean visible) {
        return new Waypoint(
            UUID.randomUUID(), name, DimensionId.OVERWORLD, 1, 2, 3,
            0xFFFFFFFF, group, visible, false, Waypoint.Type.NORMAL, 0L
        );
    }
}
