package cn.net.rms.confluxmap.core.waypoint.list;

import cn.net.rms.confluxmap.core.waypoint.SiblingWaypoint;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointDescription;
import cn.net.rms.confluxmap.core.waypoint.WaypointMarkerStyle;
import cn.net.rms.confluxmap.core.waypoint.WaypointSet;
import cn.net.rms.confluxmap.core.waypoint.WaypointStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Local-store backed {@link WaypointListModel}. Every permission reduces to
 * store writability; mutations delegate to {@link WaypointStore}, whose
 * batch move is a single transaction. Collapse state lives with the set in
 * the world's waypoint file, exactly as before the template existed.
 *
 * <p>The store supplier follows session swaps ({@code WaypointService::current});
 * a null store renders as an empty read-only model until the next session.
 */
public final class LocalWaypointListModel implements WaypointListModel {
    private static final String KEY_READ_ONLY = "confluxmap.screen.waypoints.read_only";
    private static final String KEY_GUEST = "confluxmap.screen.waypoints.cross_world_read_only";
    private static final String KEY_DEFAULT_SET_PROTECTED = "confluxmap.screen.waypoints.set_default_protected";
    private static final String KEY_UNASSIGNED = "confluxmap.screen.waypoints.set_unassigned";
    private static final String KEY_GUEST_ORIGIN = "confluxmap.screen.waypoints.cross_world_origin";

    private final Supplier<WaypointStore> storeSupplier;
    private final Supplier<List<SiblingWaypoint>> siblingsSupplier;
    private final BooleanSupplier guestsVisible;
    private final Localizer localizer;

    public LocalWaypointListModel(
        final Supplier<WaypointStore> storeSupplier,
        final Supplier<List<SiblingWaypoint>> siblingsSupplier,
        final BooleanSupplier guestsVisible,
        final Localizer localizer
    ) {
        this.storeSupplier = Objects.requireNonNull(storeSupplier, "storeSupplier");
        this.siblingsSupplier = Objects.requireNonNull(siblingsSupplier, "siblingsSupplier");
        this.guestsVisible = Objects.requireNonNull(guestsVisible, "guestsVisible");
        this.localizer = Objects.requireNonNull(localizer, "localizer");
    }

    @Override
    public List<WaypointRow> waypoints() {
        final WaypointStore store = storeSupplier.get();
        if (store == null) {
            return List.of();
        }
        final List<WaypointRow> rows = new ArrayList<>(store.size());
        for (final Waypoint waypoint : store.list()) {
            rows.add(new WaypointRow(
                waypoint.id,
                waypoint.name,
                setDisplay(waypoint.group),
                waypoint.dimensionId,
                waypoint.x,
                waypoint.y,
                waypoint.z,
                waypoint.colorArgb,
                waypoint.type,
                waypoint.iconItemId,
                waypoint.markerLabel,
                waypoint.description,
                waypoint.imageId,
                waypoint.group,
                !waypoint.visible,
                false,
                waypoint.crossDimensionVisible,
                ""
            ));
        }
        return rows;
    }

    @Override
    public List<WaypointRow> guestWaypoints() {
        if (!guestsVisible.getAsBoolean()) {
            return List.of();
        }
        final List<WaypointRow> rows = new ArrayList<>();
        for (final SiblingWaypoint sibling : siblingsSupplier.get()) {
            final Waypoint waypoint = sibling.waypoint();
            rows.add(new WaypointRow(
                waypoint.id,
                waypoint.name,
                localizer.format(KEY_GUEST_ORIGIN, sibling.worldLabel()),
                waypoint.dimensionId,
                waypoint.x,
                waypoint.y,
                waypoint.z,
                waypoint.colorArgb,
                waypoint.type,
                waypoint.iconItemId,
                waypoint.markerLabel,
                waypoint.description,
                waypoint.imageId,
                GroupRef.UNGROUPED_KEY,
                false,
                true,
                waypoint.crossDimensionVisible,
                sibling.worldLabel()
            ));
        }
        return rows;
    }

