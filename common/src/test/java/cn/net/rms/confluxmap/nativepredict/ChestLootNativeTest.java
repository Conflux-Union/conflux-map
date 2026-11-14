package cn.net.rms.confluxmap.nativepredict;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.predict.PredictionDimensions;
import cn.net.rms.confluxmap.core.predict.StructureIndex;
import java.util.Arrays;
import java.util.Locale;
import java.util.OptionalInt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the chest-loot JNI bridge ({@link CubiomesContext#structureChests}/{@link
 * CubiomesContext#rollChestLoot}) end to end against the fork's own known-answer case: the
 * outpost Fortune I book from cubiomes-loot's {@code tests/test_loot.c}, replayed through the
 * full Java/JNI path instead of the C harness. The golden values below were captured once by
 * running this code and frozen (the stronghold chest triples cross-checked bit-exact against
 * the fork's own C piece path) - a cubiomes upgrade that changes worldgen or loot must
 * deliberately re-freeze them.
 *
 * <p>Every test starts with {@code Assumptions.assumeTrue(NativeLib.initForTests())} so a
 * machine without a working native build for its platform skips these instead of failing the
 * whole module (same convention as {@link CubiomesNativeTest}).
 */
class ChestLootNativeTest {
    /** Seed of the fork's known-answer outpost case ({@code tests/test_loot.c}), frozen. */
    private static final long KNOWN_SEED = -7594343816690189614L;
    /** Loot seed that case reports for the outpost chest. */
    private static final long KNOWN_LOOT_SEED = -617667753675473930L;
    private static final String OUTPOST_TABLE = "chests/pillager_outpost";
    /** The outpost chest position the fork's piece path yields for (1344, -9872) at this seed. */
    private static final int[] KNOWN_OUTPOST_CHEST = {1334, 64, -9862};
    /**
     * Frozen chest (x, y, z) triples and loot tables of the stronghold nearest the origin at
     * cubiomes commit {@link PredictorVersion#CUBIOMES_COMMIT_12}, this seed - captured once
     * from the JNI path and cross-checked bit-exact against the fork's own C piece path.
     */
    private static final int[][] GOLDEN_STRONGHOLD_CHESTS = {
        {509, 63, 1573}, {525, 59, 1635}, {534, 59, 1631}, {528, 65, 1568},
        {549, 67, 1585}, {509, 63, 1551}, {518, 63, 1555}, {523, 59, 1552},
        {554, 57, 1599}, {544, 57, 1529}, {523, 51, 1590},
    };
    private static final String[] GOLDEN_STRONGHOLD_TABLES = {
        "chests/stronghold_crossing", "chests/stronghold_library", "chests/stronghold_library",
        "chests/stronghold_corridor", "chests/stronghold_corridor", "chests/stronghold_library",
        "chests/stronghold_library", "chests/stronghold_crossing", "chests/stronghold_corridor",
        "chests/stronghold_corridor", "chests/stronghold_crossing",
    };

    @BeforeEach
    void requireNative() {
        Assumptions.assumeTrue(
            NativeLib.initForTests(), "native prediction library unavailable on this platform"
        );
    }

    @AfterEach
    void releaseContexts() {
        CubiomesContexts.closeAllOnThisThread();
    }

    private static int mc219() {
        final OptionalInt mc = McVersions.toCubiomes("1.21.9");
        assertTrue(mc.isPresent(), "McVersions must know \"1.21.9\"");
        return mc.getAsInt();
    }

    /**
     * The known seed's Overworld context through the production cache path; tests on this
     * thread share it, and {@link #releaseContexts()} closes it after each test.
     */
    private static CubiomesContext knownSeedContext() {
        final CubiomesContext ctx = CubiomesContexts.get(
            mc219(), KNOWN_SEED,
            PredictionDimensions.nativeStructureDim(DimensionId.OVERWORLD), 0
        );
        assertNotNull(ctx, "context creation must succeed for a valid version/dim");
        return ctx;
    }

    @Test
    void outpostChestsAtKnownSeed() {
        final int[] positions = chestPositions();
        final long[] seeds = new long[CubiomesContext.MAX_CHESTS_PER_STRUCTURE];
        final String[] tables = new String[CubiomesContext.MAX_CHESTS_PER_STRUCTURE];
        final int count = knownSeedContext().structureChests(
            StructureIndex.StructureType.PILLAGER_OUTPOST.nativeId(),
            1344, -9872, CubiomesContext.MAX_CHESTS_PER_STRUCTURE, positions, seeds, tables
        );
        assertTrue(count >= 1, "the outpost must enumerate its chest, got status " + count);
        // One chest per outpost, so slot 0 is the fork's known-answer chest.
        assertEquals(OUTPOST_TABLE, tables[0],
            "tables=" + Arrays.toString(Arrays.copyOf(tables, count)));
        assertArrayEquals(KNOWN_OUTPOST_CHEST, chestAt(positions, 0));
        assertEquals(KNOWN_LOOT_SEED, seeds[0]);
    }

    /**
     * Pins the stronghold chest (x, y, z) triples and loot tables of the stronghold nearest
     * the origin: a sign-extension or stride bug in the marshalling, or a regression that
     * flattened piece Y to 0, cannot reproduce the frozen values.
     */
    @Test
    void strongholdChestTriplesMatchFrozenGoldenValues() {
        final CubiomesContext ctx = knownSeedContext();
        final long[] nearest = new long[1];
        assertTrue(
            ctx.nearestStructure(StructureIndex.StructureType.STRONGHOLD.nativeId(), 0, 0, 100_000, nearest),
            "the known seed must have a stronghold within 100k blocks of the origin"
        );
        final int blockX = (int) (nearest[0] >> 32);
        final int blockZ = (int) nearest[0];
        assertEquals(532, blockX, "nearest stronghold X");
        assertEquals(1588, blockZ, "nearest stronghold Z");
        final int[] positions = chestPositions();
        final String[] tables = new String[CubiomesContext.MAX_CHESTS_PER_STRUCTURE];
        final int count = ctx.structureChests(
            StructureIndex.StructureType.STRONGHOLD.nativeId(), blockX, blockZ,
            CubiomesContext.MAX_CHESTS_PER_STRUCTURE, positions,
            new long[CubiomesContext.MAX_CHESTS_PER_STRUCTURE], tables
        );
        assertEquals(GOLDEN_STRONGHOLD_CHESTS.length, count,
            "the stronghold must enumerate exactly its golden chests, got " + count);
        // The fork shifts strongholds underground only at mc <= 1.12.2 (features/stronghold.c),
        // so modern piece Y stays near the 64 base instead of vanilla's negative 1.18+ range.
        boolean anyNonZeroY = false;
        for (int i = 0; i < count; i++) {
            assertArrayEquals(GOLDEN_STRONGHOLD_CHESTS[i], chestAt(positions, i), "chest " + i);
            assertEquals(GOLDEN_STRONGHOLD_TABLES[i], tables[i], "table of chest " + i);
            anyNonZeroY |= positions[3 * i + 1] != 0;
        }
        assertTrue(anyNonZeroY, "chest Ys flattened to 0 would show wrong vertical positions");
    }

    @Test
    void outpostLootRollContainsFortuneBook() {
        final int stackCap = CubiomesContext.MAX_LOOT_STACKS;
        final int enchCap = stackCap * CubiomesContext.MAX_ENCHANTS_PER_STACK;
        final int[] counts = new int[stackCap + 1];
        final String[] items = new String[stackCap];
        final String[] effects = new String[stackCap];
        final String[] enchNames = new String[enchCap];
        final int[] enchLevels = new int[enchCap];
        final int stacks = knownSeedContext().rollChestLoot(
            OUTPOST_TABLE, KNOWN_LOOT_SEED, stackCap,
            counts, items, effects, enchNames, enchLevels, enchCap
        );
        assertEquals(7, stacks, "the fork's known roll yields 7 stacks");

        // Enchantments are stack-major: stack i occupies the next counts[1 + i] slots.
        int offset = 0;
        final StringBuilder seen = new StringBuilder();
        boolean fortuneBook = false;
        for (int i = 0; i < stacks; i++) {
            final int enchCount = counts[1 + i];
            for (int e = 0; e < enchCount; e++) {
                final String name = enchNames[offset + e];
                final int level = enchLevels[offset + e];
                if (seen.length() > 0) {
                    seen.append(", ");
                }
                seen.append(name).append(' ').append(level);
                // get_enchantment_name returns lowercase, but match loosely in case the
                // fork renames them.
                if ("minecraft:book".equals(items[i]) && name != null
                    && name.toLowerCase(Locale.ROOT).contains("fortune") && level == 1) {
                    fortuneBook = true;
                }
            }
            offset += enchCount;
        }
        assertTrue(fortuneBook,
            "expected a minecraft:book stack with Fortune 1; enchantments seen: " + seen);
    }

    /**
     * No unrollable loot table exists among the piece-supported types at any supported mc
     * (the fork covers mc > 1.12 fully), so the -1 "not rollable" status is pinned with a
     * table name that cannot exist instead of a version gap.
     */
    @Test
    void unrollableTableReportsStatus() {
        final int stackCap = CubiomesContext.MAX_LOOT_STACKS;
        final int enchCap = stackCap * CubiomesContext.MAX_ENCHANTS_PER_STACK;
        final int status = knownSeedContext().rollChestLoot(
            "chests/does_not_exist", KNOWN_LOOT_SEED, stackCap,
            new int[stackCap + 1], new String[stackCap], new String[stackCap],
            new String[enchCap], new int[enchCap], enchCap
        );
        assertEquals(-1, status, "an unrollable table must report -1, not throw or roll");
    }

    private static int[] chestPositions() {
        return new int[3 * CubiomesContext.MAX_CHESTS_PER_STRUCTURE];
    }

    private static int[] chestAt(final int[] positions, final int chest) {
        return Arrays.copyOfRange(positions, 3 * chest, 3 * chest + 3);
    }
}
