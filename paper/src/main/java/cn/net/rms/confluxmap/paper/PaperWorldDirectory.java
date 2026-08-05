package cn.net.rms.confluxmap.paper;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.predict.WorldPreset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.World;

/** Stable, append-only dimension indexes for one Paper process lifetime. */
final class PaperWorldDirectory {
    record Entry(
        int index,
        World world,
        String dimensionId,
        String dimensionType,
        WorldPreset preset,
        Path regionDirectory
    ) {
        DimensionId parsedDimensionId() {
            return DimensionId.parse(dimensionId);
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Map<World, Entry> byWorld = new IdentityHashMap<>();

    synchronized void add(final World world) {
        if (world == null || byWorld.containsKey(world) || entries.size() >= 8) {
            return;
        }
        final String dimensionId = world.getKey().toString();
        final Entry entry = new Entry(
            entries.size(),
            world,
            dimensionId,
            world.getKey().getKey(),
            PaperWorldMetadata.detectPreset(world),
            resolveRegionDirectory(world)
        );
        entries.add(entry);
        byWorld.put(world, entry);
    }

    synchronized Entry at(final int index) {
        return index < 0 || index >= entries.size() ? null : entries.get(index);
    }

    synchronized Entry find(final World world) {
        return byWorld.get(world);
    }

    synchronized List<Entry> entries() {
        return List.copyOf(entries);
    }

    static Path resolveRegionDirectory(final World world) {
        final Path root = world.getWorldFolder().toPath();
        final Path namespaced = root.resolve("dimensions")
            .resolve(world.getKey().getNamespace())
            .resolve(world.getKey().getKey())
            .resolve("region");
        final List<Path> candidates = switch (world.getEnvironment()) {
            case NETHER -> List.of(
                namespaced, root.resolve("DIM-1/region"), root.resolve("region")
            );
            case THE_END -> List.of(
                namespaced, root.resolve("DIM1/region"), root.resolve("region")
            );
            default -> "minecraft:overworld".equals(world.getKey().toString())
                ? List.of(root.resolve("region"), namespaced)
                : List.of(namespaced, root.resolve("region"));
        };
        for (final Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return candidates.get(0);
    }
}
