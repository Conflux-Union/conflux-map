package cn.net.rms.confluxmap.core.waypoint;

import cn.net.rms.confluxmap.core.waypoint.list.GroupRef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Flattens pre-filtered waypoint rows into the group-collapsible model used
 * by the waypoint management list: one collapsible header per group that
 * still has rows, followed by that group's rows in encounter order. Pure
 * over its inputs, so the management screen only supplies dimension/search
 * filtering and sorting.
 */
public final class WaypointListGrouping {

    /** One flattened list row: either a group header or a member waypoint. */
    public interface Entry<T> {
    }

    /**
     * Header row for one group. {@code collapsed} reports the group's
     * persisted fold state as given, even when {@code expandAll} currently
     * suspends it.
     */
    public record GroupHeader<T>(GroupRef group, int visibleCount) implements Entry<T> {
    }

    /** Member row, indented one level under its group header. */
    public record Member<T>(String groupKey, T row) implements Entry<T> {
    }

    private WaypointListGrouping() {
    }

    /** Pins matching rows above every header; remaining rows keep their original grouping. */
    public static <T> List<Entry<T>> groupPinned(
        final List<GroupRef> groups,
        final List<T> rows,
        final Function<T, String> groupKeyOf,
        final Predicate<T> pinned,
        final boolean expandAll
    ) {
        final List<Entry<T>> result = new ArrayList<>();
        final List<T> remaining = new ArrayList<>();
        for (final T row : rows) {
            if (pinned.test(row)) {
                result.add(new Member<>(groupKeyOf.apply(row), row));
            } else {
                remaining.add(row);
            }
        }
        result.addAll(group(groups, remaining, groupKeyOf, expandAll));
        return result;
    }

    /**
     * Groups rows by group key in {@code groups} order, preserving encounter
     * order within each group. Groups without rows are omitted; a collapsed
     * group contributes only its header unless {@code expandAll} suspends
     * folding, which the caller does while a search filter is active so
     * matches inside folded groups stay visible. Rows whose group has no
     * {@link GroupRef} metadata are appended as extra expanded groups in
     * first-encounter order - defensive, since both backing stores keep
     * every group materialized.
     */
    public static <T> List<Entry<T>> group(
        final List<GroupRef> groups,
        final List<T> rows,
        final Function<T, String> groupKeyOf,
        final boolean expandAll
    ) {
        final Map<String, List<T>> byGroup = new LinkedHashMap<>();
        for (final T row : rows) {
            byGroup.computeIfAbsent(groupKeyOf.apply(row), key -> new ArrayList<>()).add(row);
        }
        final List<Entry<T>> out = new ArrayList<>();
        for (final GroupRef group : groups) {
            final List<T> members = byGroup.remove(group.key());
            if (members == null) {
                continue;
            }
            out.add(new GroupHeader<>(group, members.size()));
            if (!group.collapsed() || expandAll) {
                for (final T row : members) {
                    out.add(new Member<>(group.key(), row));
                }
            }
        }
        for (final Map.Entry<String, List<T>> unknown : byGroup.entrySet()) {
            out.add(new GroupHeader<>(new GroupRef(unknown.getKey(), unknown.getKey(), false), unknown.getValue().size()));
            for (final T row : unknown.getValue()) {
                out.add(new Member<>(unknown.getKey(), row));
            }
        }
        return out;
    }
}
