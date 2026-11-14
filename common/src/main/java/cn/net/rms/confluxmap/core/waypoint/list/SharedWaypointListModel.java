package cn.net.rms.confluxmap.core.waypoint.list;

import cn.net.rms.confluxmap.core.net.shared.SharedWaypointClientState;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointProto;
import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import cn.net.rms.confluxmap.core.shared.SharedWaypointGroup;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Shared-server backed {@link WaypointListModel}. Permissions mirror the
 * server policy (operators manage everything; under owner management players
 * manage only what they published) plus the negotiated wire minor; every
 * mutation is dispatched through {@link SharedWaypointMutations} and lands
 * asynchronously as state-machine deltas. Per-player eye and fold state is
 * client-owned {@link WaypointPersonalPreferences}, never protocol data.
 */
public final class SharedWaypointListModel implements WaypointListModel {
    private static final String KEY_DISABLED_BY_SERVER = "confluxmap.shared_waypoints.disabled_by_server";
    private static final String KEY_PUBLIC_UNAVAILABLE = "confluxmap.screen.waypoint.public_unavailable";
    private static final String KEY_OPERATOR_ONLY = "confluxmap.shared_waypoints.operator_only";
    private static final String KEY_OWNER_ONLY = "confluxmap.shared_waypoints.owner_only";
    private static final String KEY_GROUP_OWNER_ONLY = "confluxmap.shared_waypoints.group_owner_only";
    private static final String KEY_SHARED_BY = "confluxmap.screen.waypoints.shared_by";

    private final Supplier<SharedWaypointClientState.View> viewSupplier;
    private final IntSupplier negotiatedMinor;
    private final BooleanSupplier ownerManagementAllowed;
    private final Supplier<UUID> localPlayerId;
    private final WaypointPersonalPreferences preferences;
    private final SharedWaypointMutations mutations;
    private final Localizer localizer;

    public SharedWaypointListModel(
        final Supplier<SharedWaypointClientState.View> viewSupplier,
        final IntSupplier negotiatedMinor,
        final BooleanSupplier ownerManagementAllowed,
        final Supplier<UUID> localPlayerId,
        final WaypointPersonalPreferences preferences,
        final SharedWaypointMutations mutations,
        final Localizer localizer
    ) {
        this.viewSupplier = Objects.requireNonNull(viewSupplier, "viewSupplier");
        this.negotiatedMinor = Objects.requireNonNull(negotiatedMinor, "negotiatedMinor");
        this.ownerManagementAllowed = Objects.requireNonNull(ownerManagementAllowed, "ownerManagementAllowed");
        this.localPlayerId = Objects.requireNonNull(localPlayerId, "localPlayerId");
        this.preferences = Objects.requireNonNull(preferences, "preferences");
        this.mutations = Objects.requireNonNull(mutations, "mutations");
        this.localizer = Objects.requireNonNull(localizer, "localizer");
    }

    /** Mirrors the server rule: operators manage every point; enabled owners manage only theirs. */
    public static boolean canManage(
        final SharedWaypoint waypoint,
        final boolean operator,
        final boolean ownerManagementAllowed,
        final UUID localPlayerId
    ) {
        if (waypoint == null) {
            return false;
        }
        if (operator) {
            return true;
        }
        return ownerManagementAllowed && waypoint.publisherId().equals(localPlayerId);
    }

    /** Mirrors the server rule: operators manage every group, owners only groups they created. */
    public static boolean canManageGroup(
        final SharedWaypointGroup group,
        final boolean operator,
        final boolean ownerManagementAllowed,
        final UUID localPlayerId
    ) {
        if (group == null) {
            return false;
        }
        if (operator) {
            return true;
        }
        return ownerManagementAllowed && group.creatorId().equals(localPlayerId);
    }

    /** Returns the user-facing reason creation is unavailable, or {@code null} when allowed. */
    public static String createDisabledReasonKey(
        final SharedWaypointClientState.State state,
        final boolean synchronizedSnapshot,
        final boolean operator,
        final boolean ownerManagementAllowed
    ) {
        if (state == SharedWaypointClientState.State.SUPPORTED_DISABLED) {
            return KEY_DISABLED_BY_SERVER;
        }
        if (state != SharedWaypointClientState.State.ENABLED || !synchronizedSnapshot) {
            return KEY_PUBLIC_UNAVAILABLE;
        }
        if (!operator && !ownerManagementAllowed) {
            return KEY_OPERATOR_ONLY;
        }
        return null;
    }

    /** Returns the user-facing reason the waypoint cannot be managed, or {@code null} when allowed. */
    public static String managementDisabledReasonKey(
        final SharedWaypointClientState.State state,
        final boolean synchronizedSnapshot,
        final boolean operator,
        final boolean ownerManagementAllowed,
        final SharedWaypoint waypoint,
        final UUID localPlayerId
    ) {
        final String createReasonKey =
            createDisabledReasonKey(state, synchronizedSnapshot, operator, ownerManagementAllowed);
        if (createReasonKey != null || waypoint == null) {
            return createReasonKey == null ? KEY_PUBLIC_UNAVAILABLE : createReasonKey;
        }
        return canManage(waypoint, operator, ownerManagementAllowed, localPlayerId)
            ? null
            : KEY_OWNER_ONLY;
    }

