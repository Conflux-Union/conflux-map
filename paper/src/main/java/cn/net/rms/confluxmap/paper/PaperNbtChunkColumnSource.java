package cn.net.rms.confluxmap.paper;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.server.DecodedChunkColumn;
import java.util.ArrayList;
import java.util.List;
import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.ListTag;
import net.querz.nbt.tag.StringTag;
import net.querz.nbt.tag.Tag;

/** Serialized Anvil chunk implementation of the platform-neutral summary input seam. */
final class PaperNbtChunkColumnSource extends DecodedChunkColumn {
    private static final Parsed EMPTY =
        new Parsed(false, 0L, 0, new long[0], new long[0], new int[0], List.of());

    PaperNbtChunkColumnSource(final CompoundTag root) {
        this(root == null ? EMPTY : parse(root));
    }

    private PaperNbtChunkColumnSource(final Parsed parsed) {
        super(parsed.generated(), parsed.revision(), parsed.bottomY(), parsed.heights(),
            parsed.oceanFloor(), parsed.legacyBiomes(), parsed.sections());
    }

    private static Parsed parse(final CompoundTag root) {
        final CompoundTag level = compound(root, "Level");
        final boolean legacy = level != null;
        final CompoundTag chunk = legacy ? level : root;
        final String status = string(chunk, "Status");
        final CompoundTag heightmaps = compoundOrEmpty(chunk, "Heightmaps");
        final long[] motionBlocking = longs(heightmaps, "MOTION_BLOCKING");
        final boolean generated = ("full".equals(status) || "minecraft:full".equals(status))
            && motionBlocking.length != 0;
        return new Parsed(
            generated,
            generated ? longValue(chunk, "LastUpdate") : 0L,
            legacy ? 0 : integer(chunk, "yPos") * 16,
            generated ? motionBlocking : new long[0],
            generated ? longs(heightmaps, "OCEAN_FLOOR") : new long[0],
            generated ? ints(chunk, "Biomes") : new int[0],
            generated
                ? parseSections(compoundList(chunk, legacy ? "Sections" : "sections"))
                : List.of()
        );
    }

    private record Parsed(
        boolean generated,
        long revision,
        int bottomY,
        long[] heights,
        long[] oceanFloor,
        int[] legacyBiomes,
        List<Section> sections
    ) {
    }

