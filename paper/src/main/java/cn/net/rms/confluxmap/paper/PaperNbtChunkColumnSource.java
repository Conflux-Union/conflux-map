package cn.net.rms.confluxmap.paper;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.net.PackedBits;
import cn.net.rms.confluxmap.core.predict.CubiomesBiomeIds;
import cn.net.rms.confluxmap.server.ChunkColumnSource;
import java.util.ArrayList;
import java.util.List;
import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.ListTag;
import net.querz.nbt.tag.StringTag;

/** Serialized Anvil chunk implementation of the platform-neutral summary input seam. */
final class PaperNbtChunkColumnSource implements ChunkColumnSource {
    private final boolean generated;
    private final long revision;
    private final int bottomY;
    private final long[] heights;
    private final long[] oceanFloor;
    private final int[] legacyBiomes;
    private final List<Section> sections;

    PaperNbtChunkColumnSource(final CompoundTag root) {
        if (root == null) {
            generated = false;
            revision = 0L;
            bottomY = 0;
            heights = new long[0];
            oceanFloor = new long[0];
            legacyBiomes = new int[0];
            sections = List.of();
            return;
        }
        final CompoundTag level = compound(root, "Level");
        final boolean legacy = level != null;
        final CompoundTag chunk = legacy ? level : root;
        final String status = string(chunk, "Status");
        final CompoundTag heightmaps = compoundOrEmpty(chunk, "Heightmaps");
        final long[] motionBlocking = longs(heightmaps, "MOTION_BLOCKING");
        generated = ("full".equals(status) || "minecraft:full".equals(status))
            && motionBlocking.length != 0;
        revision = generated ? longValue(chunk, "LastUpdate") : 0L;
        bottomY = legacy ? 0 : integer(chunk, "yPos") * 16;
        heights = generated ? motionBlocking : new long[0];
        oceanFloor = generated ? longs(heightmaps, "OCEAN_FLOOR") : new long[0];
        legacyBiomes = generated ? ints(chunk, "Biomes") : new int[0];
        sections = generated
            ? parseSections(compoundList(chunk, legacy ? "Sections" : "sections"))
            : List.of();
    }

    @Override
    public boolean generated() {
        return generated;
    }

    @Override
    public long revision() {
        return revision;
    }

    @Override
    public int bottomY() {
        return bottomY;
    }

    @Override
    public int motionBlockingHeight(final int x, final int z) {
        return bottomY + PackedBits.decode(heights, 9, z * 16 + x);
    }

    @Override
    public int oceanFloorHeight(final int x, final int z) {
        return oceanFloor.length == 0
            ? NO_HEIGHT
            : bottomY + PackedBits.decode(oceanFloor, 9, z * 16 + x);
    }

    @Override
    public String blockNameAt(final int x, final int y, final int z) {
        final Section section = sectionAt(y);
        return section == null ? "minecraft:air" : section.names[paletteIndex(section, x, y, z)];
    }

    @Override
    public SurfaceKind fluidKindAt(final int x, final int y, final int z) {
        final Section section = sectionAt(y);
        return section == null
            ? SurfaceKind.UNKNOWN
            : section.fluidKinds[paletteIndex(section, x, y, z)];
    }

    @Override
    public int biomeIdAt(final int x, final int y, final int z) {
        if (legacyBiomes.length != 0) {
            final int quartY = Math.max(0, Math.min(63, Math.floorDiv(y, 4)));
            final int index = (x >>> 2) + ((z >>> 2) * 4) + quartY * 16;
            return index >= 0 && index < legacyBiomes.length ? legacyBiomes[index] : 1;
        }
        final int sectionY = Math.floorDiv(y, 16);
        for (final Section section : sections) {
            if (section.y != sectionY) {
                continue;
            }
            final int quartY = Math.floorMod(y, 16) >>> 2;
            final int index = (quartY * 4 + (z >>> 2)) * 4 + (x >>> 2);
            final int paletteIndex = section.biomeData.length == 0
                ? 0
                : PackedBits.decode(section.biomeData, section.biomeBits, index);
            return section.biomeIds[Math.min(paletteIndex, section.biomeIds.length - 1)];
        }
        return 1;
    }

    private static List<Section> parseSections(final ListTag<CompoundTag> list) {
        final List<Section> result = new ArrayList<>(list.size());
        for (final CompoundTag section : list) {
            final int y = byteValue(section, "Y");
            final CompoundTag modernStates = compound(section, "block_states");
            final boolean modern = modernStates != null;
            final CompoundTag blockStates = modern ? modernStates : section;
            final ListTag<CompoundTag> palette = compoundList(
                blockStates, modern ? "palette" : "Palette"
            );
            final String[] names = new String[Math.max(1, palette.size())];
            final SurfaceKind[] fluidKinds = new SurfaceKind[names.length];
            for (int p = 0; p < palette.size(); p++) {
                final CompoundTag entry = palette.get(p);
                names[p] = string(entry, "Name");
                fluidKinds[p] = fluidKind(names[p], compoundOrEmpty(entry, "Properties"));
            }
            if (palette.size() == 0) {
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
                Math.max(1, bitsFor(biomeIds.length))
            ));
        }
        return result;
    }

    private Section sectionAt(final int y) {
        final int sectionY = Math.floorDiv(y, 16);
        for (final Section section : sections) {
            if (section.y == sectionY) {
                return section;
            }
        }
        return null;
    }

    private static int paletteIndex(final Section section, final int x, final int y, final int z) {
        final int localIndex = (Math.floorMod(y, 16) * 16 + z) * 16 + x;
        final int decoded = section.states.length == 0
            ? 0
            : PackedBits.decode(section.states, section.bits, localIndex);
        return Math.min(decoded, section.names.length - 1);
    }

    private static SurfaceKind fluidKind(final String name, final CompoundTag properties) {
        if ("true".equals(string(properties, "waterlogged"))
            || name.contains("water") || "minecraft:kelp".equals(name)
            || "minecraft:kelp_plant".equals(name) || "minecraft:seagrass".equals(name)
            || "minecraft:tall_seagrass".equals(name) || "minecraft:bubble_column".equals(name)
            || "minecraft:sea_pickle".equals(name)) {
            return SurfaceKind.WATER;
        }
        return name.contains("lava") ? SurfaceKind.LAVA : SurfaceKind.UNKNOWN;
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

    private static int biomeId(final String name) {
        final int separator = name.indexOf(':');
        final String path = separator >= 0 ? name.substring(separator + 1) : name;
        return CubiomesBiomeIds.idForName(path).orElse(1);
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

    private record Section(
        int y,
        String[] names,
        SurfaceKind[] fluidKinds,
        long[] states,
        int bits,
        int[] biomeIds,
        long[] biomeData,
        int biomeBits
    ) {
    }
}