    /** Returns the user-facing reason the group cannot be managed, or {@code null} when allowed. */
    public static String groupManageDisabledReasonKey(
        final SharedWaypointClientState.State state,
        final boolean synchronizedSnapshot,
        final boolean operator,
        final boolean ownerManagementAllowed,
        final SharedWaypointGroup group,
        final UUID localPlayerId
    ) {
        final String createReasonKey =
            createDisabledReasonKey(state, synchronizedSnapshot, operator, ownerManagementAllowed);
        if (createReasonKey != null || group == null) {
            return createReasonKey == null ? KEY_PUBLIC_UNAVAILABLE : createReasonKey;
        }
        return canManageGroup(group, operator, ownerManagementAllowed, localPlayerId)
            ? null
            : KEY_GROUP_OWNER_ONLY;
    }

    @Override
    public List<WaypointRow> waypoints() {
        final SharedWaypointClientState.View view = viewSupplier.get();
        final List<WaypointRow> rows = new ArrayList<>(view.list().size());
        for (final SharedWaypoint waypoint : view.list()) {
            rows.add(new WaypointRow(
                waypoint.id(),
                waypoint.name(),
                secondaryText(waypoint),
                waypoint.dimensionId(),
                waypoint.x(),
                waypoint.y(),
                waypoint.z(),
                waypoint.colorArgb(),
                waypoint.type(),
                waypoint.iconItemId(),
                waypoint.markerLabel(),
                waypoint.description(),
                waypoint.imageId(),
                groupKey(waypoint),
                preferences.isWaypointHidden(waypoint.id()),
                false,
                false,
                ""
            ));
        }
        return rows;
    }

    @Override
    public List<WaypointRow> guestWaypoints() {
        return List.of();
    }

    @Override
    public List<GroupRef> groups() {
        final List<GroupRef> groups = new ArrayList<>();
        for (final SharedWaypointGroup group : viewSupplier.get().groups()) {
            groups.add(toGroupRef(group));
        }
        return groups;
    }

    @Override
    public GroupRef ungrouped() {
        return GroupRef.ungrouped(preferences.isGroupCollapsed(GroupRef.UNGROUPED_KEY));
    }

    @Override
    public long stateVersion() {
        final SharedWaypointClientState.View view = viewSupplier.get();
        return preferences.version() * 31L
            + view.revision() * 7L
            + view.state().ordinal()
            + (view.synchronizedSnapshot() ? 1L : 0L);
    }

    @Override
    public boolean syncPending() {
        final SharedWaypointClientState.View view = viewSupplier.get();
        return view.state() == SharedWaypointClientState.State.ENABLED
            && !view.synchronizedSnapshot();
    }

    @Override
    public Permission canCreateWaypoint() {
        return permission(createDisabledReasonKey());
    }

    @Override
    public Permission canEditWaypoint(final WaypointRow row) {
        return permission(updateDisabledReasonKey(resolve(row)));
    }

    @Override
    public Permission canDeleteWaypoint(final WaypointRow row) {
        return permission(managementDisabledReasonKey(resolve(row)));
    }

    @Override
    public Permission canMoveWaypoint(final WaypointRow row) {
        return permission(managementDisabledReasonKey(resolve(row)));
    }

    @Override
    public Permission canToggleHidden(final WaypointRow row) {
        return Permission.allow();
    }

    @Override
    public Permission canCreateGroup() {
        return permission(createDisabledReasonKey());
    }

    @Override
    public Permission canRenameGroup(final GroupRef group) {
        return permission(groupManageDisabledReasonKey(resolveGroup(group)));
    }

    @Override
    public Permission canDeleteGroup(final GroupRef group) {
        return permission(groupManageDisabledReasonKey(resolveGroup(group)));
    }

    @Override
    public boolean supportsBatchMove() {
        return negotiatedMinor.getAsInt() >= SharedWaypointProto.PROTO_MINOR_BATCH_MOVE;
    }

    @Override
    public boolean supportsInFormGroupSelection() {
        return negotiatedMinor.getAsInt() >= SharedWaypointProto.PROTO_MINOR_BATCH_MOVE;
    }

    @Override
    public GroupDeleteBehavior groupDeleteBehavior() {
        return GroupDeleteBehavior.UNGROUP_MEMBERS;
    }

    @Override
    public boolean createWaypoint(final EditDraft draft) {
        if (!canCreateWaypoint().granted()) {
            return false;
        }
        return mutations.create(draft, resolveGroupId(draft.groupKey()));
    }

    @Override
    public boolean updateWaypoint(final WaypointRow row, final EditDraft draft) {
        final SharedWaypoint original = resolve(row);
        if (original == null || !canEditWaypoint(row).granted()) {
            return false;
        }
        return mutations.update(original, draft, resolveGroupId(draft.groupKey()));
    }

