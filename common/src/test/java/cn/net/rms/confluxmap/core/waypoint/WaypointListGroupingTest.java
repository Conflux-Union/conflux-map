package cn.net.rms.confluxmap.core.waypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.waypoint.list.GroupRef;
import java.util.List;
import org.junit.jupiter.api.Test;

class WaypointListGroupingTest {
    private static final List<GroupRef> GROUPS = List.of(
        new GroupRef("Bases", "Bases", false),
        new GroupRef("Travel", "Travel", false)
    );

    @Test
    void groupsRowsUnderHeadersInGroupOrderPreservingEncounterOrder() {
        final List<String> rows = List.of("Travel spot", "Base camp", "Unassigned", "Second base");

        final List<WaypointListGrouping.Entry<String>> grouped = WaypointListGrouping.group(
            GROUPS, rows, row -> groupOf(row), false
        );

        assertEquals(List.of(
            "header: \"Bases\" (2)",
            "member Base camp -> \"Bases\"",
            "member Second base -> \"Bases\"",
            "header: \"Travel\" (1)",
            "member Travel spot -> \"Travel\"",
            "header: \"\" (1)",
            "member Unassigned -> \"\""
        ), render(grouped));
    }

    @Test
    void collapsedGroupContributesOnlyItsHeader() {
        final List<GroupRef> groups = List.of(
            new GroupRef("Bases", "Bases", true),
            GroupRef.ungrouped(false)
        );
        final List<String> rows = List.of("Base camp", "Unassigned");

        final List<WaypointListGrouping.Entry<String>> grouped = WaypointListGrouping.group(
            groups, rows, row -> groupOf(row), false
        );

        assertEquals(List.of(
            "header: \"Bases\" [collapsed] (1)",
            "header: \"\" (1)",
            "member Unassigned -> \"\""
        ), render(grouped));
    }

    @Test
    void expandAllSuspendsCollapseButHeadersStillReportThePersistedState() {
        final List<GroupRef> groups = List.of(new GroupRef("Bases", "Bases", true));
        final List<String> rows = List.of("Base camp");

        final List<WaypointListGrouping.Entry<String>> grouped = WaypointListGrouping.group(
            groups, rows, row -> groupOf(row), true
        );

        assertEquals(List.of(
            "header: \"Bases\" [collapsed] (1)",
            "member Base camp -> \"Bases\""
        ), render(grouped));
    }

    @Test
    void groupsWithoutRowsAndEmptyInputProduceNothing() {
        assertEquals(
            List.of(),
            WaypointListGrouping.group(GROUPS, List.<String>of(), WaypointListGroupingTest::groupOf, false)
        );
        assertEquals(
            List.of(),
            WaypointListGrouping.group(
                List.of(new GroupRef("Empty", "Empty", false)), List.<String>of(),
                WaypointListGroupingTest::groupOf, false
            )
        );
    }

    @Test
    void rowsOutsideGroupMetadataAppendAsExpandedGroupsInEncounterOrder() {
        final List<String> rows = List.of("Ghost two", "Base camp", "Ghost one");

        final List<WaypointListGrouping.Entry<String>> grouped = WaypointListGrouping.group(
            List.of(new GroupRef("Bases", "Bases", false), GroupRef.ungrouped(false)),
            rows, row -> groupOf(row), false
        );

        assertEquals(List.of(
            "header: \"Bases\" (1)",
            "member Base camp -> \"Bases\"",
            "header: \"Ghost\" (2)",
            "member Ghost two -> \"Ghost\"",
            "member Ghost one -> \"Ghost\""
        ), render(grouped));
    }

    @Test
    void starsAppearAboveAllGroupsWithoutDuplicatingRows() {
        final List<String> rows = List.of("Travel near", "Base camp", "Second base", "Unassigned");
        final var result = WaypointListGrouping.groupPinned(GROUPS, rows,
            WaypointListGroupingTest::groupOf, row -> row.equals("Travel near") || row.equals("Second base"), false);
        assertEquals(List.of(
            "member Travel near -> \"Travel\"",
            "member Second base -> \"Bases\"",
            "header: \"Bases\" (1)",
            "member Base camp -> \"Bases\"",
            "header: \"\" (1)",
            "member Unassigned -> \"\""
        ), render(result));
    }

    @Test
    void unstarRestoresTheOriginalGroupingAndDistanceEncounterOrder() {
        final List<String> rows = List.of("Travel near", "Second base", "Base camp", "Unassigned");
        assertEquals(
            WaypointListGrouping.group(GROUPS, rows, WaypointListGroupingTest::groupOf, false),
            WaypointListGrouping.groupPinned(GROUPS, rows, WaypointListGroupingTest::groupOf, row -> false, false)
        );
    }

    @Test
    void starredRowsRemainVisibleWhenTheirOriginalGroupIsCollapsed() {
        final List<GroupRef> groups = List.of(new GroupRef("Bases", "Bases", true));
        assertEquals(List.of("member Base camp -> \"Bases\"", "header: \"Bases\" [collapsed] (1)"), render(
            WaypointListGrouping.groupPinned(groups, List.of("Base camp", "Second base"),
                WaypointListGroupingTest::groupOf, row -> row.equals("Base camp"), false)
        ));
        assertEquals(List.of(), WaypointListGrouping.groupPinned(groups, List.<String>of(),
            WaypointListGroupingTest::groupOf, row -> true, false));
    }

    private static String groupOf(final String row) {
        if (row.startsWith("Base") || row.startsWith("Second")) {
            return "Bases";
        }
        if (row.startsWith("Travel")) {
            return "Travel";
        }
        if (row.startsWith("Ghost")) {
            return "Ghost";
        }
        return "";
    }

    private static List<String> render(final List<WaypointListGrouping.Entry<String>> entries) {
        return entries.stream().map(entry -> {
            if (entry instanceof WaypointListGrouping.GroupHeader<?> header) {
                return "header: \"" + header.group().key() + "\""
                    + (header.group().collapsed() ? " [collapsed]" : "")
                    + " (" + header.visibleCount() + ")";
            }
            final WaypointListGrouping.Member<?> member = (WaypointListGrouping.Member<?>) entry;
            return "member " + member.row() + " -> \"" + member.groupKey() + "\"";
        }).toList();
    }
}