    @Override
    public List<GroupRef> groups() {
        final WaypointStore store = storeSupplier.get();
        if (store == null) {
            return List.of();
        }
        final List<GroupRef> groups = new ArrayList<>();
        for (final WaypointSet set : store.sets()) {
            if (!set.isDefault()) {
                groups.add(new GroupRef(set.name(), set.name(), set.collapsed()));
            }
        }
        return groups;
    }

    @Override
    public GroupRef ungrouped() {
        final WaypointStore store = storeSupplier.get();
        if (store == null) {
            return GroupRef.ungrouped(false);
        }
        for (final WaypointSet set : store.sets()) {
            if (set.isDefault()) {
                return GroupRef.ungrouped(set.collapsed());
            }
        }
        return GroupRef.ungrouped(false);
    }

    @Override
    public long stateVersion() {
        final WaypointStore store = storeSupplier.get();
        return store == null ? 0L : 31L * System.identityHashCode(store) + store.revision();
    }

    @Override
    public boolean syncPending() {
        return false;
    }

    @Override
    public Permission canCreateWaypoint() {
        return writable();
    }

    @Override
    public Permission canEditWaypoint(final WaypointRow row) {
        return ownedWritable(row);
    }

    @Override
    public Permission canDeleteWaypoint(final WaypointRow row) {
        return ownedWritable(row);
    }

    @Override
    public Permission canMoveWaypoint(final WaypointRow row) {
        return ownedWritable(row);
    }

    @Override
    public Permission canToggleHidden(final WaypointRow row) {
        return ownedWritable(row);
    }

    @Override
    public Permission canCreateGroup() {
        return writable();
    }

    @Override
    public Permission canRenameGroup(final GroupRef group) {
        if (group.isUngrouped()) {
            return Permission.denied(KEY_DEFAULT_SET_PROTECTED);
        }
        return writable();
    }

    @Override
    public Permission canDeleteGroup(final GroupRef group) {
        if (group.isUngrouped()) {
            return Permission.denied(KEY_DEFAULT_SET_PROTECTED);
        }
        return writable();
    }

    @Override
    public boolean supportsBatchMove() {
        return true;
    }

    @Override
    public boolean supportsInFormGroupSelection() {
        return true;
    }

    @Override
    public GroupDeleteBehavior groupDeleteBehavior() {
        return GroupDeleteBehavior.CASCADE_MEMBERS;
    }

    @Override
    public boolean createWaypoint(final EditDraft draft) {
        final WaypointStore store = storeSupplier.get();
        if (!canCreateWaypoint().granted() || store == null) {
            return false;
        }
        final Waypoint waypoint = new Waypoint(
            UUID.randomUUID(),
            draft.name(),
            draft.dimensionId(),
            draft.x(),
            draft.y(),
            draft.z(),
            draft.colorArgb(),
            draft.groupKey(),
            true,
            draft.crossDimensionVisible(),
            draft.type(),
            System.currentTimeMillis()
        );
        waypoint.crossDimensionY = draft.crossDimensionY();
        waypoint.iconItemId = WaypointMarkerStyle.iconItemId(draft.iconItemId());
        waypoint.markerLabel = WaypointMarkerStyle.markerLabel(draft.markerLabel());
        waypoint.description = WaypointDescription.normalize(draft.description());
        waypoint.imageId = WaypointDescription.normalizeImageId(draft.imageId());
        store.add(waypoint);
        return true;
    }

    @Override
    public boolean updateWaypoint(final WaypointRow row, final EditDraft draft) {
        final WaypointStore store = storeSupplier.get();
        final Waypoint existing = store == null ? null : findWaypoint(store, row.id());
        if (existing == null || !canEditWaypoint(row).granted()) {
            return false;
        }
        existing.name = draft.name();
        existing.dimensionId = draft.dimensionId();
        existing.x = draft.x();
        existing.y = draft.y();
        existing.z = draft.z();
        existing.colorArgb = draft.colorArgb();
        existing.group = draft.groupKey();
        existing.crossDimensionVisible = draft.crossDimensionVisible();
        existing.crossDimensionY = draft.crossDimensionY();
        existing.type = draft.type();
        existing.iconItemId = WaypointMarkerStyle.iconItemId(draft.iconItemId());
        existing.markerLabel = WaypointMarkerStyle.markerLabel(draft.markerLabel());
        existing.description = WaypointDescription.normalize(draft.description());
        existing.imageId = WaypointDescription.normalizeImageId(draft.imageId());
        store.update(existing);
        return true;
    }

