package cn.net.rms.confluxmap.core.xaeroimport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Round-trip and error-path coverage for {@link XaeroRegionReader} against spec-grammar fixtures. */
class XaeroRegionReaderTest {
    @TempDir
    Path tempDir;

    private static Map<Long, XaeroTileData> read(
        final byte[] bytes,
        final int regionX,
        final int regionZ
    ) throws IOException {
        final Map<Long, XaeroTileData> tiles = new LinkedHashMap<>();
        final XaeroRegionReader.Result result = XaeroRegionReader.read(
            new ByteArrayInputStream(bytes), regionX, regionZ,
            (chunkX, chunkZ, tile) -> tiles.put(((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL), tile)
        );
        assertInstanceOf(XaeroRegionReader.Result.Decoded.class, result);
        return tiles;
    }

    private static XaeroRegionWriter.Tile fullTile(
        final int chunkXInRegion,
        final int chunkZInRegion,
        final XaeroColumn[] columns
    ) {
        return new XaeroRegionWriter.Tile(chunkXInRegion, chunkZInRegion, 1, 2, columns);
    }

    private static XaeroColumn[] single(final XaeroColumn column) {
        final XaeroColumn[] columns = new XaeroColumn[256];
        for (int i = 0; i < columns.length; i++) {
            columns[i] = column;
        }
        return columns;
    }

    @Test
    void roundTripsGrassWaterAndPaletteReuse() throws IOException {
        final XaeroSaveFormat format = new XaeroSaveFormat(5, 8);
        final XaeroRegionWriter writer = new XaeroRegionWriter(format);
        final XaeroColumn grass = new XaeroColumn(
            null, 64, XaeroColumn.NO_TOP_HEIGHT, 0, "minecraft:plains", List.of());
        final XaeroColumn water = new XaeroColumn(
            XaeroBlockState.of("minecraft:sand"), 60, 63, 7, "minecraft:ocean", List.of(
                new XaeroOverlay(null, 9, 3)));
        final XaeroColumn stone = new XaeroColumn(
            XaeroBlockState.of("minecraft:stone"), 100, XaeroColumn.NO_TOP_HEIGHT, 0,
            "minecraft:plains", List.of());
        final XaeroColumn props = new XaeroColumn(
            new XaeroBlockState("minecraft:oak_leaves", Map.of("distance", "7", "persistent", "false")),
            70, XaeroColumn.NO_TOP_HEIGHT, 0, "minecraft:forest", List.of());
        // Water repeats in every tile so the palette-index path is exercised too.
        final XaeroColumn[] columnsA = single(grass);
        columnsA[0] = water;
        columnsA[1] = stone;
        columnsA[2] = props;
        final XaeroColumn[] columnsB = single(water);
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        writer.writeBare(bytes,List.of(fullTile(0, 0, columnsA), fullTile(7, 7, columnsB)));

        final Map<Long, XaeroTileData> tiles = read(bytes.toByteArray(), 2, -1);
        assertEquals(2, tiles.size());
        // Region 2,-1 holds chunks 64..95: chunkInRegion (0,0), tile slot (1,2) = chunk 64+4+1, z 0+32+2.
        final XaeroTileData first = tiles.get((((2 * 32 + 0 * 4 + 1L)) << 32) | ((-1 * 32 + 0 * 4 + 2) & 0xFFFFFFFFL));
        assertEquals(1, first.interpretationVersion());
        assertEquals(XaeroTileData.SURFACE_CAVE_START, first.caveStart());
        assertEquals(30, first.caveDepth());

        // columnsA layout: index 0 water, 1 stone, 2 leaves-with-properties, rest grass.
        final XaeroColumn waterColumn = first.column(0, 0);
        // The water column's height is its floor (60); the surface sits 3 blocks above it.
        assertEquals(60, waterColumn.height());
        assertTrue(waterColumn.hasWater());
        assertEquals(3, waterColumn.translucentDepth());
        assertEquals(9, waterColumn.overlays().get(0).light());
        assertNull(waterColumn.overlays().get(0).state());
        assertEquals("minecraft:ocean", waterColumn.biomeId());

        final XaeroColumn stoneColumn = first.column(1, 0);
        assertEquals("minecraft:stone", stoneColumn.surface().id());
        assertEquals("minecraft:plains", stoneColumn.biomeId());
        assertEquals(0, stoneColumn.overlays().size());

        final XaeroColumn leavesColumn = first.column(2, 0);
        assertEquals("minecraft:oak_leaves", leavesColumn.surface().id());
        assertEquals(Map.of("distance", "7", "persistent", "false"), leavesColumn.surface().properties());

        assertTrue(first.column(3, 0).isGrassSurface());
        assertEquals("minecraft:plains", first.column(3, 0).biomeId());

        final XaeroTileData second = tiles.get(((2 * 32 + 7 * 4 + 1L) << 32) | ((-1 * 32 + 7 * 4 + 2) & 0xFFFFFFFFL));
        assertEquals("minecraft:sand", second.column(0, 0).surface().id());
        assertTrue(second.column(0, 0).hasWater());
    }

    @Test
    void roundTripsRenamedStateKeysOnMajor8() throws IOException {
        final XaeroSaveFormat format = new XaeroSaveFormat(8, 8);
        final XaeroRegionWriter writer = new XaeroRegionWriter(format);
        final XaeroColumn[] columns = single(new XaeroColumn(
            new XaeroBlockState("minecraft:oak_leaves", Map.of("distance", "3")),
            200, XaeroColumn.NO_TOP_HEIGHT, 5, "minecraft:forest", List.of()));
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        writer.writeBare(bytes, List.of(fullTile(0, 0, columns)));

        final Map<Long, XaeroTileData> tiles = read(bytes.toByteArray(), 0, 0);
        assertEquals("minecraft:oak_leaves", tiles.values().iterator().next().column(0, 0).surface().id());
        assertEquals(Map.of("distance", "3"), tiles.values().iterator().next().column(0, 0).surface().properties());
    }

    @Test
    void handlesNegativeHeightsAndTopHeightWrap() throws IOException {
        final XaeroSaveFormat format = new XaeroSaveFormat(7, 8);
        final XaeroRegionWriter writer = new XaeroRegionWriter(format);
        // Ocean floor at -54, surface at 7: the u8 top height stores 7, depth = 61.
        final XaeroColumn deep = new XaeroColumn(
            XaeroBlockState.of("minecraft:gravel"), -54, 7, 0, "minecraft:deep_ocean", List.of(
                new XaeroOverlay(null, 0, 15)));
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        writer.writeBare(bytes,List.of(fullTile(0, 0, single(deep))));

        final Map<Long, XaeroTileData> tiles = read(bytes.toByteArray(), 0, 0);
        final XaeroColumn decoded = tiles.values().iterator().next().column(5, 9);
        assertEquals(-54, decoded.height());
        assertEquals(61, decoded.translucentDepth());
    }

    @Test
    void skipsUnversionedAndOutOfRangeStreams() throws IOException {
        final ByteArrayOutputStream legacy = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(legacy);
        out.writeInt(0x12345678); // first byte != 0xFF
        final XaeroRegionReader.Result unversioned = XaeroRegionReader.read(
            new ByteArrayInputStream(legacy.toByteArray()), 0, 0, (a, b, c) -> {});
        assertEquals("unversioned stream (legacy 1.12-era data)", ((XaeroRegionReader.Result.Skipped) unversioned).reason());

        for (final int version : new int[] {0, (1 << 16) | 3, (9 << 16) | 8, (5 << 16) | 9}) {
            final ByteArrayOutputStream stream = new ByteArrayOutputStream();
            final DataOutputStream versionOut = new DataOutputStream(stream);
            versionOut.writeByte(0xFF);
            versionOut.writeInt(version);
            final XaeroRegionReader.Result result = XaeroRegionReader.read(
                new ByteArrayInputStream(stream.toByteArray()), 0, 0, (a, b, c) -> {});
            assertInstanceOf(XaeroRegionReader.Result.Skipped.class, result, "version " + version);
        }
    }

    @Test
    void toleratesTruncationAndKeepsCompletedTiles() throws IOException {
        final XaeroSaveFormat format = new XaeroSaveFormat(5, 8);
        final XaeroRegionWriter writer = new XaeroRegionWriter(format);
        final XaeroColumn column = new XaeroColumn(
            XaeroBlockState.of("minecraft:stone"), 80, XaeroColumn.NO_TOP_HEIGHT, 0,
            "minecraft:plains", List.of());
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        writer.writeBare(bytes,List.of(fullTile(0, 0, single(column)), fullTile(0, 1, single(column))));
        final byte[] all = bytes.toByteArray();
        // Cut somewhere inside the second chunk block.
        final Map<Long, XaeroTileData> tiles = new LinkedHashMap<>();
        final XaeroRegionReader.Result result = XaeroRegionReader.read(
            new ByteArrayInputStream(all, 0, all.length - 500), 0, 0,
            (chunkX, chunkZ, tile) -> tiles.put((long) chunkX, tile));
        final XaeroRegionReader.Result.Decoded decoded = assertInstanceOf(XaeroRegionReader.Result.Decoded.class, result);
        assertTrue(decoded.truncated());
        assertEquals(1, tiles.size());
    }

    @Test
    void headerOnlyStreamDecodesEmpty() throws IOException {
        final ByteArrayOutputStream stream = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(stream);
        out.writeByte(0xFF);
        out.writeInt(new XaeroSaveFormat(5, 8).encoded());
        final Map<Long, XaeroTileData> tiles = read(stream.toByteArray(), 0, 0);
        assertTrue(tiles.isEmpty());
    }

    @Test
    void rejectsCorruptMarkersAndPaletteIndexes() throws IOException {
        // A state payload referencing a palette index that was never written inline.
        final ByteArrayOutputStream badIndex = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(badIndex);
        out.writeByte(0xFF);
        out.writeInt(new XaeroSaveFormat(5, 8).encoded());
        out.writeByte(0); // chunk marker (0,0)
        out.writeInt(1); // tile marker == first pixel params: bit 0 set, palette index follows
        out.writeInt(999);
        assertThrows(XaeroImportException.class, () -> read(badIndex.toByteArray(), 0, 0));

        // A chunk marker with bits in the reserved slots.
        final ByteArrayOutputStream badMarker = new ByteArrayOutputStream();
        final DataOutputStream markerOut = new DataOutputStream(badMarker);
        markerOut.writeByte(0xFF);
        markerOut.writeInt(new XaeroSaveFormat(5, 8).encoded());
        markerOut.writeByte(0x88);
        assertThrows(XaeroImportException.class, () -> read(badMarker.toByteArray(), 0, 0));
    }

    @Test
    void legacyMinorFramingOmitsTrailers() throws IOException {
        final XaeroSaveFormat format = new XaeroSaveFormat(5, 3);
        final XaeroRegionWriter writer = new XaeroRegionWriter(format);
        final XaeroColumn column = new XaeroColumn(
            XaeroBlockState.of("minecraft:stone"), 80, XaeroColumn.NO_TOP_HEIGHT, 0,
            "minecraft:plains", List.of());
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        writer.writeBare(bytes,List.of(fullTile(0, 0, single(column))));
        // The reader must not consume trailer bytes that the minor-3 writer never emitted:
        // a successful full decode without truncation proves the framing matched.
        final Map<Long, XaeroTileData> tiles = read(bytes.toByteArray(), 0, 0);
        assertEquals(1, tiles.size());
        final XaeroTileData tile = tiles.values().iterator().next();
        assertEquals(0, tile.interpretationVersion());
        assertEquals(XaeroTileData.SURFACE_CAVE_START, tile.caveStart());
        assertEquals(32, tile.caveDepth());
    }

    @Test
    void collectsOverlayStatesIntoSharedPalette() throws IOException {
        final XaeroSaveFormat format = new XaeroSaveFormat(5, 8);
        final XaeroRegionWriter writer = new XaeroRegionWriter(format);
        final XaeroColumn iceOverWater = new XaeroColumn(
            XaeroBlockState.of("minecraft:gravel"), 62, 64, 0, "minecraft:frozen_ocean", List.of(
                new XaeroOverlay(XaeroBlockState.of("minecraft:ice"), 0, 2),
                new XaeroOverlay(null, 5, 4)));
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        writer.writeBare(bytes,List.of(fullTile(0, 0, single(iceOverWater))));

        final Map<Long, XaeroTileData> tiles = read(bytes.toByteArray(), 0, 0);
        final XaeroColumn decoded = tiles.values().iterator().next().column(0, 0);
        assertEquals(2, decoded.overlays().size());
        assertEquals("minecraft:ice", decoded.overlays().get(0).state().id());
        assertNull(decoded.overlays().get(1).state());
        assertEquals(2, decoded.translucentDepth());
    }

    @Test
    void skipsNumericRegistryBiomes() throws IOException {
        // A biome marked inline-as-numeric (bit 20 + 22 + 23) is a reader-side branch for
        // registry ids without names; the importer surfaces no biome for it.
        final ByteArrayOutputStream stream = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(stream);
        out.writeByte(0xFF);
        out.writeInt(new XaeroSaveFormat(5, 8).encoded());
        out.writeByte(0); // chunk marker (0,0)
        // First pixel: grass, height 64, light 0, biome bits 20|22|23 -> numeric id follows.
        final int height = 64 & 0xFFF;
        out.writeInt((1 << 20) | (1 << 22) | (1 << 23) | ((height & 0xFF) << 12) | ((height >>> 8 & 0xF) << 25));
        out.writeInt(42); // numeric biome registry id, unmappable
        // Remaining pixels: plain grass without biome info.
        for (int i = 1; i < 256; i++) {
            out.writeInt(((height & 0xFF) << 12) | ((height >>> 8 & 0xF) << 25));
        }
        out.writeByte(1); // interpretation version
        out.writeInt(XaeroTileData.SURFACE_CAVE_START);
        out.writeByte(30);

        final Map<Long, XaeroTileData> tiles = read(stream.toByteArray(), 0, 0);
        assertNull(tiles.values().iterator().next().column(0, 0).biomeId());
        assertEquals(64, tiles.values().iterator().next().column(0, 0).height());
    }

    @Test
    void airColumnsDecodeAsExplicitState() throws IOException {
        final XaeroSaveFormat format = new XaeroSaveFormat(5, 8);
        final XaeroRegionWriter writer = new XaeroRegionWriter(format);
        final XaeroColumn air = new XaeroColumn(
            XaeroBlockState.of("minecraft:air"), -2048, XaeroColumn.NO_TOP_HEIGHT, 0, null, List.of());
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        writer.writeBare(bytes,List.of(fullTile(0, 0, single(air))));
        final Map<Long, XaeroTileData> tiles = read(bytes.toByteArray(), 0, 0);
        assertEquals("minecraft:air", tiles.values().iterator().next().column(0, 0).surface().id());
        assertEquals(-2048, tiles.values().iterator().next().column(0, 0).height());
    }

    @Test
    void readsZipContainersViaRegionFile() throws Exception {
        final XaeroSaveFormat format = new XaeroSaveFormat(5, 8);
        final XaeroRegionWriter writer = new XaeroRegionWriter(format);
        final XaeroColumn column = new XaeroColumn(
            XaeroBlockState.of("minecraft:stone"), 80, XaeroColumn.NO_TOP_HEIGHT, 0,
            "minecraft:plains", List.of());
        final Path file = tempDir.resolve("3_-4.zip");
        try (OutputStream out = Files.newOutputStream(file)) {
            writer.write(out, List.of(fullTile(0, 0, single(column))));
        }
        final XaeroRegionFile region = XaeroRegionFile.parse(file);
        assert region != null;
        final Map<Long, XaeroTileData> tiles = new LinkedHashMap<>();
        try (InputStream stream = region.open()) {
            XaeroRegionReader.read(stream, region.regionX(), region.regionZ(),
                (chunkX, chunkZ, tile) -> tiles.put((long) chunkX, tile));
        }
        assertEquals(1, tiles.size());
        assertEquals(80, tiles.values().iterator().next().column(0, 0).height());
    }

    @Test
    void absentTilesProduceNoSinkCalls() throws IOException {
        final XaeroSaveFormat format = new XaeroSaveFormat(5, 8);
        final XaeroRegionWriter writer = new XaeroRegionWriter(format);
        final XaeroColumn column = new XaeroColumn(
            null, 64, XaeroColumn.NO_TOP_HEIGHT, 0, "minecraft:plains", List.of());
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        writer.writeBare(bytes,List.of(fullTile(3, 4, single(column))));
        final List<Integer> chunkXs = new ArrayList<>();
        XaeroRegionReader.read(
            new ByteArrayInputStream(bytes.toByteArray()), 0, 0, (chunkX, chunkZ, tile) -> chunkXs.add(chunkX));
        // fullTile(3, 4) puts the tile at tile-chunk (3,4), tile slot (1,2): chunk (13, 18).
        assertEquals(List.of(3 * 4 + 1), chunkXs);
    }
}
