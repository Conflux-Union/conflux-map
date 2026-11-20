package cn.net.rms.confluxmap.core.api;

import cn.net.rms.confluxmap.api.SharedWaypointApi;
import cn.net.rms.confluxmap.api.WaypointApi.WaypointType;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointAvailability;
import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Read-only shared-waypoint facade over the live client catalog sources. */
final class ApiSharedWaypoints implements SharedWaypointApi {
    private final Supplier<List<SharedWaypoint>> listSource;
    private final Supplier<SharedWaypointAvailability> availabilitySource;

    ApiSharedWaypoints(
        final Supplier<List<SharedWaypoint>> listSource,
        final Supplier<SharedWaypointAvailability> availabilitySource
    ) {
        this.listSource = Objects.requireNonNull(listSource, "listSource");
        this.availabilitySource = Objects.requireNonNull(availabilitySource, "availabilitySource");
    }

    @Override
    public List<ApiSharedWaypoint> list() {
        return toApi(listSource.get());
    }

    @Override
    public Optional<ApiSharedWaypoint> get(final UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        for (final SharedWaypoint waypoint : listSource.get()) {
            if (id.equals(waypoint.id())) {
                return Optional.of(toApi(waypoint));
            }
        }
        return Optional.empty();
    }

    @Override
    public SharedWaypointAccess availability() {
        final SharedWaypointAvailability availability = availabilitySource.get();
        return new SharedWaypointAccess(
            availability.visible(), availability.enabled(), availability.ready()
        );
    }

    static List<ApiSharedWaypoint> toApi(final List<SharedWaypoint> waypoints) {
        final List<ApiSharedWaypoint> out = new ArrayList<>(waypoints.size());
        for (final SharedWaypoint waypoint : waypoints) {
            out.add(toApi(waypoint));
        }
        return out;
    }

    static ApiSharedWaypoint toApi(final SharedWaypoint waypoint) {
        return new ApiSharedWaypoint(
            waypoint.id(),
            waypoint.publisherName(),
            waypoint.name(),
            waypoint.dimensionId().toString(),
            waypoint.x(),
            waypoint.y(),
            waypoint.z(),
            waypoint.colorArgb(),
            ApiMappers.fromCore(waypoint.type()),
            waypoint.iconItemId(),
            waypoint.markerLabel(),
            waypoint.description(),
            waypoint.imageId(),
            waypoint.createdAtEpochMs()
        );
    }
}
