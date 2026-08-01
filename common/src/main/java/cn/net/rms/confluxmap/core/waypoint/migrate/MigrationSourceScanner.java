package cn.net.rms.confluxmap.core.waypoint.migrate;

import cn.net.rms.confluxmap.core.model.DimensionId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.Logger;

/**
 * Discovers Xaero's Minimap and VoxelMap waypoint data belonging to the
 * current world/server, per {@code docs/reference-specs/waypoint-storage-formats.md}.
 *
 * <p>Both mods changed their storage location over time, so every known root
 * is probed: for Xaero the modern {@code xaero/minimap} plus the 1.17-era
 * {@code XaeroWaypoints} (same internal layout; a same-named container in the
 * legacy root is the stale pre-migration copy and is skipped), for VoxelMap
 * {@code voxelmap} plus the old {@code mods/mamiyaotaru/voxelmap} (consulted
 * only when the new location has no matching file, mirroring the mod).
 *
 * <p>Each Xaero sub-world (multiworld id) becomes its own
 * {@link MigrationSource} so proxy-server players don't blend waypoints from
 * unrelated backend worlds into one import.
 */
public final class MigrationSourceScanner {
    private static final String XAERO_MULTIPLAYER_PREFIX = "Multiplayer_";
    /** Container used when Xaero's "differentiate by address" option is off. */
    private static final String XAERO_ANY_ADDRESS = "Any Address";
    private static final String XAERO_DEFAULT_FILE_STEM = "waypoints";
    private static final String VOXELMAP_EXTENSION = ".points";

    /** The identity of the world the user is currently in; exactly one field is set. */
    public record Context(String singleplayerSaveName, String serverAddress) {
        public static Context singleplayer(final String saveName) {
            return new Context(Objects.requireNonNull(saveName, "saveName"), null);
        }

        public static Context multiplayer(final String address) {
            return new Context(null, Objects.requireNonNull(address, "address"));
        }
    }

    private MigrationSourceScanner() {
    }

    public static List<MigrationSource> scan(final Path gameDir, final Context context, final Logger logger) {
        final List<MigrationSource> out = new ArrayList<>();
        final Set<String> seenContainers = new HashSet<>();
        scanXaeroRoot(gameDir.resolve("xaero").resolve("minimap"), context, seenContainers, out, logger);
        scanXaeroRoot(gameDir.resolve("XaeroWaypoints"), context, seenContainers, out, logger);
        final Set<String> seenPointsFiles = new HashSet<>();
        scanVoxelMapDir(gameDir.resolve("voxelmap"), context, seenPointsFiles, out, logger);
        scanVoxelMapDir(
            gameDir.resolve("mods").resolve("mamiyaotaru").resolve("voxelmap"),
            context, seenPointsFiles, out, logger
        );
        return out;
    }

    // --- Xaero (Format X) ---

