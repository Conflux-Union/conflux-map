package cn.net.rms.confluxmap.core.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CaveFloorEngineCacheParityTest {
    /** Naive unoptimized baseline matching the original pre-optimization implementation. */
    private static Set<Integer> naiveScanMissing(final ChunkVolume volume, final Map<Integer, MaterialDescriptor> materials) {
        final Set<Integer> missing = new HashSet<>();
        for (final int stateId : volume.stateIds()) {
            if (!materials.containsKey(stateId)) {
                missing.add(stateId);
            }
        }
        return missing;
    }

    @Test
    void negativeStateIdMustThrowMissingMaterialsAndNeverNpe() {
        final int height = 16;
        final int[] states = new int[height * 256];
        states[0] = -1; // Negative invalid state ID

        final ChunkVolume chunk = new ChunkVolume(0, 0, 1L, 0, height, states);
        final CaveFloorEngine engine = new CaveFloorEngine();
        final Map<Integer, MaterialDescriptor> materials = Map.of(
            0, new MaterialDescriptor(true, false)
        );

        final MissingMaterialsException ex = assertThrows(
            MissingMaterialsException.class,
            () -> engine.select(chunk, 8, materials),
            "Negative state ID must trigger MissingMaterialsException, never NPE"
        );
        assertEquals(Set.of(-1), ex.stateIds());
    }

    @Test
    void lruSlotEvictionParityWithMoreThanFourDistinctStates() throws Exception {
        final int height = 16;
        final int[] states = new int[height * 256];
        // Cycle 7 distinct valid state IDs (0..6) repeatedly across the volume
        for (int i = 0; i < states.length; i++) {
            states[i] = i % 7;
        }

        final ChunkVolume chunk = new ChunkVolume(0, 0, 1L, 0, height, states);
        final CaveFloorEngine engine = new CaveFloorEngine();
        final Map<Integer, MaterialDescriptor> materials = new HashMap<>();
        for (int id = 0; id < 7; id++) {
            materials.put(id, new MaterialDescriptor(id % 2 == 0, false));
        }

        // Must succeed without missing exception
        final CaveChunkResult result = engine.select(chunk, 8, materials);
        assertEquals(0, result.chunkX());
        assertEquals(0, result.chunkZ());
    }

    @Test
    void randomizedMissingDetectionParityAcrossManyPatterns() {
        final Random random = new Random(42L);
        final CaveFloorEngine engine = new CaveFloorEngine();

        final Map<Integer, MaterialDescriptor> knownMaterials = new HashMap<>();
        for (int i = 0; i < 20; i++) {
            knownMaterials.put(i, new MaterialDescriptor(i % 2 == 0, i % 3 == 0));
        }

        final int height = 32;
        final int totalStates = height * 256;

        for (int trial = 0; trial < 50; trial++) {
            final int[] states = new int[totalStates];
            int current = random.nextInt(25); // Some in 0..19 (known), some in 20..24 (missing)
            int runLength = random.nextInt(64) + 1;

            for (int i = 0; i < totalStates; i++) {
                if (--runLength <= 0) {
                    current = random.nextInt(25);
                    runLength = random.nextInt(64) + 1;
                }
                states[i] = current;
            }

            final ChunkVolume volume = new ChunkVolume(trial, trial, 1L, 0, height, states);
            final Set<Integer> expectedMissing = naiveScanMissing(volume, knownMaterials);

            if (expectedMissing.isEmpty()) {
                try {
                    final CaveChunkResult res = engine.select(volume, 16, knownMaterials);
                    assertEquals(trial, res.chunkX());
                } catch (final MissingMaterialsException e) {
                    throw new AssertionError("Unexpected missing exception for trial " + trial);
                }
            } else {
                final MissingMaterialsException ex = assertThrows(
                    MissingMaterialsException.class,
                    () -> engine.select(volume, 16, knownMaterials)
                );
                assertEquals(expectedMissing, ex.stateIds(), "Mismatch in missing IDs for trial " + trial);
            }
        }
    }
}
