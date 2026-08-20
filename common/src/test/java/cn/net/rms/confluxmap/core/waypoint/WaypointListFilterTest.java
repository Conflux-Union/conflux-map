package cn.net.rms.confluxmap.core.waypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class WaypointListFilterTest {
    @Test
    void localListHidesOtherDimensionsUntilCrossDimensionDisplayIsEnabled() {
        final List<Waypoint> waypoints = List.of(
            waypoint("Overworld", DimensionId.OVERWORLD),
            waypoint("Nether", DimensionId.NETHER),
            waypoint("End", DimensionId.END)
        );

        assertEquals(
            List.of("Nether"),
            WaypointListFilter.local(waypoints, DimensionId.NETHER, false).stream()
                .map(waypoint -> waypoint.name)
                .toList()
        );
        assertEquals(
            List.of("Overworld", "Nether", "End"),
            WaypointListFilter.local(waypoints, DimensionId.NETHER, true).stream()
                .map(waypoint -> waypoint.name)
                .toList()
        );
    }

    @Test
    void sharedListsUseTheSameDimensionRule() {
        final List<SharedWaypoint> waypoints = List.of(
            sharedWaypoint("Overworld", DimensionId.OVERWORLD),
            sharedWaypoint("Nether", DimensionId.NETHER),
            sharedWaypoint("End", DimensionId.END)
        );

        assertEquals(
            List.of("End"),
            WaypointListFilter.shared(waypoints, DimensionId.END, false).stream()
                .map(SharedWaypoint::name)
                .toList()
        );
        assertEquals(
            List.of("Overworld", "Nether", "End"),
            WaypointListFilter.shared(waypoints, DimensionId.END, true).stream()
                .map(SharedWaypoint::name)
                .toList()
        );
    }

    @Test
    void managementFilterSupportsCurrentAllAndOneKnownDimension() {
        final List<Waypoint> waypoints = List.of(
            waypoint("Overworld", DimensionId.OVERWORLD),
            waypoint("Nether", DimensionId.NETHER),
            waypoint("End", DimensionId.END)
        );

        assertEquals(
            List.of("Nether"),
            WaypointListFilter.local(
                waypoints, DimensionId.NETHER, WaypointDimensionFilter.current()
            ).stream().map(waypoint -> waypoint.name).toList()
        );
        assertEquals(
            List.of("Overworld", "Nether", "End"),
            WaypointListFilter.local(
                waypoints, DimensionId.NETHER, WaypointDimensionFilter.all()
            ).stream().map(waypoint -> waypoint.name).toList()
        );
        assertEquals(
            List.of("End"),
            WaypointListFilter.local(
                waypoints, DimensionId.NETHER, WaypointDimensionFilter.only(DimensionId.END)
            ).stream().map(waypoint -> waypoint.name).toList()
        );
        assertEquals(
            List.of("Overworld"),
            WaypointListFilter.shared(
                List.of(
                    sharedWaypoint("Overworld", DimensionId.OVERWORLD),
                    sharedWaypoint("Nether", DimensionId.NETHER)
                ),
                DimensionId.NETHER,
                WaypointDimensionFilter.only(DimensionId.OVERWORLD)
            ).stream().map(SharedWaypoint::name).toList()
        );
    }

    @Test
    void filterOptionsContainEveryKnownDimensionWithoutDuplicatingCurrent() {
        assertEquals(
            List.of(
                WaypointDimensionFilter.current(),
                WaypointDimensionFilter.all(),
                WaypointDimensionFilter.only(DimensionId.OVERWORLD),
                WaypointDimensionFilter.only(DimensionId.END)
            ),
            WaypointDimensionFilter.options(
                DimensionId.NETHER,
                List.of(DimensionId.OVERWORLD, DimensionId.NETHER, DimensionId.END)
            )
        );
    }

    private static Waypoint waypoint(final String name, final DimensionId dimension) {
        return new Waypoint(
            UUID.randomUUID(), name, dimension, 0.0, 64.0, 0.0,
            0xFFFFFFFF, "", true, Waypoint.Type.NORMAL, 1L
        );
    }

    private static SharedWaypoint sharedWaypoint(final String name, final DimensionId dimension) {
        return new SharedWaypoint(
            UUID.randomUUID(), UUID.randomUUID(), "Publisher", name, dimension,
            0.0, 64.0, 0.0, 0xFFFFFFFF, Waypoint.Type.NORMAL, false, 1L, 1L
        );
    }
}
