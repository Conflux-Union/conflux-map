package cn.net.rms.confluxmap.core.xaeroimport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Folder-grammar coverage for {@link XaeroWorldMapLayout}. */
class XaeroWorldMapLayoutTest {
    @TempDir
    Path gameDir;

    private static void touchRegion(final Path dir, final String name) throws Exception {
        Files.write(dir.resolve(name), new byte[] {0x50, 0x4B, 3, 4});
    }

    @Test
    void discoversBothRootsAndContainers() throws Exception {
        final Path current = gameDir.resolve("xaero/world-map/Multiplayer_127.0.0.1");
        final Path legacy = gameDir.resolve("XaeroWorldMap/Multiplayer_Unknown");
        Files.createDirectories(current);
        Files.createDirectories(legacy);

        final List<XaeroWorldMapLayout.Container> containers = XaeroWorldMapLayout.containers(gameDir);
        assertEquals(2, containers.size());
        assertEquals("Multiplayer_127.0.0.1", containers.get(0).folderName());
        assertEquals("Multiplayer_Unknown", containers.get(1).folderName());
    }

    @Test
    void mapsDimensionFolders() throws Exception {
        final Path container = gameDir.resolve("XaeroWorldMap/Realms_abc.123");
        Files.createDirectories(container.resolve("null"));
        Files.createDirectories(container.resolve("DIM-1"));
        Files.createDirectories(container.resolve("DIM1"));
        Files.createDirectories(container.resolve("terralith$worlds%2b2t"));
        Files.createDirectories(container.resolve("cache_1"));

        final List<XaeroWorldMapLayout.DimensionFolder> dimensions =
            XaeroWorldMapLayout.dimensions(new XaeroWorldMapLayout.Container("Realms_abc.123", container));
        assertEquals(4, dimensions.size());
        assertTrue(dimensions.stream().anyMatch(d -> d.dimension().equals(DimensionId.OVERWORLD)));
        assertTrue(dimensions.stream().anyMatch(d -> d.dimension().equals(DimensionId.NETHER)));
        assertTrue(dimensions.stream().anyMatch(d -> d.dimension().equals(DimensionId.END)));
        assertTrue(dimensions.stream().anyMatch(d ->
            d.dimension().toString().equals("terralith:worlds/2b2t")));
    }

    @Test
    void prefersDefaultMultiworldAndFallsBackToSingleplayerLayout() throws Exception {
        final Path multiplayer = gameDir.resolve("xaero/world-map/Multiplayer_2b2t/null");
        final Path defaultLayer = multiplayer.resolve("mw$default");
        final Path legacyLayer = multiplayer.resolve("mw0,1,0");
        Files.createDirectories(defaultLayer);
        Files.createDirectories(legacyLayer);
        touchRegion(defaultLayer, "0_0.zip");

        assertEquals(defaultLayer, XaeroWorldMapLayout.surfaceLayerFolder(multiplayer));

        final Path singleplayer = gameDir.resolve("XaeroWorldMap/New_World/null");
        Files.createDirectories(singleplayer);
        touchRegion(singleplayer, "-1_-1.xaero");
        assertEquals(singleplayer, XaeroWorldMapLayout.surfaceLayerFolder(singleplayer));

        final Path empty = gameDir.resolve("XaeroWorldMap/Empty/null");
        Files.createDirectories(empty);
        assertNull(XaeroWorldMapLayout.surfaceLayerFolder(empty));
    }

    @Test
    void listsOnlyRegionFiles() throws Exception {
        final Path layer = gameDir.resolve("xaero/world-map/Multiplayer_a/null/mw$default");
        Files.createDirectories(layer.resolve("cache_1"));
        Files.createDirectories(layer.resolve("caves/1"));
        touchRegion(layer, "0_0.zip");
        touchRegion(layer, "-1_-2.zip");
        touchRegion(layer, "0_1.xaero");
        Files.write(layer.resolve("0_0.zip.temp"), new byte[] {1});
        Files.write(layer.resolve("0_1.zip.outdated"), new byte[] {1});
        Files.write(layer.resolve("server_config.txt"), new byte[] {1});
        Files.write(layer.resolve("cache_1/0_0.xwmc"), new byte[] {1});

        final List<XaeroRegionFile> regions = XaeroWorldMapLayout.surfaceRegions(layer);
        assertEquals(3, regions.size());
        assertEquals("-1_-2.zip", regions.get(0).path().getFileName().toString());
        assertEquals(-1, regions.get(0).regionX());
        assertEquals(-2, regions.get(0).regionZ());
        assertFalse(regions.get(2).zipped());
    }

    @Test
    void resolvesContainerAddresses() {
        assertEquals("127.0.0.1", XaeroWorldMapLayout.multiplayerAddress("Multiplayer_127.0.0.1"));
        assertNull(XaeroWorldMapLayout.multiplayerAddress("Multiplayer_Unknown"));
        assertNull(XaeroWorldMapLayout.multiplayerAddress("Multiplayer_Any Address"));
        assertNull(XaeroWorldMapLayout.multiplayerAddress("New_World"));
        assertNull(XaeroWorldMapLayout.multiplayerAddress("Realms_uuid.1"));

        assertEquals("127.0.0.1", XaeroWorldMapLayout.addressKey("127.0.0.1:25565"));
        assertEquals("play.example.org", XaeroWorldMapLayout.addressKey("Play.Example.ORG:25565"));
        assertEquals("::1", XaeroWorldMapLayout.addressKey("[::1]:25565"));
        // Hostname containers carry no escapes; IPv6 leftovers are escaped with §.
        assertEquals("play.example.org", XaeroWorldMapLayout.containerAddressKey("Multiplayer_play.example.org"));
        assertEquals("2b2t", XaeroWorldMapLayout.containerAddressKey("Multiplayer_2b2t"));
        assertEquals("::1", XaeroWorldMapLayout.containerAddressKey("Multiplayer_[§§1]"));
        assertEquals("a_b/c", XaeroWorldMapLayout.unescapeContainerName("a^us^b%fs%c"));
    }
}
