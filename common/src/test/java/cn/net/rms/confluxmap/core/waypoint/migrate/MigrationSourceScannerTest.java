package cn.net.rms.confluxmap.core.waypoint.migrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationSourceScannerTest {
    private static final Logger LOGGER = LogManager.getLogger("MigrationSourceScannerTest");
    private static final String XAERO_LINE =
        "waypoint:Home:H:10:64:20:9:false:0:gui.xaero_default:false:0:0:false";
    private static final String XAERO_NETHER_LINE =
        "waypoint:Fortress:F:1:40:2:12:false:0:gui.xaero_default:false:0:0:false";
    private static final String VOXEL_LINE = "name:Base,x:5,z:6,y:70,enabled:true,dimensions:overworld#";

    @Test
    void findsXaeroMultiplayerContainerAcrossDimensionsCaseInsensitivelyAndPortStripped(
        @TempDir final Path gameDir
    ) throws IOException {
        final Path container = gameDir.resolve("xaero/minimap/Multiplayer_play.example.com");
        write(container.resolve("dim%0/waypoints.txt"), XAERO_LINE);
        write(container.resolve("dim%-1/waypoints.txt"), XAERO_NETHER_LINE);
        write(gameDir.resolve("xaero/minimap/Multiplayer_other.server.net/dim%0/waypoints.txt"), XAERO_LINE);

        final List<MigrationSource> sources = MigrationSourceScanner.scan(
            gameDir, MigrationSourceScanner.Context.multiplayer("Play.Example.COM:25566"), LOGGER
        );

        assertEquals(1, sources.size());
        final MigrationSource source = sources.get(0);
        assertEquals(MigrationSource.Mod.XAERO, source.mod());
        assertEquals("Multiplayer_play.example.com", source.displayName());
        assertEquals(2, source.waypoints().size());
        assertEquals(DimensionId.NETHER, source.waypoints().get(0).dimensionId());
        assertEquals(DimensionId.OVERWORLD, source.waypoints().get(1).dimensionId());
    }

    @Test
    void matchesFoldersFromPre2390BuildsWithNaiveIpv6PortCut(@TempDir final Path gameDir) throws IOException {
        // An old build named the folder by cutting "[2001:db8::1]:25565" at the first colon.
        write(gameDir.resolve("xaero/minimap/Multiplayer_[2001/dim%0/waypoints.txt"), XAERO_LINE);

        final List<MigrationSource> sources = MigrationSourceScanner.scan(
            gameDir, MigrationSourceScanner.Context.multiplayer("[2001:db8::1]:25565"), LOGGER
        );

        assertEquals(1, sources.size());
        assertEquals("Multiplayer_[2001", sources.get(0).displayName());
    }

    @Test
    void multiworldFilesBecomeSeparateSourcesWithDefaultFirst(@TempDir final Path gameDir) throws IOException {
        final Path container = gameDir.resolve("xaero/minimap/Multiplayer_play.example.com");
        write(container.resolve("dim%0/mw$1_1.txt"), XAERO_LINE);
        write(container.resolve("dim%0/mw$2_Sub%us%World.txt"), XAERO_NETHER_LINE);
        write(container.resolve("config.txt"), "defaultMultiworldId:mw$2");

        final List<MigrationSource> sources = MigrationSourceScanner.scan(
            gameDir, MigrationSourceScanner.Context.multiplayer("play.example.com"), LOGGER
        );

        assertEquals(2, sources.size());
        assertEquals("Multiplayer_play.example.com · Sub_World", sources.get(0).displayName());
        assertEquals("Multiplayer_play.example.com · 1", sources.get(1).displayName());
    }

    @Test
    void legacyXaeroRootIsUsedButLosesToASameNamedModernContainer(@TempDir final Path gameDir) throws IOException {
        write(gameDir.resolve("XaeroWaypoints/My%us%World/dim%0/waypoints.txt"), XAERO_LINE);

        final MigrationSourceScanner.Context context = MigrationSourceScanner.Context.singleplayer("My_World");
        final List<MigrationSource> legacyOnly = MigrationSourceScanner.scan(gameDir, context, LOGGER);
        assertEquals(1, legacyOnly.size());
        assertEquals("My_World", legacyOnly.get(0).displayName());

        write(
            gameDir.resolve("xaero/minimap/My%us%World/dim%0/waypoints.txt"),
            XAERO_LINE + "\n" + XAERO_NETHER_LINE
        );
        final List<MigrationSource> bothRoots = MigrationSourceScanner.scan(gameDir, context, LOGGER);
        assertEquals(1, bothRoots.size());
        assertEquals(2, bothRoots.get(0).waypoints().size());
    }

    @Test
    void findsVoxelMapFilesWithEscapedPortAndDefaultPortStrip(@TempDir final Path gameDir) throws IOException {
        write(gameDir.resolve("voxelmap/play.example.com~colon~1234.points"), VOXEL_LINE);

        final List<MigrationSource> withPort = MigrationSourceScanner.scan(
            gameDir, MigrationSourceScanner.Context.multiplayer("Play.Example.com:1234"), LOGGER
        );
        assertEquals(1, withPort.size());
        assertEquals(MigrationSource.Mod.VOXELMAP, withPort.get(0).mod());
        assertEquals("play.example.com:1234", withPort.get(0).displayName());
        assertEquals(1, withPort.get(0).waypoints().size());

        write(gameDir.resolve("voxelmap/play.example.com.points"), VOXEL_LINE);
        final List<MigrationSource> defaultPort = MigrationSourceScanner.scan(
            gameDir, MigrationSourceScanner.Context.multiplayer("play.example.com:25565"), LOGGER
        );
        assertEquals(1, defaultPort.size());
        assertEquals("play.example.com", defaultPort.get(0).displayName());
    }

    @Test
    void oldVoxelMapLocationOnlyAppliesWhenNewLocationHasNoMatch(@TempDir final Path gameDir) throws IOException {
        write(gameDir.resolve("mods/mamiyaotaru/voxelmap/My_World.points"), VOXEL_LINE);

        final MigrationSourceScanner.Context context = MigrationSourceScanner.Context.singleplayer("My_World");
        final List<MigrationSource> oldOnly = MigrationSourceScanner.scan(gameDir, context, LOGGER);
        assertEquals(1, oldOnly.size());
        assertTrue(oldOnly.get(0).origin().toString().contains("mamiyaotaru"));

        write(gameDir.resolve("voxelmap/My_World.points"), VOXEL_LINE + "\n" + VOXEL_LINE.replace("Base", "Two"));
        final List<MigrationSource> bothLocations = MigrationSourceScanner.scan(gameDir, context, LOGGER);
        assertEquals(1, bothLocations.size());
        assertEquals(2, bothLocations.get(0).waypoints().size());
    }

    @Test
    void emptyOrForeignDataYieldsNoSources(@TempDir final Path gameDir) throws IOException {
        write(gameDir.resolve("xaero/minimap/Multiplayer_play.example.com/dim%0/waypoints.txt"), "#\n#");
        write(gameDir.resolve("xaero/minimap/Multiplayer_play.example.com/backup/waypoints.txt"), XAERO_LINE);
        write(gameDir.resolve("voxelmap/other.server.points"), VOXEL_LINE);

        final List<MigrationSource> sources = MigrationSourceScanner.scan(
            gameDir, MigrationSourceScanner.Context.multiplayer("play.example.com"), LOGGER
        );

        assertTrue(sources.isEmpty());
    }

    private static void write(final Path file, final String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content + "\n");
    }
}
