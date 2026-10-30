package cn.net.rms.confluxmap.core.xaeroimport;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Decoder for one external world-map region byte stream (512x512 columns,
 * 32x32 Minecraft chunks). The grammar is documented in
 * {@code docs/reference-specs/xaero-world-map-region-format.md}; this class is
 * a direct implementation of sections 3-6 of that document.
 *
 * <p>Decoding is sink-based so a large region never has to be held in memory:
 * completed tiles are handed to the sink as they finish. A stream that ends
 * mid-structure is reported as truncated with the completed tiles kept; a
 * stream that violates the grammar throws {@link XaeroImportException} so the
 * caller can quarantine the file.
 */
public final class XaeroRegionReader {
    /** Receives decoded tiles in stream order. {@code chunkX/chunkZ} are global chunk coordinates. */
    public interface TileSink {
        void tile(int chunkX, int chunkZ, XaeroTileData tile);
    }

    public interface Result {
        /** The stream is not in the supported window; nothing was decoded. */
        record Skipped(String reason) implements Result {}

        record Decoded(int chunks, int tiles, boolean truncated) implements Result {}
    }

    private static final int CHUNKS_PER_SIDE = 8;
    private static final int TILES_PER_CHUNK_SIDE = 4;
    private static final int TILE_COLUMNS = 256;
    private static final int MAX_OVERLAYS = 10;
    private static final int DEFAULT_CAVE_DEPTH = 32;

    private XaeroRegionReader() {
    }

    /**
     * Decodes the region stream in {@code stream} (already positioned at the
     * first header byte). {@code regionX/regionZ} are the file-name region
     * coordinates used to place tiles in the world.
     */
    public static Result read(
        final InputStream stream,
        final int regionX,
        final int regionZ,
        final TileSink sink
    ) throws IOException {
        final DataInputStream in = new DataInputStream(stream);
        final int marker = in.readUnsignedByte();
        if (marker != XaeroSaveFormat.VERSION_MARKER) {
            return new Result.Skipped("unversioned stream (legacy 1.12-era data)");
        }
        final XaeroSaveFormat format = XaeroSaveFormat.ofEncoded(in.readInt());
        if (!format.supported()) {
            return new Result.Skipped(
                "unsupported save format " + format.major() + "." + format.minor()
            );
        }
        final Palettes palettes = new Palettes();
        int chunks = 0;
        int tiles = 0;
        boolean truncated = false;
        try {
            while (true) {
                final int chunkMarker = in.readUnsignedByte();
                if ((chunkMarker & 0x88) != 0) {
                    throw new XaeroImportException("invalid chunk marker " + chunkMarker);
                }
                tiles += readChunk(in, format, palettes, regionX, regionZ, chunkMarker, sink);
                chunks++;
            }
        } catch (final EOFException endOfStream) {
            // The format is EOF-terminated and tolerates truncation mid-structure;
            // completed tiles were already handed to the sink.
            truncated = true;
        }
        return new Result.Decoded(chunks, tiles, truncated);
    }

    private static int readChunk(
        final DataInputStream in,
        final XaeroSaveFormat format,
        final Palettes palettes,
        final int regionX,
        final int regionZ,
        final int chunkMarker,
        final TileSink sink
    ) throws IOException {
        final int chunkXInRegion = chunkMarker >>> 4;
        final int chunkZInRegion = chunkMarker & 0x7;
        int tiles = 0;
        for (int tileX = 0; tileX < TILES_PER_CHUNK_SIDE; tileX++) {
            for (int tileZ = 0; tileZ < TILES_PER_CHUNK_SIDE; tileZ++) {
                final int head = in.readInt();
                if (head == -1) {
                    continue;
                }
                // A present tile's marker integer doubles as its first pixel's params.
                final XaeroTileData tile = readTile(in, format, palettes, head);
                final int chunkX = regionX * 32 + chunkXInRegion * 4 + tileX;
                final int chunkZ = regionZ * 32 + chunkZInRegion * 4 + tileZ;
                sink.tile(chunkX, chunkZ, tile);
                tiles++;
            }
        }
        return tiles;
    }

