package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.net.PackedBits;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;

/** Converts a serialized 1.17.1 chunk into a cheap surface-only summary without loading a chunk. */
public final class ChunkSummarizer {
    /**
     * Resolves a block id string (e.g. {@code minecraft:oak_planks}) to its vanilla map-colour
     * id, or a negative value when unknown. Kept as a seam so the registry-backed resolver
     * (which needs a bootstrapped Minecraft) stays out of this NBT-only class and its tests.
     */
    @FunctionalInterface
    public interface MapColorResolver {
        int mapColorId(String blockName);
    }

    private static final MapColorResolver UNRESOLVED = name -> -1;

    private final MapColorResolver mapColors;

    public ChunkSummarizer() {
        this(UNRESOLVED);
    }

    public ChunkSummarizer(final MapColorResolver mapColors) {
        this.mapColors = mapColors == null ? UNRESOLVED : mapColors;
    }

    public SummaryCodec.Chunk summarize(final NbtCompound root) {
        if (root == null || !root.contains("Level", 10)) {
            return SummaryCodec.Chunk.empty();
        }
        final NbtCompound level = root.getCompound("Level");
        // Region files contain partially-generated chunks as early as structure_starts. They
        // have no usable surface heightmap yet; treating them as generated produced 0-height
        // UNKNOWN corrections which punched transparent/black holes into valid predictions.
        if (!"full".equals(level.getString("Status"))) {
            return SummaryCodec.Chunk.empty();
        }
        final NbtCompound heightmaps = level.contains("Heightmaps", 10)
            ? level.getCompound("Heightmaps") : new NbtCompound();
        final long[] heights = heightmaps.getLongArray("MOTION_BLOCKING");
        if (heights.length == 0) {
            return SummaryCodec.Chunk.empty();
        }
        final long[] oceanFloor = heightmaps.getLongArray("OCEAN_FLOOR");
        final int[] biomes = level.contains("Biomes", 11) ? level.getIntArray("Biomes") : new int[0];
        final NbtList sections = level.contains("Sections", 9) ? level.getList("Sections", 10) : new NbtList();
        final List<Section> parsed = parseSections(sections);
        final SummaryCodec.Column[] columns = new SummaryCodec.Column[SummaryCodec.COLUMNS];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                final int index = z * 16 + x;
                final int top = heights.length == 0 ? 0 : PackedBits.decode(heights, 9, index);
                final int surfaceY = top - 1;
                final BlockInfo block = blockAt(parsed, x, surfaceY, z);
                final int biome = biomeAt(biomes, x, z, surfaceY);
                final boolean fluidSurface = block.kind == SurfaceKind.WATER || block.kind == SurfaceKind.ICE;
                final int fluidDepth;
                if (!fluidSurface) {
                    fluidDepth = 0;
                } else if (block.kind == SurfaceKind.WATER && oceanFloor.length != 0) {
                    fluidDepth = clamp(top - PackedBits.decode(oceanFloor, 9, index));
                } else {
                    fluidDepth = scanFluidDepth(parsed, x, surfaceY, z);
                }
                columns[index] = new SummaryCodec.Column(
                    biome & 255, clampShort(surfaceY), block.kind.ordinal(), block.mapColorId, fluidDepth
                );
            }
        }
        final long revision = level.contains("LastUpdate", 4) ? level.getLong("LastUpdate") : 0L;
        return new SummaryCodec.Chunk(true, revision, columns);
    }

    private static List<Section> parseSections(final NbtList list) {
        final List<Section> result = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            final NbtCompound section = list.getCompound(i);
            final int y = section.getByte("Y");
            final NbtList palette = section.contains("Palette", 9) ? section.getList("Palette", 10) : new NbtList();
            final String[] names = new String[Math.max(1, palette.size())];
            for (int p = 0; p < palette.size(); p++) {
                names[p] = palette.getCompound(p).getString("Name");
            }
            if (palette.isEmpty()) {
                names[0] = "minecraft:air";
            }
            final long[] states = section.contains("BlockStates", 12) ? section.getLongArray("BlockStates") : new long[0];
            final int bits = Math.max(4, bitsFor(names.length));
            result.add(new Section(y, names, states, bits));
        }
        return result;
    }

    private BlockInfo blockAt(final List<Section> sections, final int x, final int y, final int z) {
        return classify(blockNameAt(sections, x, y, z), mapColors);
    }

    private static String blockNameAt(final List<Section> sections, final int x, final int y, final int z) {
        final int sectionY = Math.floorDiv(y, 16);
        final int localY = Math.floorMod(y, 16);
        for (final Section section : sections) {
            if (section.y != sectionY) {
                continue;
            }
            final int localIndex = (localY * 16 + z) * 16 + x;
            final int paletteIndex = section.states.length == 0 ? 0 : PackedBits.decode(section.states, section.bits, localIndex);
            return section.names[Math.min(paletteIndex, section.names.length - 1)];
        }
        return "minecraft:air";
    }

    private static int scanFluidDepth(final List<Section> sections, final int x, final int surfaceY, final int z) {
        int floorY = surfaceY - 1;
        while (floorY >= 0 && isUnderwaterColumnBlock(blockNameAt(sections, x, floorY, z))) {
            floorY--;
        }
        return clamp(surfaceY - floorY);
    }

    private static boolean isUnderwaterColumnBlock(final String name) {
        return name.contains("water") || name.contains("bubble_column") || isKelp(name)
            || name.contains("seagrass") || name.contains("sea_pickle") || name.contains("coral_fan");
    }

    private static boolean isKelp(final String name) {
        return "minecraft:kelp".equals(name) || "minecraft:kelp_plant".equals(name);
    }

    private static int biomeAt(final int[] biomes, final int x, final int z, final int top) {
        if (biomes.length == 0) {
            return 1;
        }
        final int quartY = Math.max(0, Math.min(63, Math.floorDiv(top, 4)));
        final int index = (x >>> 2) + ((z >>> 2) * 4) + quartY * 16;
        return index >= 0 && index < biomes.length ? biomes[index] : 1;
    }

    /** Classifies one block id string into its surface kind and map colour; also used by {@code FlatWorldBaseline}. */
    public static BlockInfo classify(final String value, final MapColorResolver mapColors) {
        final String name = value == null ? "minecraft:air" : value;
        final MapColorResolver resolver = mapColors == null ? UNRESOLVED : mapColors;
        final SurfaceKind kind;
        final int color;
        if (name.contains("water") || isKelp(name)) {
            kind = SurfaceKind.WATER;
            color = 12;
        } else if (name.contains("lava")) {
            kind = SurfaceKind.LAVA;
            color = 4;
        } else if (name.contains("leaves") || name.contains("vine")) {
            kind = SurfaceKind.FOLIAGE;
            color = resolveOr(resolver, name, 7);
        } else if (name.contains("snow") || name.contains("powder_snow")) {
            kind = SurfaceKind.SNOW;
            color = resolveOr(resolver, name, 3);
        } else if (name.contains("ice")) {
            kind = SurfaceKind.ICE;
            color = resolveOr(resolver, name, 12);
        } else if (name.endsWith("sand") || name.contains("sandstone")) {
            kind = SurfaceKind.SAND;
            color = resolveOr(resolver, name, 2);
        } else if (name.contains("bedrock")) {
            kind = SurfaceKind.BEDROCK_CEILING;
            color = resolveOr(resolver, name, 11);
        } else if (name.endsWith("air") || name.endsWith("cave_air") || name.endsWith("void_air")) {
            kind = SurfaceKind.UNKNOWN;
            color = ProtoColor.NONE;
        } else {
            kind = SurfaceKind.LAND;
            // Heuristic fallback for when no registry resolver is wired (tests) or the name is
            // unknown to it: enough to flag non-natural block names through the same wire.
            color = resolveOr(resolver, name, name.contains("stone") || name.contains("brick") || name.contains("concrete") ? 11 : 1);
        }
        return new BlockInfo(kind, color);
    }

    /**
     * The registry colour when available, else the heuristic fallback. Id 0 (CLEAR, e.g. glass)
     * also falls back: the client renders corrected pixels straight from the map-colour table,
     * and a transparent pixel would punch a hole where a block demonstrably exists.
     */
    private static int resolveOr(final MapColorResolver mapColors, final String name, final int fallback) {
        final int resolved = mapColors.mapColorId(name);
        return resolved > 0 ? resolved : fallback;
    }

    private static int bitsFor(final int size) {
        int bits = 0;
        int value = Math.max(1, size - 1);
        while (value > 0) {
            bits++;
            value >>>= 1;
        }
        return bits;
    }

    private static int clamp(final int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static short clampShort(final int value) {
        return (short) Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, value));
    }

    private record Section(int y, String[] names, long[] states, int bits) {
    }

    /** One classified block: its map surface kind and vanilla map colour id. */
    public record BlockInfo(SurfaceKind kind, int mapColorId) {
    }

    private static final class ProtoColor {
        private static final int NONE = 255;
    }
}
