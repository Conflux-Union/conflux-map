package cn.net.rms.confluxmap.core.api;

import cn.net.rms.confluxmap.api.WaypointApi;
import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointDescription;
import cn.net.rms.confluxmap.core.waypoint.WaypointService;
import cn.net.rms.confluxmap.core.waypoint.WaypointStore;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** {@link WaypointApi} over the live {@link WaypointService}; see the interface contract. */
final class ApiWaypoints implements WaypointApi {
    static final int DEFAULT_COLOR = 0xFFF1C40F;

    private final GameBridge bridge;
    private final WaypointService service;

    ApiWaypoints(final GameBridge bridge, final WaypointService service) {
        this.bridge = bridge;
        this.service = service;
    }

    @Override
    public List<ApiWaypoint> list() {
        final WaypointStore store = service.current();
        return store == null ? List.of() : ApiMappers.toApi(store.list());
    }

    @Override
    public Optional<ApiWaypoint> get(final UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        for (final ApiWaypoint waypoint : list()) {
            if (waypoint.id().equals(id)) {
                return Optional.of(waypoint);
            }
        }
        return Optional.empty();
    }

    @Override
    public List<String> sets() {
        final WaypointStore store = service.current();
        return store == null ? List.of() : ApiMappers.setNames(store.sets());
    }

    @Override
    public CompletableFuture<WaypointMutation> add(final WaypointEdit edit) {
        if (edit == null) {
            return CompletableFuture.completedFuture(
                new WaypointMutation(WaypointMutation.Result.INVALID, null)
            );
        }
        return ConfluxMapApiImpl.callOnMain(bridge, () -> {
            final WaypointStore store = service.current();
            if (store == null) {
                return new WaypointMutation(WaypointMutation.Result.NO_SESSION, null);
            }
            if (!store.persistenceWritable()) {
                return new WaypointMutation(WaypointMutation.Result.READ_ONLY, null);
            }
            final String name = edit.name() == null ? "" : edit.name().trim();
            if (name.isEmpty()) {
                return new WaypointMutation(WaypointMutation.Result.INVALID, null);
            }
            final Optional<PlayerView> viewpoint = bridge.viewpoint();
            final PlayerView fallback = viewpoint.orElse(null);
            if (fallback == null && (edit.x() == null || edit.y() == null || edit.z() == null)) {
                return new WaypointMutation(WaypointMutation.Result.INVALID, null);
            }
            final DimensionId dimension = edit.dimensionId() != null
                ? DimensionId.parse(edit.dimensionId())
                : fallback == null ? bridge.session().dimension() : fallback.dimension();
            final Waypoint waypoint = Waypoint.create(
                name,
                dimension,
                edit.x() != null ? edit.x() : fallback.x(),
                edit.y() != null ? edit.y() : fallback.y(),
                edit.z() != null ? edit.z() : fallback.z(),
                edit.colorArgb() != null ? edit.colorArgb() : DEFAULT_COLOR,
                edit.group() == null ? "" : edit.group(),
                edit.type() == null ? Waypoint.Type.NORMAL : ApiMappers.toCore(edit.type())
            );
            waypoint.visible = edit.visible() == null || edit.visible();
            waypoint.crossDimensionVisible = edit.crossDimensionVisible() != null && edit.crossDimensionVisible();
            waypoint.iconItemId = edit.iconItemId() == null ? "" : edit.iconItemId();
            waypoint.markerLabel = edit.markerLabel() == null ? "" : edit.markerLabel();
            final String description;
            final String imageId;
            try {
                description = WaypointDescription.normalize(edit.description());
                imageId = WaypointDescription.normalizeImageId(edit.imageId());
            } catch (final IllegalArgumentException e) {
                return new WaypointMutation(WaypointMutation.Result.INVALID, null);
            }
            waypoint.description = description;
            waypoint.imageId = imageId;
            store.add(waypoint);
            return new WaypointMutation(WaypointMutation.Result.APPLIED, ApiMappers.toApi(waypoint));
        });
    }

