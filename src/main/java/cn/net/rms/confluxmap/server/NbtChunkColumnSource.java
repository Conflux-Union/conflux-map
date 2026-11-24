package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.compat.Nbts;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;

/** Serialized-region implementation of {@link ChunkColumnSource}. */
final class NbtChunkColumnSource extends DecodedChunkColumn {
    private static final Parsed EMPTY =
        new Parsed(false, 0L, 0, new long[0], new long[0], new int[0], List.of());

    NbtChunkColumnSource(final NbtCompound root) {
        this(root == null ? EMPTY : parse(root));
    }

    private NbtChunkColumnSource(final Parsed parsed) {
        super(parsed.generated(), parsed.revision(), parsed.bottomY(), parsed.heights(),
            parsed.oceanFloor(), parsed.legacyBiomes(), parsed.sections());
    }

    private static Parsed parse(final NbtCompound root) {
        final boolean legacy = Nbts.hasCompound(root, "Level");
        final NbtCompound level = legacy ? Nbts.compound(root, "Level") : root;
        final String status = Nbts.string(level, "Status");
        final NbtCompound heightmaps = Nbts.compound(level, "Heightmaps");
        final long[] motionBlocking = Nbts.longArray(heightmaps, "MOTION_BLOCKING");
        final boolean generated = ("full".equals(status) || "minecraft:full".equals(status))
            && motionBlocking.length != 0;
        return new Parsed(
            generated,
            generated ? Nbts.longValue(level, "LastUpdate") : 0L,
            legacy ? 0 : Nbts.integer(level, "yPos") * 16,
            generated ? motionBlocking : new long[0],
            generated ? Nbts.longArray(heightmaps, "OCEAN_FLOOR") : new long[0],
            generated ? Nbts.intArray(level, "Biomes") : new int[0],
            generated
                ? parseSections(Nbts.list(level, legacy ? "Sections" : "sections", 10))
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

    private static List<Section> parseSections(final NbtList list) {
        final List<Section> result = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            final NbtCompound section = Nbts.compound(list, i);
            final int y = Nbts.byteValue(section, "Y");
            final boolean modern = Nbts.hasCompound(section, "block_states");
            final NbtCompound blockStates = modern ? Nbts.compound(section, "block_states") : section;
            final String paletteKey = modern ? "palette" : "Palette";
            final String dataKey = modern ? "data" : "BlockStates";
            final NbtList typedPalette = Nbts.list(blockStates, paletteKey, 10);
            // Versions before 1.21.5 pick the list by element type; 26.3 writes default states
            // as a string-typed palette, so a compound-typed miss falls back to a string read.
            final NbtList palette = typedPalette.isEmpty()
                ? Nbts.list(blockStates, paletteKey, 8)
                : typedPalette;
            final String[] names = new String[Math.max(1, palette.size())];
            final SurfaceKind[] fluidKinds = new SurfaceKind[names.length];
            for (int p = 0; p < palette.size(); p++) {
                // Palette entry forms: {"Name": ..., "Properties": {...}} before 26.3 and
                // {"id": ..., "properties": {...}} after; default states are bare id strings.
                // A mixed palette serializes its strings as {"" : id} (ListTag#wrapIfNeeded),
                // but vanilla's reader unwraps that form while loading, so in-memory lists hold
                // real strings next to the compounds and the empty-key probe only guards data
                // that never passed through a vanilla reader. The compound read must go first:
                // NbtList.getString returns the element's NBT text for compound entries on
                // older versions, so a string probe would mangle those.
                final NbtCompound entry = Nbts.compound(palette, p);
                String name = Nbts.string(entry, "Name");
                if (name.isEmpty()) {
                    name = Nbts.string(entry, "id");
                }
                if (name.isEmpty()) {
                    name = Nbts.string(entry, "");
                }
                final NbtCompound properties = Nbts.hasCompound(entry, "properties")
                    ? Nbts.compound(entry, "properties")
                    : Nbts.compound(entry, "Properties");
                names[p] = name.isEmpty() ? Nbts.string(palette, p) : name;
                fluidKinds[p] = fluidKind(names[p], properties);
            }
            if (palette.isEmpty()) {
                names[0] = "minecraft:air";
                fluidKinds[0] = SurfaceKind.UNKNOWN;
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
                fluidKinds,
                states,
                bits,
                biomeIds,
                biomeData,
                Math.max(1, bitsFor(biomeIds.length)),
                Nbts.byteArray(section, "BlockLight")
            ));
        }
        return result;
    }

    private static SurfaceKind fluidKind(final String name, final NbtCompound properties) {
        return fluidKind(name, "true".equals(Nbts.string(properties, "waterlogged")));
    }
}