    private static List<Section> parseSections(final ListTag<CompoundTag> list) {
        final List<Section> result = new ArrayList<>(list.size());
        for (final CompoundTag section : list) {
            final int y = byteValue(section, "Y");
            final CompoundTag modernStates = compound(section, "block_states");
            final boolean modern = modernStates != null;
            final CompoundTag blockStates = modern ? modernStates : section;
            final ListTag<?> palette = blockStates == null
                ? null : blockStates.getListTag(modern ? "palette" : "Palette");
            final int paletteSize = palette == null ? 0 : palette.size();
            final String[] names = new String[Math.max(1, paletteSize)];
            final SurfaceKind[] fluidKinds = new SurfaceKind[names.length];
            for (int p = 0; p < paletteSize; p++) {
                // Palette entry forms: {"Name": ..., "Properties": {...}} before 26.3 and
                // {"id": ..., "properties": {...}} after; default states are bare id strings.
                // Querz reads the raw bytes, so a mixed palette arrives with its strings still
                // wrapped as {"" : id} (ListTag#wrapIfNeeded on write) — vanilla's own reader
                // unwraps that form and never exposes it in memory.
                final Tag<?> entry = palette.get(p);
                if (entry instanceof StringTag stringEntry) {
                    names[p] = stringEntry.getValue();
                    fluidKinds[p] = fluidKind(names[p], null);
                } else if (entry instanceof CompoundTag compoundEntry) {
                    String name = string(compoundEntry, "Name");
                    if (name.isEmpty()) {
                        name = string(compoundEntry, "id");
                    }
                    if (name.isEmpty()) {
                        name = string(compoundEntry, "");
                    }
                    final CompoundTag properties = compound(compoundEntry, "properties");
                    names[p] = name;
                    fluidKinds[p] = properties != null
                        ? fluidKind(names[p], properties)
                        : fluidKind(names[p], compoundOrEmpty(compoundEntry, "Properties"));
                } else {
                    // Corrupted entry of an unexpected type: degrade to air instead of leaving
                    // a null name that NPEs in the downstream contains() checks.
                    names[p] = "minecraft:air";
                    fluidKinds[p] = SurfaceKind.UNKNOWN;
                }
            }
            if (paletteSize == 0) {
                names[0] = "minecraft:air";
                fluidKinds[0] = SurfaceKind.UNKNOWN;
            }
            final long[] states = longs(blockStates, modern ? "data" : "BlockStates");
            final CompoundTag biomeContainer = modern
                ? compoundOrEmpty(section, "biomes") : new CompoundTag();
            final ListTag<StringTag> biomePalette = stringList(biomeContainer, "palette");
            final int[] biomeIds = new int[Math.max(1, biomePalette.size())];
            for (int p = 0; p < biomePalette.size(); p++) {
                biomeIds[p] = biomeId(biomePalette.get(p).getValue());
            }
            if (biomePalette.size() == 0) {
                biomeIds[0] = 1;
            }
            result.add(new Section(
                y,
                names,
                fluidKinds,
                states,
                Math.max(4, bitsFor(names.length)),
                biomeIds,
                longs(biomeContainer, "data"),
                Math.max(1, bitsFor(biomeIds.length)),
                bytes(section, "BlockLight")
            ));
        }
        return result;
    }

    private static SurfaceKind fluidKind(final String name, final CompoundTag properties) {
        return fluidKind(name, "true".equals(string(properties, "waterlogged")));
    }

    private static CompoundTag compound(final CompoundTag tag, final String key) {
        return tag == null ? null : tag.getCompoundTag(key);
    }

    private static CompoundTag compoundOrEmpty(final CompoundTag tag, final String key) {
        final CompoundTag value = compound(tag, key);
        return value == null ? new CompoundTag() : value;
    }

    private static String string(final CompoundTag tag, final String key) {
        return tag == null ? "" : tag.getString(key).orElse("");
    }

    private static int integer(final CompoundTag tag, final String key) {
        return tag == null ? 0 : tag.getInt(key).orElse(0);
    }

    private static int byteValue(final CompoundTag tag, final String key) {
        return tag == null ? 0 : tag.getByte(key).orElse((byte) 0);
    }

    private static long longValue(final CompoundTag tag, final String key) {
        return tag == null ? 0L : tag.getLong(key).orElse(0L);
    }

    private static long[] longs(final CompoundTag tag, final String key) {
        return tag == null ? new long[0] : tag.getLongArray(key).orElseGet(() -> new long[0]);
    }

    private static int[] ints(final CompoundTag tag, final String key) {
        return tag == null ? new int[0] : tag.getIntArray(key).orElseGet(() -> new int[0]);
    }

    private static byte[] bytes(final CompoundTag tag, final String key) {
        return tag == null ? new byte[0] : tag.getByteArray(key).orElseGet(() -> new byte[0]);
    }

    private static ListTag<CompoundTag> compoundList(final CompoundTag tag, final String key) {
        final ListTag<?> list = tag == null ? null : tag.getListTag(key);
        if (list == null || !CompoundTag.class.equals(list.getTypeClass())) {
            return new ListTag<>(CompoundTag.class);
        }
        return list.asCompoundTagList();
    }

    private static ListTag<StringTag> stringList(final CompoundTag tag, final String key) {
        final ListTag<?> list = tag == null ? null : tag.getListTag(key);
        if (list == null || !StringTag.class.equals(list.getTypeClass())) {
            return new ListTag<>(StringTag.class);
        }
        return list.asStringTagList();
    }
}