    private static XaeroTileData readTile(
        final DataInputStream in,
        final XaeroSaveFormat format,
        final Palettes palettes,
        final int firstParams
    ) throws IOException {
        final XaeroColumn[] columns = new XaeroColumn[TILE_COLUMNS];
        // Pixel order is x outer, z inner; column arrays index z * 16 + x.
        int pixel = 0;
        int params = firstParams;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                columns[z * 16 + x] = readColumn(in, format, palettes, params);
                pixel++;
                if (pixel < TILE_COLUMNS) {
                    params = in.readInt();
                }
            }
        }
        final int interpretation = format.modernFraming() ? in.readUnsignedByte() : 0;
        final int caveStart = format.hasCaveStart() ? in.readInt() : XaeroTileData.SURFACE_CAVE_START;
        final int caveDepth = format.hasCaveDepth() ? in.readUnsignedByte() : DEFAULT_CAVE_DEPTH;
        return new XaeroTileData(interpretation, caveStart, caveDepth, columns);
    }

    private static XaeroColumn readColumn(
        final DataInputStream in,
        final XaeroSaveFormat format,
        final Palettes palettes,
        final int params
    ) throws IOException {
        final XaeroBlockState surface = readSurfaceState(in, format, palettes, params);
        final int height = readHeight(in, format, params);
        final int topHeight = format.modernFraming() && (params & (1 << 24)) != 0
            ? in.readUnsignedByte()
            : XaeroColumn.NO_TOP_HEIGHT;
        final List<XaeroOverlay> overlays = readOverlays(in, format, palettes, params);
        if (format.usesLegacyColors() && ((params >>> 2) & 3) == 3) {
            in.readInt();
        }
        final String biomeId = readBiome(in, format, palettes, params);
        return new XaeroColumn(surface, height, topHeight, (params >>> 8) & 0xF, biomeId, overlays);
    }
    /** Bit 0 clear means a grass surface with no state bytes; null encodes that. */
    private static XaeroBlockState readSurfaceState(
        final DataInputStream in,
        final XaeroSaveFormat format,
        final Palettes palettes,
        final int params
    ) throws IOException {
        if ((params & 1) == 0) {
            return null;
        }
        if ((params & (1 << 21)) != 0) {
            final XaeroBlockState state = XaeroNbt.readBlockState(in, format);
            palettes.states.add(state);
            return state;
        }
        return palettes.state(in.readInt());
    }

    private static int readHeight(final DataInputStream in, final XaeroSaveFormat format, final int params)
            throws IOException {
        if ((params & (1 << 6)) != 0) {
            return in.readUnsignedByte();
        }
        final int low = (params >>> 12) & 0xFF;
        final int high = format.modernFraming() ? (params >>> 25) & 0xF : (params >>> 24) & 0xF;
        final int height = low | high << 8;
        return (height & 0x800) != 0 ? height - 0x1000 : height;
    }

    private static List<XaeroOverlay> readOverlays(
        final DataInputStream in,
        final XaeroSaveFormat format,
        final Palettes palettes,
        final int params
    ) throws IOException {
        if ((params & 2) == 0) {
            return List.of();
        }
        final int count = in.readUnsignedByte();
        if (count > MAX_OVERLAYS) {
            throw new XaeroImportException("overlay count " + count + " exceeds " + MAX_OVERLAYS);
        }
        final List<XaeroOverlay> overlays = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            overlays.add(readOverlay(in, format, palettes));
        }
        return overlays;
    }

    private static XaeroOverlay readOverlay(
        final DataInputStream in,
        final XaeroSaveFormat format,
        final Palettes palettes
    ) throws IOException {
        final int params = in.readInt();
        XaeroBlockState state = null;
        if ((params & 1) != 0) {
            if ((params & (1 << 10)) != 0) {
                state = XaeroNbt.readBlockState(in, format);
                palettes.states.add(state);
            } else {
                state = palettes.state(in.readInt());
            }
        }
        final int light = params >>> 4 & 0xF;
        final int opacity;
        if (format.overlayOpacityNibble()) {
            opacity = params >>> 11 & 0xF;
        } else if ((params & (1 << 3)) != 0) {
            final int legacy = in.readInt();
            opacity = Math.max(0, Math.min(15, legacy));
        } else {
            opacity = 1;
        }
        // Legacy explicit payloads, in the order the grammar fixes them.
        if (format.minor() < 1 && (params & 2) != 0) {
            in.readInt();
        }
        final boolean legacyColorType2 = format.usesLegacyColors() && (params >>> 8 & 3) == 2;
        if (legacyColorType2 || (params & (1 << 2)) != 0) {
            in.readInt();
        }
        return new XaeroOverlay(state, light, opacity);
    }

    private static String readBiome(
        final DataInputStream in,
        final XaeroSaveFormat format,
        final Palettes palettes,
        final int params
    ) throws IOException {
        if (!format.biomePalettes() || (params & (1 << 20)) == 0) {
            return null;
        }
        if ((params & (1 << 22)) != 0) {
            if ((params & (1 << 23)) != 0) {
                // Numeric registry id: the format maps unknown ids to plains; we surface
                // "no biome" and let the consumer decide its own fallback.
                in.readInt();
                return null;
            }
            final String biomeId = in.readUTF();
            palettes.biomes.add(biomeId);
            return biomeId;
        }
        return palettes.biome(in.readInt());
    }

    /** Per-stream identity palettes; indexes are order of first inline occurrence. */
    private static final class Palettes {
        private final List<XaeroBlockState> states = new ArrayList<>();
        private final List<String> biomes = new ArrayList<>();

        XaeroBlockState state(final int index) throws IOException {
            if (index < 0 || index >= states.size()) {
                throw new XaeroImportException("state palette index " + index + " out of bounds");
            }
            return states.get(index);
        }

        String biome(final int index) throws IOException {
            if (index < 0 || index >= biomes.size()) {
                throw new XaeroImportException("biome palette index " + index + " out of bounds");
            }
            return biomes.get(index);
        }
    }
}