    @Override
    public boolean deleteWaypoint(final WaypointRow row) {
        final SharedWaypoint waypoint = resolve(row);
        if (waypoint == null || !canDeleteWaypoint(row).granted()) {
            return false;
        }
        return mutations.delete(waypoint);
    }

    @Override
    public boolean moveWaypoints(final List<WaypointRow> rows, final GroupRef target) {
        if (rows.isEmpty() || target == null) {
            return false;
        }
        final List<SharedWaypoint> waypoints = new ArrayList<>(rows.size());
        for (final WaypointRow row : rows) {
            final SharedWaypoint waypoint = resolve(row);
            if (waypoint == null || !canMoveWaypoint(row).granted()) {
                return false;
            }
            waypoints.add(waypoint);
        }
        return mutations.moveBatch(waypoints, resolveGroupId(target.key()));
    }

    @Override
    public boolean toggleWaypointHidden(final WaypointRow row) {
        preferences.setWaypointHidden(row.id(), !row.hidden());
        return true;
    }

    @Override
    public boolean toggleGroupCollapsed(final GroupRef group) {
        preferences.setGroupCollapsed(group.key(), !group.collapsed());
        return true;
    }

    @Override
    public boolean createGroup(final String name) {
        if (!canCreateGroup().granted()) {
            return false;
        }
        return mutations.createGroup(name);
    }

    @Override
    public boolean renameGroup(final GroupRef group, final String name) {
        final SharedWaypointGroup target = resolveGroup(group);
        if (target == null || !canRenameGroup(group).granted()) {
            return false;
        }
        return mutations.renameGroup(target, name);
    }

    @Override
    public boolean deleteGroup(final GroupRef group) {
        final SharedWaypointGroup target = resolveGroup(group);
        if (target == null || !canDeleteGroup(group).granted()) {
            return false;
        }
        return mutations.deleteGroup(target);
    }

    /** Server-update capability additionally needs negotiated minor 2. */
    private String updateDisabledReasonKey(final SharedWaypoint waypoint) {
        final String reasonKey = managementDisabledReasonKey(waypoint);
        return reasonKey == null && negotiatedMinor.getAsInt() < 2
            ? KEY_PUBLIC_UNAVAILABLE
            : reasonKey;
    }

    private String createDisabledReasonKey() {
        final SharedWaypointClientState.View view = viewSupplier.get();
        return createDisabledReasonKey(
            view.state(), view.synchronizedSnapshot(), view.operator(), ownerManagementAllowed.getAsBoolean()
        );
    }

    private String managementDisabledReasonKey(final SharedWaypoint waypoint) {
        final SharedWaypointClientState.View view = viewSupplier.get();
        return managementDisabledReasonKey(
            view.state(), view.synchronizedSnapshot(), view.operator(),
            ownerManagementAllowed.getAsBoolean(), waypoint, localPlayerId.get()
        );
    }

    private String groupManageDisabledReasonKey(final SharedWaypointGroup group) {
        final SharedWaypointClientState.View view = viewSupplier.get();
        return groupManageDisabledReasonKey(
            view.state(), view.synchronizedSnapshot(), view.operator(),
            ownerManagementAllowed.getAsBoolean(), group, localPlayerId.get()
        );
    }

    private static Permission permission(final String reasonKey) {
        return reasonKey == null ? Permission.allow() : Permission.denied(reasonKey);
    }

    private SharedWaypoint resolve(final WaypointRow row) {
        for (final SharedWaypoint waypoint : viewSupplier.get().list()) {
            if (waypoint.id().equals(row.id())) {
                return waypoint;
            }
        }
        return null;
    }

    private SharedWaypointGroup resolveGroup(final GroupRef group) {
        if (group == null || group.isUngrouped()) {
            return null;
        }
        for (final SharedWaypointGroup candidate : viewSupplier.get().groups()) {
            if (candidate.id().toString().equals(group.key())) {
                return candidate;
            }
        }
        return null;
    }

    private UUID resolveGroupId(final String groupKey) {
        final SharedWaypointGroup group = resolveGroup(new GroupRef(groupKey, groupKey, false));
        return group == null ? null : group.id();
    }

    private GroupRef toGroupRef(final SharedWaypointGroup group) {
        return new GroupRef(
            group.id().toString(), group.name(), preferences.isGroupCollapsed(group.id().toString())
        );
    }

    private static String groupKey(final SharedWaypoint waypoint) {
        return waypoint.groupId() == null ? GroupRef.UNGROUPED_KEY : waypoint.groupId().toString();
    }

    private String secondaryText(final SharedWaypoint waypoint) {
        final String publisher = localizer.format(KEY_SHARED_BY, waypoint.publisherName());
        if (waypoint.groupId() == null) {
            return publisher;
        }
        for (final SharedWaypointGroup group : viewSupplier.get().groups()) {
            if (group.id().equals(waypoint.groupId())) {
                return group.name() + " \u00b7 " + publisher;
            }
        }
        return publisher;
    }
}