    @Override
    public boolean deleteWaypoint(final WaypointRow row) {
        final WaypointStore store = storeSupplier.get();
        if (store == null || !canDeleteWaypoint(row).granted()) {
            return false;
        }
        store.remove(row.id());
        return true;
    }

    @Override
    public boolean moveWaypoints(final List<WaypointRow> rows, final GroupRef target) {
        final WaypointStore store = storeSupplier.get();
        if (store == null || target == null || rows.isEmpty()) {
            return false;
        }
        for (final WaypointRow row : rows) {
            if (!canMoveWaypoint(row).granted()) {
                return false;
            }
        }
        final List<UUID> ids = new ArrayList<>(rows.size());
        for (final WaypointRow row : rows) {
            ids.add(row.id());
        }
        final WaypointStore.BatchMoveResult result = store.moveToSet(ids, target.key());
        return result.result() == WaypointStore.MutationResult.APPLIED
            || result.result() == WaypointStore.MutationResult.NO_CHANGE;
    }

    @Override
    public boolean toggleWaypointHidden(final WaypointRow row) {
        final WaypointStore store = storeSupplier.get();
        final Waypoint existing = store == null ? null : findWaypoint(store, row.id());
        if (existing == null || !canToggleHidden(row).granted()) {
            return false;
        }
        existing.visible = !existing.visible;
        store.update(existing);
        return true;
    }

    @Override
    public boolean toggleGroupCollapsed(final GroupRef group) {
        final WaypointStore store = storeSupplier.get();
        if (store == null) {
            return false;
        }
        final WaypointStore.MutationResult result =
            store.setCollapsed(group.key(), !group.collapsed());
        return result == WaypointStore.MutationResult.APPLIED
            || result == WaypointStore.MutationResult.NO_CHANGE;
    }

    @Override
    public boolean createGroup(final String name) {
        final WaypointStore store = storeSupplier.get();
        if (store == null || !canCreateGroup().granted()) {
            return false;
        }
        return store.createSet(name) == WaypointStore.MutationResult.APPLIED;
    }

    @Override
    public boolean renameGroup(final GroupRef group, final String name) {
        final WaypointStore store = storeSupplier.get();
        if (store == null || !canRenameGroup(group).granted()) {
            return false;
        }
        final WaypointStore.MutationResult result = store.renameSet(group.key(), name);
        return result == WaypointStore.MutationResult.APPLIED
            || result == WaypointStore.MutationResult.NO_CHANGE;
    }

    @Override
    public boolean deleteGroup(final GroupRef group) {
        final WaypointStore store = storeSupplier.get();
        if (store == null || !canDeleteGroup(group).granted()) {
            return false;
        }
        return store.deleteSet(group.key()).result() == WaypointStore.MutationResult.APPLIED;
    }

    private Permission writable() {
        final WaypointStore store = storeSupplier.get();
        return store != null && store.persistenceWritable()
            ? Permission.allow()
            : Permission.denied(KEY_READ_ONLY);
    }

    private Permission ownedWritable(final WaypointRow row) {
        if (row.guest()) {
            return Permission.denied(KEY_GUEST);
        }
        return writable();
    }

    private static Waypoint findWaypoint(final WaypointStore store, final UUID id) {
        for (final Waypoint waypoint : store.list()) {
            if (waypoint.id.equals(id)) {
                return waypoint;
            }
        }
        return null;
    }

    private String setDisplay(final String setName) {
        return setName.isEmpty() ? localizer.format(KEY_UNASSIGNED) : setName;
    }
}
