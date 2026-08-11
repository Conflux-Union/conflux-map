package cn.net.rms.confluxmap.core.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.SampleSource;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MapCacheMigrationTest {
    private static final Logger LOGGER = LogManager.getLogger("MapCacheMigrationTest");

    @Test
    void explicitMigrationCopiesARegionAndKeepsTheSource(@TempDir final Path root) throws Exception {
        final WorldIdentity source = WorldIdentity.multiplayer("play.example.net:25565", "world");
        final WorldIdentity target = WorldIdentity.companionMultiplayer(
            "play.example.net:25565", "11111111-2222-3333-4444-555555555555"
        );
        write(root, source, data(0, 0, SampleSource.REAL_CACHED, 17));

        final MapCacheMigration.Result result = MapCacheMigration.merge(root, source, target, LOGGER);

        assertEquals(MapCacheMigration.Status.APPLIED, result.status());
        assertEquals(1, result.copiedRegions());
        assertEquals(1, result.migratedChunks());
        assertTrue(Files.isRegularFile(region(root, source)));
        assertTrue(Files.isRegularFile(region(root, target)));
        assertEquals(17, read(root, target).baseArgb()[0]);
    }

    @Test
    void explicitMigrationFillsUnknownChunksButPreservesTargetData(@TempDir final Path root)
        throws Exception {
        final WorldIdentity source = WorldIdentity.multiplayer("play.example.net:25565", "world");
        final WorldIdentity target = WorldIdentity.companionMultiplayer(
            "play.example.net:25565", "11111111-2222-3333-4444-555555555555"
        );
        write(root, source, dataWithSecondChunk(0, 0, SampleSource.REAL_CACHED, 11));
        write(root, target, data(0, 0, SampleSource.REAL_CACHED, 22));

        final MapCacheMigration.Result result = MapCacheMigration.merge(root, source, target, LOGGER);
        final RegionFileCodec.RegionData merged = read(root, target);

        assertEquals(MapCacheMigration.Status.APPLIED, result.status());
        assertEquals(1, result.migratedChunks());
        assertEquals(22, merged.baseArgb()[0]);
        assertEquals(11, merged.baseArgb()[16]);
    }

    @Test
    void sameNamespaceIsRejectedWithoutTouchingTheCache(@TempDir final Path root) {
        final WorldIdentity world = WorldIdentity.companionMultiplayer(
            "play.example.net:25565", "11111111-2222-3333-4444-555555555555"
        );

        final MapCacheMigration.Result result = MapCacheMigration.merge(root, world, world, LOGGER);

        assertEquals(MapCacheMigration.Status.SOURCE_IS_TARGET, result.status());
    }

    private static RegionFileCodec.RegionData data(
        final int rx,
        final int rz,
        final SampleSource source,
        final int marker
    ) {
        final byte[] chunkSource = new byte[RegionFileCodec.CHUNK_TABLE_ENTRIES];
        chunkSource[0] = (byte) source.ordinal();
        final int[] updates = new int[RegionFileCodec.CHUNK_TABLE_ENTRIES];
        final long[] revisions = new long[RegionFileCodec.CHUNK_TABLE_ENTRIES];
        final short[] surface = new short[RegionFileCodec.COLUMN_COUNT];
        final byte[] fluid = new byte[RegionFileCodec.COLUMN_COUNT];
        final byte[] kind = new byte[RegionFileCodec.COLUMN_COUNT];
        final String[] biomes = new String[RegionFileCodec.COLUMN_COUNT];
        final int[] base = new int[RegionFileCodec.COLUMN_COUNT];
        final int[] tint = new int[RegionFileCodec.COLUMN_COUNT];
        final int[] overlay = new int[RegionFileCodec.COLUMN_COUNT];
        final byte[] light = new byte[RegionFileCodec.COLUMN_COUNT];
        base[0] = marker;
        base[16] = marker;
        return new RegionFileCodec.RegionData(
            rx, rz, 1L, chunkSource, updates, revisions, surface, fluid, kind, biomes,
            base, tint, overlay, light
        );
    }

    private static RegionFileCodec.RegionData dataWithSecondChunk(
        final int rx,
        final int rz,
        final SampleSource source,
        final int marker
    ) {
        final RegionFileCodec.RegionData data = data(rx, rz, source, marker);
        final byte[] chunkSource = data.chunkSourceOrdinal().clone();
        chunkSource[1] = (byte) source.ordinal();
        return new RegionFileCodec.RegionData(
            data.rx(), data.rz(), data.lastWriteEpochMs(), chunkSource,
            data.chunkUpdateEpochSeconds(), data.chunkSourceRevision(), data.surfaceY(),
            data.fluidDepth(), data.kind(), data.biomeId(), data.baseArgb(), data.biomeTint(),
            data.overlayArgb(), data.light()
        );
    }

    private static Path region(final Path root, final WorldIdentity world) {
        return root.resolve(world.serverId()).resolve(world.worldId())
            .resolve("overworld").resolve("surface").resolve("r.0.0.cfr");
    }

    private static void write(
        final Path root,
        final WorldIdentity world,
        final RegionFileCodec.RegionData data
    ) throws IOException {
        final Path file = region(root, world);
        Files.createDirectories(file.getParent());
        try (java.io.OutputStream output = Files.newOutputStream(file)) {
            RegionFileCodec.encode(output, 0, data);
        }
    }

    private static RegionFileCodec.RegionData read(final Path root, final WorldIdentity world)
        throws IOException, RegionFileCodec.RegionFileException {
        try (InputStream input = Files.newInputStream(region(root, world))) {
            return RegionFileCodec.decode(input, 0, 0, 0);
        }
    }
}
