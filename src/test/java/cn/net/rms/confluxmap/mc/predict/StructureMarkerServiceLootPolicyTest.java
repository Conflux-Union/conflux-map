package cn.net.rms.confluxmap.mc.predict;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.predict.ChestLootPreview;
import cn.net.rms.confluxmap.core.predict.PredictionState;
import cn.net.rms.confluxmap.core.predict.StructureIndex;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.nativepredict.McVersions;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Policy pins for the chest-loot preview seam: gating completes the future before any worker
 * or native work is scheduled, and {@code lootSupported} follows the support set frozen from
 * the native loot matrix (2026-11-15, {@code testNativeChestLoot}).
 */
class StructureMarkerServiceLootPolicyTest {
    @Test
    void lootPreviewWithoutActiveSessionCompletesEmptyWithoutSchedulingNativeWork(
        @TempDir final Path cacheRoot
    ) {
        final MapExecutors executors = new MapExecutors();
        final StructureMarkerService service = service(
            cacheRoot, McVersions.toCubiomes("1.21.9").getAsInt(), () -> true, executors
        );
        executors.shutdown(1_000L);
        // With the pool shut down, a gate regression fails as RejectedExecutionException
        // instead of a vacuous isDone() pass.
        final CompletableFuture<List<ChestLootPreview>> preview =
            service.lootPreviewAsync(outpostMarker());
        assertTrue(preview.isDone());
        assertTrue(preview.join().isEmpty());
    }

    @Test
    void lootPreviewWithStructureSearchForbiddenCompletesEmptyWithoutSchedulingNativeWork(
        @TempDir final Path cacheRoot
    ) {
        final AtomicBoolean allowed = new AtomicBoolean(true);
        final MapExecutors executors = new MapExecutors();
        final StructureMarkerService service = service(
            cacheRoot, McVersions.toCubiomes("1.21.9").getAsInt(), allowed::get, executors
        );
        service.onSessionChanged(new SessionGuard.Session(
            1L, WorldIdentity.multiplayer("loot-policy"), DimensionId.OVERWORLD
        ));
        allowed.set(false);
        executors.shutdown(1_000L);
        final CompletableFuture<List<ChestLootPreview>> preview =
            service.lootPreviewAsync(outpostMarker());
        assertTrue(preview.isDone());
        assertTrue(preview.join().isEmpty());
    }

    @Test
    void lootSupportedFollowsTheFrozenSupportSet(@TempDir final Path cacheRoot) {
        final MapExecutors executors = new MapExecutors();
        try {
            final StructureMarkerService service = service(
                cacheRoot, McVersions.toCubiomes("1.21.9").getAsInt(), () -> true, executors
            );
            assertTrue(service.lootSupported(StructureIndex.StructureType.PILLAGER_OUTPOST));
            // No piece path: missing from the fork's getStructureSaltConfig, or (Nether_Fossil)
            // no case in the fork's piece switch - enumeration reports -1 at every version.
            for (final StructureIndex.StructureType type : new StructureIndex.StructureType[] {
                StructureIndex.StructureType.VILLAGE,
                StructureIndex.StructureType.OCEAN_RUIN,
                StructureIndex.StructureType.WOODLAND_MANSION,
                StructureIndex.StructureType.ANCIENT_CITY,
                StructureIndex.StructureType.MINESHAFT,
                StructureIndex.StructureType.TRIAL_CHAMBERS,
                StructureIndex.StructureType.NETHER_FOSSIL,
            }) {
                assertFalse(service.lootSupported(type), type.id());
            }
            // No loot tables in the fork's table registry.
            for (final StructureIndex.StructureType type : new StructureIndex.StructureType[] {
                StructureIndex.StructureType.SWAMP_HUT,
                StructureIndex.StructureType.OCEAN_MONUMENT,
                StructureIndex.StructureType.TRAIL_RUINS,
            }) {
                assertFalse(service.lootSupported(type), type.id());
            }
        } finally {
            executors.shutdown(1_000L);
        }
    }

    @Test
    void lootSupportedVersionGatesFollowTheFrozenSet(@TempDir final Path cacheRoot) {
        final MapExecutors executors = new MapExecutors();
        try {
            final StructureMarkerService legacy = service(
                cacheRoot, McVersions.toCubiomes("1.17.1").getAsInt(), () -> true, executors
            );
            assertTrue(legacy.lootSupported(StructureIndex.StructureType.SHIPWRECK));
            assertFalse(legacy.lootSupported(StructureIndex.StructureType.RUINED_PORTAL_NETHER));
            assertFalse(legacy.lootSupported(StructureIndex.StructureType.ABANDONED_CAMP));

            final StructureMarkerService post118 = service(
                cacheRoot, McVersions.toCubiomes("1.18.2").getAsInt(), () -> true, executors
            );
            assertTrue(post118.lootSupported(StructureIndex.StructureType.RUINED_PORTAL_NETHER));
            assertFalse(post118.lootSupported(StructureIndex.StructureType.ABANDONED_CAMP));

            final StructureMarkerService modern = service(
                cacheRoot, McVersions.toCubiomes("26.3").getAsInt(), () -> true, executors
            );
            assertTrue(modern.lootSupported(StructureIndex.StructureType.ABANDONED_CAMP));
        } finally {
            executors.shutdown(1_000L);
        }
    }

    private static StructureMarkerService service(
        final Path cacheRoot,
        final int mcVersion,
        final BooleanSupplier structureSearchAllowed,
        final MapExecutors executors
    ) {
        final PredictionState prediction = new PredictionState();
        prediction.setSeed(42L, mcVersion);
        return new StructureMarkerService(cacheRoot, prediction, structureSearchAllowed, executors);
    }

    private static StructureIndex.Marker outpostMarker() {
        return new StructureIndex.Marker(
            StructureIndex.StructureType.PILLAGER_OUTPOST, 1344, -9872, StructureIndex.State.CANDIDATE
        );
    }
}
