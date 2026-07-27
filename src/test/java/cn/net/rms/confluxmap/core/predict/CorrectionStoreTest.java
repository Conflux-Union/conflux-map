package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CorrectionStoreTest {
    @Test
    void singleplayerCorrectionsUseTheCurrentSaveIdentity(@TempDir final Path tempDir) throws IOException {
        final Path saveRoot = tempDir.resolve("saves").resolve("New World");
        Files.createDirectories(saveRoot);
        Files.writeString(saveRoot.resolve("level.dat"), "test save");
        final WorldIdentity world = WorldIdentity.singleplayerSave(saveRoot);
        final CorrectionStore store = new CorrectionStore(tempDir);
        store.onSessionChanged(new SessionGuard.Session(1L, world, DimensionId.OVERWORLD));

        final CorrectionStore.Key key = new CorrectionStore.Key("minecraft:overworld", 0, 3, -2);
        store.apply(
            key, 1L, new byte[Proto.PATCH_PRESENCE_BYTES], new PatchCodec.Patch(List.of()), 12_345L
        );
        store.flush();

        final Path file = tempDir.resolve(world.serverId()).resolve(world.worldId())
            .resolve("minecraft_overworld").resolve("pred").resolve("0").resolve("t.3.-2.cfp");
        assertTrue(Files.isRegularFile(file));

        final CorrectionStore reopened = new CorrectionStore(tempDir);
        reopened.setNamespace(world);
        assertEquals(12_345L, reopened.get(key).validatedAtMillis());
    }

    @Test
    void sessionChangesFlushEachWorldBeforeClearing(@TempDir final Path tempDir) {
        final WorldIdentity first = WorldIdentity.singleplayer("first-world");
        final WorldIdentity second = WorldIdentity.singleplayer("second-world");
        final CorrectionStore store = new CorrectionStore(tempDir);
        final CorrectionStore.Key key = new CorrectionStore.Key("minecraft:overworld", 0, 0, 0);

        store.onSessionChanged(new SessionGuard.Session(1L, first, DimensionId.OVERWORLD));
        store.apply(key, 1L, new byte[Proto.PATCH_PRESENCE_BYTES], new PatchCodec.Patch(List.of()));
        store.onSessionChanged(new SessionGuard.Session(2L, second, DimensionId.OVERWORLD));

        store.apply(key, 2L, new byte[Proto.PATCH_PRESENCE_BYTES], new PatchCodec.Patch(List.of()));
        store.onSessionChanged(SessionGuard.Session.NONE);

        assertTrue(Files.isRegularFile(correctionFile(tempDir, first, 0, 0)));
        assertTrue(Files.isRegularFile(correctionFile(tempDir, second, 0, 0)));
    }

    @Test
    void persistentIdentityMigratesDirectoryBasedCorrections(@TempDir final Path tempDir) throws IOException {
        final Path saveRoot = tempDir.resolve("saves").resolve("New World");
        Files.createDirectories(saveRoot);
        Files.writeString(saveRoot.resolve("level.dat"), "test save");
        final WorldIdentity currentWorld = WorldIdentity.singleplayerSave(saveRoot);
        final WorldIdentity directoryBasedWorld = new WorldIdentity("local", currentWorld.legacyStorageIds().get(0));
        final CorrectionStore.Key key = new CorrectionStore.Key("minecraft:overworld", 0, 3, -2);
        final CorrectionStore oldStore = new CorrectionStore(tempDir.resolve("corrections"));
        oldStore.setNamespace(directoryBasedWorld);
        oldStore.apply(key, 7L, new byte[Proto.PATCH_PRESENCE_BYTES], new PatchCodec.Patch(List.of()));
        oldStore.flush();
        final Path oldRoot = tempDir.resolve("corrections").resolve("local").resolve(directoryBasedWorld.worldId());

        final CorrectionStore currentStore = new CorrectionStore(tempDir.resolve("corrections"));
        currentStore.setNamespace(currentWorld);

        assertEquals(7L, currentStore.get(key).revision());
        assertFalse(Files.exists(oldRoot));
        assertTrue(Files.isRegularFile(correctionFile(tempDir.resolve("corrections"), currentWorld, 3, -2)));
    }

    @Test
    void coarseInvalidationExpiresLoadedChildrenButNotNeighbors(@TempDir final Path tempDir) {
        final CorrectionStore store = new CorrectionStore(tempDir);
        final byte[] presence = new byte[Proto.PATCH_PRESENCE_BYTES];
        final PatchCodec.Patch patch = new PatchCodec.Patch(List.of());
        final CorrectionStore.Key child = new CorrectionStore.Key("minecraft:overworld", 0, 2, 3);
        final CorrectionStore.Key sibling = new CorrectionStore.Key("minecraft:overworld", 0, 3, 3);
        final CorrectionStore.Key neighbor = new CorrectionStore.Key("minecraft:overworld", 0, 4, 3);
        store.apply(child, 1L, presence, patch, 10_000L);
        store.apply(sibling, 1L, presence, patch, 10_000L);
        store.apply(neighbor, 1L, presence, patch, 10_000L);

        assertTrue(store.invalidateCoverage(new CorrectionStore.Key("minecraft:overworld", 1, 1, 1)));

        assertEquals(0L, store.get(child).validatedAtMillis());
        assertEquals(0L, store.get(sibling).validatedAtMillis());
        assertEquals(10_000L, store.get(neighbor).validatedAtMillis());
    }

    private static Path correctionFile(
        final Path root,
        final WorldIdentity world,
        final int tileX,
        final int tileZ
    ) {
        return root.resolve(world.serverId()).resolve(world.worldId())
            .resolve("minecraft_overworld").resolve("pred").resolve("0")
            .resolve("t." + tileX + "." + tileZ + ".cfp");
    }
}
