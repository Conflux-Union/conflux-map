package cn.net.rms.confluxmap.server.shared;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.chat.WaypointChatCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Platform-neutral command use cases for server-owned shared waypoints. */
public final class SharedWaypointCommandService {
    public static final int PAGE_SIZE = 6;
    public static final int DEFAULT_COLOR_ARGB = 0xFF3498DB;

    private static final int MIN_ID_PREFIX_LENGTH = 8;
    private static final Pattern COMPACT_ID = Pattern.compile("[0-9a-f]{1,32}");

    public enum Status {
        APPLIED,
        REJECTED,
        INVALID_ID,
        UNKNOWN_ID,
        AMBIGUOUS_ID,
        FORBIDDEN
    }

    public record Position(DimensionId dimensionId, double x, double y, double z) {
        public Position {
            Objects.requireNonNull(dimensionId, "dimensionId");
        }
    }

    public record Entry(String idPrefix, SharedWaypoint waypoint, String xaeroMessage) {
        public Entry {
            Objects.requireNonNull(idPrefix, "idPrefix");
            Objects.requireNonNull(waypoint, "waypoint");
            Objects.requireNonNull(xaeroMessage, "xaeroMessage");
        }
    }

    public record Page(
        boolean valid,
        int page,
        int totalPages,
        int totalWaypoints,
        List<Entry> entries
    ) {
        public Page {
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        }
    }

    public record Result(
        Status status,
        SharedWaypointService.MutationResult mutation,
        SharedWaypoint waypoint
    ) {
        public Result {
            Objects.requireNonNull(status, "status");
        }

        public boolean applied() {
            return status == Status.APPLIED;
        }
    }

    private record Resolution(Status status, SharedWaypoint waypoint) {
    }

    private final SharedWaypointService service;
    private final Supplier<UUID> operationIds;
    private final Consumer<SharedWaypointService.MutationResult> appliedMutation;

    public SharedWaypointCommandService(
        final SharedWaypointService service,
        final Supplier<UUID> operationIds,
        final Consumer<SharedWaypointService.MutationResult> appliedMutation
    ) {
        this.service = Objects.requireNonNull(service, "service");
        this.operationIds = Objects.requireNonNull(operationIds, "operationIds");
        this.appliedMutation = Objects.requireNonNull(appliedMutation, "appliedMutation");
    }

