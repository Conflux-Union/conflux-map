package cn.net.rms.confluxmap.core.xaeroimport;

import cn.net.rms.confluxmap.core.model.DimensionId;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * On-disk layout of the external world-map data roots: data roots, container
 * folders (one per world/server), dimension folders, multiworld layer folders
 * and region file listing. Grammar per {@code docs/reference-specs/
 * xaero-world-map-region-format.md} section 2.
 */
public final class XaeroWorldMapLayout {
    /** Data root of World Map releases before 1.39.0. */
    public static final String ROOT_LEGACY = "XaeroWorldMap";
    /** Data root since World Map 1.39.0. */
    public static final String ROOT_CURRENT = "xaero/world-map";

    private static final Pattern LEGACY_MULTIWORLD = Pattern.compile("^mw(-?\\d+),(-?\\d+),(-?\\d+)$");
    private static final String MULTIWORLD_DEFAULT = "mw$default";
    private static final String MULTIWORLD_PREFIX = "mw$";
    private static final String CUSTOM_MULTIWORLD_PREFIX = "cm$";

    /** One world/server dataset below a data root. */
    public record Container(String folderName, Path dir) {}

    /** One dimension's data below a container. */
    public record DimensionFolder(DimensionId dimension, Path dir) {}

    private XaeroWorldMapLayout() {
    }

    /** Existing data roots under the game directory, current root first. */
    public static List<Path> roots(final Path gameDir) {
        final List<Path> roots = new ArrayList<>(2);
        final Path current = gameDir.resolve(ROOT_CURRENT);
        final Path legacy = gameDir.resolve(ROOT_LEGACY);
        if (Files.isDirectory(current)) {
            roots.add(current);
        }
        if (Files.isDirectory(legacy)) {
            roots.add(legacy);
        }
        return roots;
    }

