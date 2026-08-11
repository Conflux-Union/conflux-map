package cn.net.rms.confluxmap.core.radar;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Selects the smallest face-like subset from the stable child names exposed by an entity model.
 * Paths use {@code /} separators and are ordered only for deterministic fallback selection.
 */
public final class HeadPartSelector {
    private static final Set<String> FULL_MODEL_TYPES = Set.of(
        "minecraft:cod", "minecraft:salmon", "minecraft:pufferfish",
        "minecraft:tropical_fish", "minecraft:slime", "minecraft:magma_cube",
        "minecraft:ghast"
    );
    private static final Set<String> VILLAGER_FACE_TYPES = Set.of(
        "minecraft:villager", "minecraft:zombie_villager", "minecraft:wandering_trader"
    );

    private HeadPartSelector() {
    }

    public static boolean usesFullModel(final String entityType) {
        return entityType != null && FULL_MODEL_TYPES.contains(entityType.toLowerCase(Locale.ROOT));
    }

    public static Set<String> select(final String entityType, final Collection<String> availablePaths) {
        final List<String> paths = availablePaths.stream()
            .filter(path -> path != null && !path.isBlank())
            .distinct()
            .sorted(Comparator.comparingInt(HeadPartSelector::depth).thenComparing(path -> path))
            .toList();
        if (paths.isEmpty()) {
            return Set.of();
        }

        final String type = entityType == null ? "" : entityType.toLowerCase(Locale.ROOT);
        if (usesFullModel(type)) {
            return root(paths);
        }
        if (type.equals("minecraft:wither")) {
            final Set<String> heads = matching(paths, name -> name.equals("head") || name.endsWith("_head"));
            if (!heads.isEmpty()) {
                return heads;
            }
        }

        final String headParts = firstNamed(paths, "head_parts");
        if (headParts != null) {
            return Set.of(headParts);
        }

        final String head = firstNamed(paths, "head");
        if (head != null) {
            final LinkedHashSet<String> selected = new LinkedHashSet<>();
            selected.add(head);
            if (VILLAGER_FACE_TYPES.contains(type)) {
                paths.stream()
                    .filter(path -> path.startsWith(head + "/") && leaf(path).contains("hat"))
                    .forEach(selected::add);
            }
            if (type.equals("minecraft:rabbit")) {
                addNamed(selected, paths, "right_ear");
                addNamed(selected, paths, "left_ear");
                addNamed(selected, paths, "nose");
            }
            if (type.equals("minecraft:spider") || type.equals("minecraft:cave_spider")) {
                final String body0 = firstNamed(paths, "body0");
                if (body0 != null) {
                    selected.add(body0);
                }
            }
            return Set.copyOf(selected);
        }

        final String body = firstNamed(paths, "body");
        if (body != null) {
            return Set.of(body);
        }
        final String cube = firstNamed(paths, "cube");
        if (cube != null) {
            return Set.of(cube);
        }

        final List<String> segments = paths.stream()
            .filter(path -> leaf(path).matches("segment[_-]?[01]"))
            .limit(2)
            .toList();
        if (!segments.isEmpty()) {
            return Set.copyOf(segments);
        }
        // No face-like part name: report nothing instead of the whole root. A whole-body portrait
        // reads as a rendering bug, while an empty selection degrades to the shaped category dot.
        return Set.of();
    }

    private static void addNamed(
        final Set<String> selected,
        final List<String> paths,
        final String name
    ) {
        final String path = firstNamed(paths, name);
        if (path != null) {
            selected.add(path);
        }
    }

    private static Set<String> matching(
        final List<String> paths,
        final java.util.function.Predicate<String> namePredicate
    ) {
        final Set<String> result = new LinkedHashSet<>();
        for (final String path : paths) {
            if (namePredicate.test(leaf(path))) {
                result.add(path);
            }
        }
        return Set.copyOf(result);
    }

    private static String firstNamed(final List<String> paths, final String name) {
        for (final String path : paths) {
            if (leaf(path).equals(name)) {
                return path;
            }
        }
        return null;
    }

    private static Set<String> root(final List<String> paths) {
        int shallowest = Integer.MAX_VALUE;
        final List<String> roots = new ArrayList<>();
        for (final String path : paths) {
            final int depth = depth(path);
            if (depth < shallowest) {
                roots.clear();
                shallowest = depth;
            }
            if (depth == shallowest) {
                roots.add(path);
            }
        }
        return Set.copyOf(roots);
    }

    private static String leaf(final String path) {
        final int slash = path.lastIndexOf('/');
        return (slash < 0 ? path : path.substring(slash + 1)).toLowerCase(Locale.ROOT);
    }

    private static int depth(final String path) {
        int depth = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') {
                depth++;
            }
        }
        return depth;
    }
}