    private static void scanXaeroRoot(
        final Path root,
        final Context context,
        final Set<String> seenContainers,
        final List<MigrationSource> out,
        final Logger logger
    ) {
        for (final Path container : listDirectory(root, Files::isDirectory, logger)) {
            final String name = container.getFileName().toString();
            if (!matchesXaeroContainer(name, context)) {
                continue;
            }
            if (!seenContainers.add(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            collectXaeroContainer(container, out, logger);
        }
    }

    private static boolean matchesXaeroContainer(final String folderName, final Context context) {
        if (context.singleplayerSaveName() != null) {
            return folderName.equalsIgnoreCase(escapeXaeroName(context.singleplayerSaveName()));
        }
        if (folderName.length() <= XAERO_MULTIPLAYER_PREFIX.length()
            || !folderName.regionMatches(true, 0, XAERO_MULTIPLAYER_PREFIX, 0, XAERO_MULTIPLAYER_PREFIX.length())) {
            return false;
        }
        final String suffix = folderName.substring(XAERO_MULTIPLAYER_PREFIX.length());
        return suffix.equalsIgnoreCase(xaeroAddressName(context.serverAddress()))
            || suffix.equalsIgnoreCase(xaeroLegacyAddressName(context.serverAddress()))
            || suffix.equalsIgnoreCase(XAERO_ANY_ADDRESS);
    }

    /** Format X §2 steps 2-4: strip port (IPv6-aware) and trailing dots, then escape. */
    private static String xaeroAddressName(final String address) {
        return xaeroFolderSuffix(stripPort(address.trim()));
    }

    /**
     * Builds before 23.9.0 cut the port at the first {@code :} even for IPv6
     * addresses (Format X version matrix), so folders written by most
     * 1.17.1-era builds carry the mangled name; match that shape too.
     */
    private static String xaeroLegacyAddressName(final String address) {
        final String trimmed = address.trim();
        final int colon = trimmed.indexOf(':');
        return xaeroFolderSuffix(colon < 0 ? trimmed : trimmed.substring(0, colon));
    }

    private static String xaeroFolderSuffix(final String host) {
        String cleaned = host;
        while (cleaned.endsWith(".")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return escapeXaeroName(cleaned.replace(":", "§"));
    }

    private static String stripPort(final String address) {
        final long colons = address.chars().filter(c -> c == ':').count();
        if (colons == 0) {
            return address;
        }
        if (colons > 1) {
            final int bracketEnd = address.lastIndexOf("]:");
            return bracketEnd >= 0 ? address.substring(0, bracketEnd + 1) : address;
        }
        return address.substring(0, address.indexOf(':'));
    }

    private static String escapeXaeroName(final String raw) {
        return raw.replace("_", "%us%").replace("/", "%fs%").replace("\\", "%bs%");
    }

    private static String decodeXaeroFolder(final String stored) {
        return stored.replace("%us%", "_").replace("%fs%", "/").replace("%bs%", "\\").replace("§", ":");
    }

    private static String decodeXaeroLabel(final String stored) {
        return stored.replace("%us%", "_").replace("§§", ":");
    }

    private static void collectXaeroContainer(
        final Path container,
        final List<MigrationSource> out,
        final Logger logger
    ) {
        final Map<String, List<ImportedWaypoint>> byMultiworldId = new LinkedHashMap<>();
        final Map<String, String> labels = new LinkedHashMap<>();
        for (final Path dimensionDir : listDirectory(container, Files::isDirectory, logger)) {
            final DimensionId dimension = parseXaeroDimensionFolder(dimensionDir.getFileName().toString());
            if (dimension == null) {
                continue;
            }
            for (final Path file : listDirectory(dimensionDir, Files::isRegularFile, logger)) {
                final String fileName = file.getFileName().toString();
                if (!fileName.endsWith(".txt")) {
                    continue;
                }
                final List<ImportedWaypoint> waypoints =
                    XaeroWaypointsParser.parse(readLines(file, logger), dimension);
                if (waypoints.isEmpty()) {
                    continue;
                }
                final String stem = fileName.substring(0, fileName.length() - ".txt".length());
                final String[] idAndLabel = splitMultiworldStem(stem);
                byMultiworldId.computeIfAbsent(idAndLabel[0], key -> new ArrayList<>()).addAll(waypoints);
                labels.putIfAbsent(idAndLabel[0], idAndLabel[1]);
            }
        }
        if (byMultiworldId.isEmpty()) {
            return;
        }
        final String containerDisplay = decodeXaeroFolder(container.getFileName().toString());
        final boolean multipleSubWorlds = byMultiworldId.size() > 1;
        for (final String id : orderedMultiworldIds(container, byMultiworldId, logger)) {
            final String label = labels.get(id);
            final String subWorld = label.isEmpty() ? (id.isEmpty() ? "default" : id) : label;
            final String display = multipleSubWorlds
                ? containerDisplay + " · " + subWorld
                : containerDisplay;
            out.add(new MigrationSource(
                MigrationSource.Mod.XAERO, display, container, byMultiworldId.get(id)
            ));
        }
    }

    /** Format X §4: the default file is plain {@code waypoints}; otherwise {@code <id>_<label>}. */
    private static String[] splitMultiworldStem(final String stem) {
        if (stem.equals(XAERO_DEFAULT_FILE_STEM)) {
            return new String[] {"", ""};
        }
        final int underscore = stem.indexOf('_');
        if (underscore < 0) {
            return new String[] {stem, ""};
        }
        return new String[] {stem.substring(0, underscore), decodeXaeroLabel(stem.substring(underscore + 1))};
    }

    /** The container's configured default sub-world imports first; the rest keep discovery order. */
    private static List<String> orderedMultiworldIds(
        final Path container,
        final Map<String, List<ImportedWaypoint>> byMultiworldId,
        final Logger logger
    ) {
        final List<String> ordered = new ArrayList<>(byMultiworldId.keySet());
        final Path config = container.resolve("config.txt");
        if (!Files.isRegularFile(config)) {
            return ordered;
        }
        for (final String line : readLines(config, logger)) {
            if (line.startsWith("defaultMultiworldId:")) {
                final String defaultId = line.substring("defaultMultiworldId:".length()).trim();
                if (ordered.remove(defaultId)) {
                    ordered.add(0, defaultId);
                }
                break;
            }
        }
        return ordered;
    }

    private static DimensionId parseXaeroDimensionFolder(final String name) {
        // Legacy pre-dim% folder names the reference loader still recognizes.
        switch (name) {
            case "Overworld": return DimensionId.OVERWORLD;
            case "Nether": return DimensionId.NETHER;
            case "The End": return DimensionId.END;
            default: break;
        }
        if (!name.startsWith("dim%")) {
            return null;
        }
        final String key = name.substring("dim%".length());
        switch (key) {
            case "0": return DimensionId.OVERWORLD;
            case "-1": return DimensionId.NETHER;
            case "1": return DimensionId.END;
            default: break;
        }
        final int dollar = key.indexOf('$');
        if (dollar <= 0) {
            // Unknown legacy numeric id - no meaningful mapping exists.
            return null;
        }
        return DimensionId.of(key.substring(0, dollar), key.substring(dollar + 1).replace('%', '/'));
    }

    // --- VoxelMap (Format V) ---

    private static void scanVoxelMapDir(
        final Path dir,
        final Context context,
        final Set<String> seenPointsFiles,
        final List<MigrationSource> out,
        final Logger logger
    ) {
        for (final Path file : listDirectory(dir, Files::isRegularFile, logger)) {
            final String fileName = file.getFileName().toString();
            if (!fileName.endsWith(VOXELMAP_EXTENSION)) {
                continue;
            }
            final String stem = fileName.substring(0, fileName.length() - VOXELMAP_EXTENSION.length());
            if (!matchesVoxelMapFile(stem, context)) {
                continue;
            }
            if (!seenPointsFiles.add(stem.toLowerCase(Locale.ROOT))) {
                continue;
            }
            final List<ImportedWaypoint> waypoints = VoxelMapWaypointsParser.parse(readLines(file, logger));
            if (!waypoints.isEmpty()) {
                out.add(new MigrationSource(
                    MigrationSource.Mod.VOXELMAP, VoxelMapWaypointsParser.unescape(stem), file, waypoints
                ));
            }
        }
    }

    private static boolean matchesVoxelMapFile(final String stem, final Context context) {
        if (context.singleplayerSaveName() != null) {
            return stem.equalsIgnoreCase(escapeVoxelMapFileName(context.singleplayerSaveName()));
        }
        String base = context.serverAddress().toLowerCase(Locale.ROOT);
        if (base.endsWith(":25565")) {
            base = base.substring(0, base.length() - ":25565".length());
        }
        return stem.equalsIgnoreCase(escapeVoxelMapFileName(base));
    }

    private static String escapeVoxelMapFileName(final String raw) {
        return raw
            .replace("<", "~less~")
            .replace(">", "~greater~")
            .replace(":", "~colon~")
            .replace("\"", "~quote~")
            .replace("/", "~slash~")
            .replace("\\", "~backslash~")
            .replace("|", "~pipe~")
            .replace("?", "~question~")
            .replace("*", "~star~");
    }

    // --- shared ---

    private static List<Path> listDirectory(
        final Path dir,
        final java.util.function.Predicate<Path> filter,
        final Logger logger
    ) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(filter).sorted().collect(Collectors.toList());
        } catch (final IOException e) {
            logger.warn("Could not list waypoint migration directory {}", dir, e);
            return List.of();
        }
    }

    private static List<String> readLines(final Path file, final Logger logger) {
        try {
            return List.of(new String(Files.readAllBytes(file), StandardCharsets.UTF_8).split("\r?\n"));
        } catch (final IOException e) {
            logger.warn("Skipping unreadable waypoint migration file {}", file, e);
            return List.of();
        }
    }
}
