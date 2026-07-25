package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.compat.Nbts;
import cn.net.rms.confluxmap.core.net.PackedBits;
import cn.net.rms.confluxmap.core.predict.CubiomesBiomeIds;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;

/** Serialized-region implementation of {@link ChunkColumnSource}. */
final class NbtChunkColumnSource implements ChunkColumnSource {
    private final boolean generated;
    private final long revision;
    private final int bottomY;
    private final long[] heights;
    private final long[] oceanFloor;
    private final int[] legacyBiomes;
    private final List<Section> sections;

    NbtChunkColumnSource(final NbtCompound root) {
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
        final boolean legacy = Nbts.hasCompound(root, "Level");
        final NbtCompound level = legacy ? Nbts.compound(root, "Level") : root;
        final String status = Nbts.string(level, "Status");
        final NbtCompound heightmaps = Nbts.compound(level, "Heightmaps");
        final long[] motionBlocking = Nbts.longArray(heightmaps, "MOTION_BLOCKING");
        generated = ("full".equals(status) || "minecraft:full".equals(status))
            && motionBlocking.length != 0;
        revision = generated ? Nbts.longValue(level, "LastUpdate") : 0L;
        bottomY = legacy ? 0 : Nbts.integer(level, "yPos") * 16;
        heights = generated ? motionBlocking : new long[0];
        oceanFloor = generated ? Nbts.longArray(heightmaps, "OCEAN_FLOOR") : new long[0];
        legacyBiomes = generated ? Nbts.intArray(level, "Biomes") : new int[0];
        final String sectionsKey = legacy ? "Sections" : "sections";
        sections = generated ? parseSections(Nbts.list(level, sectionsKey, 10)) : List.of();
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
        return bottomY + PackedBits.decode(heights, 9, columnIndex(x, z));
    }

    @Override
    public int oceanFloorHeight(final int x, final int z) {
        return oceanFloor.length == 0
            ? NO_HEIGHT
            : bottomY + PackedBits.decode(oceanFloor, 9, columnIndex(x, z));
    }

    @Override
    public String blockNameAt(final int x, final int y, final int z) {
        final int sectionY = Math.floorDiv(y, 16);
        final int localY = Math.floorMod(y, 16);
        for (final Section section : sections) {
            if (section.y != sectionY) {
                continue;
            }
            final int localIndex = (localY * 16 + z) * 16 + x;
            final int paletteIndex = section.states.length == 0
                ? 0
                : PackedBits.decode(section.states, section.bits, localIndex);
            return section.names[Math.min(paletteIndex, section.names.length - 1)];
        }
        return "minecraft:air";
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

    private static List<Section> parseSections(final NbtList list) {
        final List<Section> result = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            final NbtCompound section = Nbts.compound(list, i);
            final int y = Nbts.byteValue(section, "Y");
            final boolean modern = Nbts.hasCompound(section, "block_states");
            final NbtCompound blockStates = modern ? Nbts.compound(section, "block_states") : section;
            final String paletteKey = modern ? "palette" : "Palette";
            final String dataKey = modern ? "data" : "BlockStates";
            final NbtList palette = Nbts.list(blockStates, paletteKey, 10);
            final String[] names = new String[Math.max(1, palette.size())];
            for (int p = 0; p < palette.size(); p++) {
                names[p] = Nbts.string(Nbts.compound(palette, p), "Name");
            }
            if (palette.isEmpty()) {
                names[0] = "minecraft:air";
            }
            final long[] states = Nbts.longArray(blockStates, dataKey);
            final int bits = Math.max(4, bitsFor(names.length));
            final NbtCompound biomeContainer = modern ? Nbts.compound(section, "biomes") : new NbtCompound();
            final NbtList biomePalette = Nbts.list(biomeContainer, "palette", 8);
            final int[] biomeIds = new int[Math.max(1, biomePalette.size())];
            for (int p = 0; p < biomePalette.size(); p++) {
                biomeIds[p] = biomeId(Nbts.string(biomePalette, p));
            }
            if (biomePalette.isEmpty()) {
                biomeIds[0] = 1;
            }
            final long[] biomeData = Nbts.longArray(biomeContainer, "data");
            result.add(new Section(
                y,
                names,
                states,
                bits,
                biomeIds,
                biomeData,
                Math.max(1, bitsFor(biomeIds.length))
            ));
        }
        return result;
    }

    private static int columnIndex(final int x, final int z) {
        return z * 16 + x;
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
        long[] states,
        int bits,
        int[] biomeIds,
        long[] biomeData,
        int biomeBits
    ) {
    }
}
