package cn.net.rms.confluxmap.core.xaeroimport;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Test-side encoder for the region-stream grammar of
 * {@code docs/reference-specs/xaero-world-map-region-format.md}. It exists to
 * build fixtures for {@link XaeroRegionReader}; bytes written here follow the
 * spec's writer order, and the real-sample integration test guards against the
 * two agreeing on a wrong interpretation.
 */
final class XaeroRegionWriter {
    private final XaeroSaveFormat format;
    private final Map<XaeroBlockState, Integer> statePalette = new LinkedHashMap<>();
    private final Map<String, Integer> biomePalette = new LinkedHashMap<>();

    XaeroRegionWriter(final XaeroSaveFormat format) {
        this.format = format;
    }

    /**
     * One tile to write: 256 columns indexed {@code z * 16 + x}, placed at the
     * given chunk/tile slot inside the region.
     */
    record Tile(int chunkXInRegion, int chunkZInRegion, int tileX, int tileZ, XaeroColumn[] columns) {}

    void write(final OutputStream raw, final List<Tile> tiles) throws IOException {
        final ZipOutputStream zip = new ZipOutputStream(raw);
        zip.putNextEntry(new ZipEntry("region.xaero"));
        writeStream(zip, tiles);
        zip.closeEntry();
        zip.finish();
    }

    /** Writes the same stream without zip framing, mirroring the legacy bare {@code .xaero} files. */
    void writeBare(final OutputStream raw, final List<Tile> tiles) throws IOException {
        writeStream(raw, tiles);
    }

    private void writeStream(final OutputStream out, final List<Tile> tiles) throws IOException {
        final DataOutputStream data = new DataOutputStream(out);
        data.writeByte(XaeroSaveFormat.VERSION_MARKER);
        data.writeInt(format.encoded());
        for (final Tile tile : tiles) {
            data.writeByte(tile.chunkXInRegion() << 4 | tile.chunkZInRegion());
            writeChunk(data, tile);
        }
        data.flush();
    }

    private void writeChunk(final DataOutputStream out, final Tile tile) throws IOException {
        // Tile fields are positional: tileX 0..3 outer, tileZ 0..3 inner, matching the reader.
        for (int tileX = 0; tileX < 4; tileX++) {
            for (int tileZ = 0; tileZ < 4; tileZ++) {
                if (tileX != tile.tileX() || tileZ != tile.tileZ()) {
                    out.writeInt(-1);
                    continue;
                }
                // The first pixel's params integer doubles as the tile marker, so
                // writeColumn's params write IS the marker write for that pixel.
                boolean first = true;
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        writeColumn(out, tile.columns()[z * 16 + x], first);
                        first = false;
                    }
                }
                if (format.modernFraming()) {
                    out.writeByte(1);
                }
                if (format.hasCaveStart()) {
                    out.writeInt(XaeroTileData.SURFACE_CAVE_START);
                }
                if (format.hasCaveDepth()) {
                    out.writeByte(30);
                }
            }
        }
    }

    private void writeColumn(final DataOutputStream out, final XaeroColumn column, final boolean firstPixel)
            throws IOException {
        final boolean grass = column.isGrassSurface();
        final boolean inlineState = !grass && !statePalette.containsKey(column.surface());
        final boolean inlineBiome = column.biomeId() != null && !biomePalette.containsKey(column.biomeId());

        int params = 0;
        if (!grass) {
            params |= 1;
        }
        if (!column.overlays().isEmpty()) {
            params |= 2;
        }
        if (inlineState) {
            params |= 1 << 21;
        }
        if (column.hasTopHeight() && format.modernFraming()) {
            params |= 1 << 24;
        }
        if (column.biomeId() != null) {
            params |= 1 << 20;
        }
        if (inlineBiome) {
            params |= 1 << 22;
        }
        params |= (column.light() & 0xF) << 8;
        final int encodedHeight = column.height() & 0xFFF;
        params |= (encodedHeight & 0xFF) << 12;
        if (format.modernFraming()) {
            params |= (encodedHeight >>> 8 & 0xF) << 25;
        } else {
            params |= (encodedHeight >>> 8 & 0xF) << 24;
        }
        out.writeInt(params);

        if (!grass) {
            writeStatePayload(out, column.surface(), inlineState);
        }
        if (column.hasTopHeight() && format.modernFraming()) {
            out.writeByte(column.topHeight() & 0xFF);
        }
        if (!column.overlays().isEmpty()) {
            out.writeByte(column.overlays().size());
            for (final XaeroOverlay overlay : column.overlays()) {
                writeOverlay(out, overlay);
            }
        }
        if (format.usesLegacyColors() && ((params >>> 2) & 3) == 3) {
            out.writeInt(0);
        }
        if (column.biomeId() != null) {
            if (inlineBiome) {
                out.writeUTF(column.biomeId());
                biomePalette.put(column.biomeId(), biomePalette.size());
            } else {
                out.writeInt(biomePalette.get(column.biomeId()));
            }
        }
    }

    private void writeStatePayload(final DataOutputStream out, final XaeroBlockState state, final boolean inline)
            throws IOException {
        if (inline) {
            out.writeByte(0x0A);
            out.writeUTF("");
            out.writeByte(0x08);
            out.writeUTF(format.renamedStateKeys() ? "id" : "Name");
            out.writeUTF(state.id());
            if (!state.properties().isEmpty()) {
                out.writeByte(0x0A);
                out.writeUTF(format.renamedStateKeys() ? "properties" : "Properties");
                for (final Map.Entry<String, String> e : state.properties().entrySet()) {
                    out.writeByte(0x08);
                    out.writeUTF(e.getKey());
                    out.writeUTF(e.getValue());
                }
                out.writeByte(0);
            }
            out.writeByte(0);
            statePalette.put(state, statePalette.size());
        } else {
            out.writeInt(statePalette.get(state));
        }
    }

    private void writeOverlay(final DataOutputStream out, final XaeroOverlay overlay) throws IOException {
        final boolean inline = !overlay.isWater() && !statePalette.containsKey(overlay.state());
        int params = 0;
        if (!overlay.isWater()) {
            params |= 1;
            if (inline) {
                params |= 1 << 10;
            }
        }
        params |= (overlay.light() & 0xF) << 4;
        if (format.overlayOpacityNibble()) {
            params |= (overlay.opacity() & 0xF) << 11;
        }
        out.writeInt(params);
        if (!overlay.isWater()) {
            writeStatePayload(out, overlay.state(), inline);
        }
    }
}