    public Page list(final int requestedPage) {
        final List<SharedWaypoint> waypoints = service.snapshot().waypoints();
        final int totalPages = Math.max(1, (waypoints.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (requestedPage < 1 || requestedPage > totalPages) {
            return new Page(false, requestedPage, totalPages, waypoints.size(), List.of());
        }
        final int from = (requestedPage - 1) * PAGE_SIZE;
        final int to = Math.min(waypoints.size(), from + PAGE_SIZE);
        final List<Entry> entries = new ArrayList<>(to - from);
        for (int index = from; index < to; index++) {
            final SharedWaypoint waypoint = waypoints.get(index);
            entries.add(new Entry(
                uniquePrefix(waypoint.id(), waypoints),
                waypoint,
                WaypointChatCodec.formatXaero(
                    waypoint.name(), waypoint.dimensionId(), waypoint.x(), waypoint.y(), waypoint.z(),
                    waypoint.colorArgb()
                )
            ));
        }
        return new Page(true, requestedPage, totalPages, waypoints.size(), entries);
    }

    public Result createHere(
        final SharedWaypointService.Actor actor,
        final Position position,
        final String name
    ) {
        Objects.requireNonNull(position, "position");
        return mutation(service.create(
            actor,
            new SharedWaypointService.CreateRequest(
                operationId(), service.snapshot().revision(), name, position.dimensionId(),
                position.x(), position.y(), position.z(), DEFAULT_COLOR_ARGB, Waypoint.Type.NORMAL
            )
        ));
    }

    public Result rename(
        final SharedWaypointService.Actor actor,
        final String idPrefix,
        final String name
    ) {
        final Resolution resolution = resolve(idPrefix);
        if (resolution.status() != Status.APPLIED) {
            return new Result(resolution.status(), null, null);
        }
        final SharedWaypoint waypoint = resolution.waypoint();
        return update(actor, waypoint, name, new Position(
            waypoint.dimensionId(), waypoint.x(), waypoint.y(), waypoint.z()
        ));
    }

    public Result moveHere(
        final SharedWaypointService.Actor actor,
        final String idPrefix,
        final Position position
    ) {
        Objects.requireNonNull(position, "position");
        final Resolution resolution = resolve(idPrefix);
        if (resolution.status() != Status.APPLIED) {
            return new Result(resolution.status(), null, null);
        }
        final SharedWaypoint waypoint = resolution.waypoint();
        return update(actor, waypoint, waypoint.name(), position);
    }

    public Result delete(final SharedWaypointService.Actor actor, final String idPrefix) {
        Objects.requireNonNull(actor, "actor");
        if (!actor.operator()) {
            return new Result(Status.FORBIDDEN, null, null);
        }
        final Resolution resolution = resolve(idPrefix);
        if (resolution.status() != Status.APPLIED) {
            return new Result(resolution.status(), null, null);
        }
        final SharedWaypoint waypoint = resolution.waypoint();
        return mutation(service.delete(
            actor,
            new SharedWaypointService.DeleteRequest(operationId(), waypoint.revision(), waypoint.id())
        ));
    }

    private Result update(
        final SharedWaypointService.Actor actor,
        final SharedWaypoint waypoint,
        final String name,
        final Position position
    ) {
        return mutation(service.update(
            actor,
            new SharedWaypointService.UpdateRequest(
                operationId(), waypoint.revision(), waypoint.id(), name, position.dimensionId(),
                position.x(), position.y(), position.z(), waypoint.colorArgb(), waypoint.type()
            )
        ));
    }

    private Result mutation(final SharedWaypointService.MutationResult mutation) {
        if (!mutation.applied()) {
            return new Result(Status.REJECTED, mutation, null);
        }
        if (!mutation.replayed() && mutation.delta().kind() != SharedWaypointStore.DeltaKind.NOOP) {
            appliedMutation.accept(mutation);
        }
        final SharedWaypoint waypoint = mutation.delta().kind() == SharedWaypointStore.DeltaKind.UPSERT
            ? mutation.delta().waypoint()
            : null;
        return new Result(Status.APPLIED, mutation, waypoint);
    }

    private Resolution resolve(final String input) {
        if (input == null) {
            return new Resolution(Status.INVALID_ID, null);
        }
        final String compact = input.strip().toLowerCase(Locale.ROOT).replace("-", "");
        if (!COMPACT_ID.matcher(compact).matches()) {
            return new Resolution(Status.INVALID_ID, null);
        }
        SharedWaypoint match = null;
        for (final SharedWaypoint waypoint : service.snapshot().waypoints()) {
            if (!compact(waypoint.id()).startsWith(compact)) {
                continue;
            }
            if (match != null) {
                return new Resolution(Status.AMBIGUOUS_ID, null);
            }
            match = waypoint;
        }
        return match == null
            ? new Resolution(Status.UNKNOWN_ID, null)
            : new Resolution(Status.APPLIED, match);
    }

    private UUID operationId() {
        return Objects.requireNonNull(operationIds.get(), "operation id");
    }

    private static String uniquePrefix(
        final UUID id,
        final List<SharedWaypoint> waypoints
    ) {
        final String compact = compact(id);
        for (int length = MIN_ID_PREFIX_LENGTH; length < compact.length(); length++) {
            final String prefix = compact.substring(0, length);
            boolean unique = true;
            for (final SharedWaypoint waypoint : waypoints) {
                if (!waypoint.id().equals(id) && compact(waypoint.id()).startsWith(prefix)) {
                    unique = false;
                    break;
                }
            }
            if (unique) {
                return prefix;
            }
        }
        return compact;
    }

    private static String compact(final UUID id) {
        return id.toString().replace("-", "");
    }
}