    public static List<Container> containers(final Path gameDir) {
        final List<Container> containers = new ArrayList<>();
        for (final Path root : roots(gameDir)) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
                for (final Path entry : entries) {
                    if (Files.isDirectory(entry)) {
                        containers.add(new Container(entry.getFileName().toString(), entry));
                    }
                }
            } catch (final IOException e) {
                // Unreadable root: skip it, the other root may still hold data.
            }
        }
        return containers;
    }

    public static List<DimensionFolder> dimensions(final Container container) {
        final List<DimensionFolder> dimensions = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(container.dir())) {
            for (final Path entry : entries) {
                if (!Files.isDirectory(entry)) {
                    continue;
                }
                final DimensionId dimension = dimensionFromFolderName(entry.getFileName().toString());
                if (dimension != null) {
                    dimensions.add(new DimensionFolder(dimension, entry));
                }
            }
        } catch (final IOException e) {
            // Unreadable container: nothing to import from it.
        }
        return dimensions;
    }

    /**
     * Maps a dimension folder name to a dimension id. The overworld folder is
     * literally {@code null}; custom dimensions escape {@code /} in the path
     * as {@code %}.
     */
    public static DimensionId dimensionFromFolderName(final String name) {
        return switch (name) {
            case "null" -> DimensionId.OVERWORLD;
            case "DIM-1" -> DimensionId.NETHER;
            case "DIM1" -> DimensionId.END;
            default -> {
                final int separator = name.indexOf('$');
                if (separator <= 0 || separator == name.length() - 1) {
                    yield null;
                }
                yield DimensionId.of(
                    name.substring(0, separator),
                    name.substring(separator + 1).replace('%', '/')
                );
            }
        };
    }

    /**
     * Picks the folder holding surface-layer region files for a dimension.
     * Multiplayer data lives in a multiworld subfolder ({@code mw$default}
     * preferred), while singleplayer data sits directly in the dimension
     * folder. Returns null when no unambiguous layer folder exists.
     */
    public static Path surfaceLayerFolder(final Path dimensionDir) {
        Path best = null;
        int candidates = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dimensionDir)) {
            for (final Path entry : entries) {
                if (!Files.isDirectory(entry)) {
                    continue;
                }
                final String name = entry.getFileName().toString();
                if (name.equals(MULTIWORLD_DEFAULT)) {
                    return entry;
                }
                if (name.startsWith(MULTIWORLD_PREFIX)
                    || name.startsWith(CUSTOM_MULTIWORLD_PREFIX)
                    || LEGACY_MULTIWORLD.matcher(name).matches()) {
                    candidates++;
                    best = entry;
                }
            }
        } catch (final IOException e) {
            return null;
        }
        if (candidates == 1) {
            return best;
        }
        if (candidates > 1) {
            return null;
        }
        return hasRegionFiles(dimensionDir) ? dimensionDir : null;
    }

    /** Region files directly inside a layer folder, in name order. */
    public static List<XaeroRegionFile> surfaceRegions(final Path layerFolder) throws IOException {
        final List<XaeroRegionFile> regions = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(layerFolder)) {
            for (final Path entry : entries) {
                if (Files.isRegularFile(entry)) {
                    final XaeroRegionFile region = XaeroRegionFile.parse(entry);
                    if (region != null) {
                        regions.add(region);
                    }
                }
            }
        }
        regions.sort((a, b) -> a.path().getFileName().compareTo(b.path().getFileName()));
        return regions;
    }

    private static boolean hasRegionFiles(final Path dir) {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (final Path entry : entries) {
                if (Files.isRegularFile(entry) && XaeroRegionFile.parse(entry) != null) {
                    return true;
                }
            }
        } catch (final IOException e) {
            return false;
        }
        return false;
    }

    /**
     * Server address encoded in a multiplayer container folder name, already
     * port-stripped and escaped; null when the container carries no address
     * ({@code Multiplayer_Unknown}, {@code Multiplayer_Any Address}) or is not
     * a multiplayer container.
     */
    public static String multiplayerAddress(final String containerFolderName) {
        if (!containerFolderName.startsWith("Multiplayer_")) {
            return null;
        }
        final String rest = containerFolderName.substring("Multiplayer_".length());
        if (rest.isEmpty() || rest.equals("Unknown") || rest.equals("Any Address")) {
            return null;
        }
        return rest;
    }

    /**
     * Undoes the container-name escaping so an address-derived container can be
     * matched against a connection address. Handles both the {@code ^x^} and
     * the newer {@code %x%} escape spellings; unknown escapes are left alone.
     */
    public static String unescapeContainerName(final String folderName) {
        String name = folderName;
        if (name.startsWith("^e^")) {
            name = name.substring(3);
        }
        return name.replace("§", ":")
            .replace("^us^", "_").replace("%us%", "_")
            .replace("^fs^", "/").replace("%fs%", "/")
            .replace("^bs^", "\\").replace("%bs%", "\\");
    }

    /**
     * Comparison form of a connection address for matching address-derived
     * containers: brackets stripped, port cut at the first colon the same way
     * the container naming does, lowercased.
     */
    public static String addressKey(final String address) {
        String host = address.trim();
        final int bracketEnd = host.indexOf(']');
        if (bracketEnd >= 0) {
            host = host.startsWith("[") ? host.substring(1, bracketEnd) : host.substring(0, bracketEnd);
        } else {
            final int portSeparator = host.indexOf(':');
            if (portSeparator >= 0) {
                host = host.substring(0, portSeparator);
            }
        }
        return host.toLowerCase(Locale.ROOT);
    }

    /**
     * Comparison form of a multiplayer container's address part. Xaero strips
     * the port itself, so no port cut happens here; escapes are undone and the
     * result lowercased to meet {@link #addressKey}.
     */
    public static String containerAddressKey(final String containerFolderName) {
        final String address = multiplayerAddress(containerFolderName);
        if (address == null) {
            return null;
        }
        String host = unescapeContainerName(address).trim();
        final int bracketEnd = host.indexOf(']');
        if (bracketEnd >= 0) {
            host = host.startsWith("[") ? host.substring(1, bracketEnd) : host.substring(0, bracketEnd);
        }
        return host.toLowerCase(Locale.ROOT);
    }
}
