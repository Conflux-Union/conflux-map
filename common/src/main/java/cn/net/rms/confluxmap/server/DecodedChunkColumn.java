package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.net.PackedBits;
import cn.net.rms.confluxmap.core.predict.CubiomesBiomeIds;
import java.util.List;

/** Decoded query logic shared by the platform NBT adapters; subclasses keep only their NBT reads. */
public abstract class DecodedChunkColumn implements ChunkColumnSource {
    private final boolean generated;
    private final long revision;
    private final int bottomY;
    private final long[] heights;
    private final long[] oceanFloor;
    private final int[] legacyBiomes;
    private final List<Section> sections;

    protected DecodedChunkColumn(
        final boolean generated,
        final long revision,
        final int bottomY,
        final long[] heights,
        final long[] oceanFloor,
        final int[] legacyBiomes,
        final List<Section> sections
    ) {
        this.generated = generated;
        this.revision = revision;
        this.bottomY = bottomY;
        this.heights = heights;
        this.oceanFloor = oceanFloor;
        this.legacyBiomes = legacyBiomes;
        this.sections = sections;
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
        final Section section = sectionAt(y);
        return section == null ? "minecraft:air" : section.names[paletteIndex(section, x, y, z)];
    }

    @Override
    public SurfaceKind fluidKindAt(final int x, final int y, final int z) {
        final Section section = sectionAt(y);
        return section == null ? SurfaceKind.UNKNOWN : section.fluidKinds[paletteIndex(section, x, y, z)];
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

    @Override
    public int blockLightAbove(final int x, final int surfaceY, final int z) {
        final int y = surfaceY + 1;
        final Section section = sectionAt(y);
        return section == null ? 0 : nibble(section.blockLight, x, y, z);
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

    protected static int paletteIndex(final Section section, final int x, final int y, final int z) {
        final int localIndex = (Math.floorMod(y, 16) * 16 + z) * 16 + x;
        final int decoded = section.states.length == 0
            ? 0
            : PackedBits.decode(section.states, section.bits, localIndex);
        return Math.min(decoded, section.names.length - 1);
    }

    protected static SurfaceKind fluidKind(final String name, final boolean waterlogged) {
        if (waterlogged
            || name.contains("water") || "minecraft:kelp".equals(name) || "minecraft:kelp_plant".equals(name)
            || "minecraft:seagrass".equals(name) || "minecraft:tall_seagrass".equals(name)
            || "minecraft:bubble_column".equals(name) || "minecraft:sea_pickle".equals(name)) {
            return SurfaceKind.WATER;
        }
        return name.contains("lava") ? SurfaceKind.LAVA : SurfaceKind.UNKNOWN;
    }

    private static int columnIndex(final int x, final int z) {
        return z * 16 + x;
    }

    protected static int biomeId(final String name) {
        final int separator = name.indexOf(':');
        final String path = separator >= 0 ? name.substring(separator + 1) : name;
        return CubiomesBiomeIds.idForName(path).orElse(1);
    }

    protected static int bitsFor(final int size) {
        int bits = 0;
        int value = Math.max(1, size - 1);
        while (value > 0) {
            bits++;
            value >>>= 1;
        }
        return bits;
    }

    protected static int nibble(final byte[] values, final int x, final int y, final int z) {
        final int index = (Math.floorMod(y, 16) * 16 + z) * 16 + x;
        final int byteIndex = index >>> 1;
        if (byteIndex >= values.length) {
            return 0;
        }
        return (values[byteIndex] >>> ((index & 1) * 4)) & 0xF;
    }

    /** One serialized section decoded into neutral arrays. */
    public record Section(
        int y,
        String[] names,
        SurfaceKind[] fluidKinds,
        long[] states,
        int bits,
        int[] biomeIds,
        long[] biomeData,
        int biomeBits,
        byte[] blockLight
    ) {
    }
}