    @Override
    public CompletableFuture<WaypointMutation> update(final UUID id, final WaypointEdit changes) {
        if (id == null || changes == null) {
            return CompletableFuture.completedFuture(
                new WaypointMutation(WaypointMutation.Result.INVALID, null)
            );
        }
        return ConfluxMapApiImpl.callOnMain(bridge, () -> {
            final WaypointStore store = service.current();
            if (store == null) {
                return new WaypointMutation(WaypointMutation.Result.NO_SESSION, null);
            }
            final Waypoint existing = find(store, id);
            if (existing == null) {
                return new WaypointMutation(WaypointMutation.Result.NOT_FOUND, null);
            }
            if (!store.persistenceWritable()) {
                return new WaypointMutation(WaypointMutation.Result.READ_ONLY, null);
            }
            if (changes.name() != null) {
                final String name = changes.name().trim();
                if (name.isEmpty()) {
                    return new WaypointMutation(WaypointMutation.Result.INVALID, null);
                }
                existing.name = name;
            }
            if (changes.dimensionId() != null) {
                existing.dimensionId = DimensionId.parse(changes.dimensionId());
            }
            if (changes.x() != null) {
                existing.x = changes.x();
            }
            if (changes.y() != null) {
                existing.y = changes.y();
            }
            if (changes.z() != null) {
                existing.z = changes.z();
            }
            if (changes.colorArgb() != null) {
                existing.colorArgb = changes.colorArgb();
            }
            if (changes.group() != null) {
                existing.group = changes.group();
            }
            if (changes.visible() != null) {
                existing.visible = changes.visible();
            }
            if (changes.crossDimensionVisible() != null) {
                existing.crossDimensionVisible = changes.crossDimensionVisible();
            }
            if (changes.type() != null) {
                existing.type = ApiMappers.toCore(changes.type());
            }
            if (changes.iconItemId() != null) {
                existing.iconItemId = changes.iconItemId();
            }
            if (changes.markerLabel() != null) {
                existing.markerLabel = changes.markerLabel();
            }
            if (changes.description() != null) {
                try {
                    existing.description = WaypointDescription.normalize(changes.description());
                } catch (final IllegalArgumentException e) {
                    return new WaypointMutation(WaypointMutation.Result.INVALID, null);
                }
            }
            if (changes.imageId() != null) {
                try {
                    existing.imageId = WaypointDescription.normalizeImageId(changes.imageId());
                } catch (final IllegalArgumentException e) {
                    return new WaypointMutation(WaypointMutation.Result.INVALID, null);
                }
            }
            store.update(existing);
            return new WaypointMutation(WaypointMutation.Result.APPLIED, ApiMappers.toApi(existing));
        });
    }

    @Override
    public CompletableFuture<WaypointMutation> remove(final UUID id) {
        if (id == null) {
            return CompletableFuture.completedFuture(
                new WaypointMutation(WaypointMutation.Result.INVALID, null)
            );
        }
        return ConfluxMapApiImpl.callOnMain(bridge, () -> {
            final WaypointStore store = service.current();
            if (store == null) {
                return new WaypointMutation(WaypointMutation.Result.NO_SESSION, null);
            }
            final Waypoint existing = find(store, id);
            if (existing == null) {
                return new WaypointMutation(WaypointMutation.Result.NOT_FOUND, null);
            }
            if (!store.persistenceWritable()) {
                return new WaypointMutation(WaypointMutation.Result.READ_ONLY, null);
            }
            store.remove(id);
            return new WaypointMutation(WaypointMutation.Result.APPLIED, ApiMappers.toApi(existing));
        });
    }

    private static Waypoint find(final WaypointStore store, final UUID id) {
        for (final Waypoint waypoint : store.list()) {
            if (waypoint.id.equals(id)) {
                return waypoint;
            }
        }
        return null;
    }
}
