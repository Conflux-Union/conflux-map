package cn.net.rms.confluxmap.nativepredict;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.predict.PredictionDimensions;
import cn.net.rms.confluxmap.core.predict.StructureIndex;
import cn.net.rms.confluxmap.core.predict.StructureLootSupport;
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
 * <p>The coverage matrix pins the same bridge per representative worldgen version against the
 * frozen support set in {@link StructureLootSupport}: support-claimed types enumerate chests
 * that roll, version-gated and salt-gap types report unsupported instead of crashing.
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
    /**
     * Frozen per-stack item counts and item names of the known outpost roll in generated_items
     * order (the fork's {@code tests/test_loot.c} output, replayed through the JNI path).
     */
    private static final int[] GOLDEN_OUTPOST_ITEM_COUNTS = {1, 4, 5, 2, 2, 2, 1};
    private static final String[] GOLDEN_OUTPOST_ITEM_NAMES = {
        "minecraft:crossbow", "minecraft:wheat", "minecraft:wheat", "minecraft:dark_oak_log",
        "minecraft:dark_oak_log", "minecraft:string", "minecraft:book",
    };

    /**
     * Worldgen versions the coverage matrix pins: chest enumeration and rolls were verified by
     * the {@code testNativeChestLoot} C harness at 1.21.9 and 1.17 with this seed; the rest
     * extend the pin across the version gates and unsampled worldgen.
     */
    private static final String[] MATRIX_VERSIONS = {"1.17.1", "1.18.2", "1.20.1", "1.21.9", "26.3"};

    /**
     * The types the fork's piece paths can attach chests to - the universe the matrix
     * cross-checks against {@link StructureLootSupport}. The six salt-gap types (Village,
     * Ocean_Ruin, Mansion, Ancient_City, Mineshaft, Trial_Chambers) have no {@code
     * getStructureSaltConfig} entry, so a per-version probe only prints the fork's ERR stderr
     * line and returns -1; {@link #excludedStructureTypesYieldNoChests} pins that once.
     */
    private static final StructureIndex.StructureType[] CHEST_PIECE_TYPES = {
        StructureIndex.StructureType.DESERT_PYRAMID,
        StructureIndex.StructureType.JUNGLE_TEMPLE,
        StructureIndex.StructureType.IGLOO,
        StructureIndex.StructureType.SHIPWRECK,
        StructureIndex.StructureType.PILLAGER_OUTPOST,
        StructureIndex.StructureType.RUINED_PORTAL,
        StructureIndex.StructureType.RUINED_PORTAL_NETHER,
        StructureIndex.StructureType.BURIED_TREASURE,
        StructureIndex.StructureType.FORTRESS,
        StructureIndex.StructureType.BASTION_REMNANT,
        StructureIndex.StructureType.END_CITY,
        StructureIndex.StructureType.STRONGHOLD,
        StructureIndex.StructureType.ABANDONED_CAMP,
    };

    /**
     * Search halfwidth in blocks for the matrix instance scans. The C harness found every
     * claimed type within 16 structure regions (at most ~8k blocks) of the origin at
     * MC_1_21_9/MC_1_17 with this seed, so 20k is generous; treasure (one-chunk regions) and
     * End_City (highlands only past the center islands) keep the harness's own radii (128/64
     * regions) - a uniform 20k would exceed the shim's region-rect cell cap for treasure and
     * re-scan five figures of regions for End_City.
     */
    private static final int MATRIX_RADIUS_BLOCKS = 20_000;
    private static final int TREASURE_RADIUS_BLOCKS = 4_096;
    private static final int END_CITY_RADIUS_BLOCKS = 24_576;
    /** Instance cap per scan; igloo needs headroom because only basement igloos carry a chest. */
    private static final int MATRIX_INSTANCE_CAP = 64;

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

    private static int mcOf(final String worldgenVersion) {
        final OptionalInt mc = McVersions.toCubiomes(worldgenVersion);
        assertTrue(mc.isPresent(), "McVersions must know \"" + worldgenVersion + "\"");
        return mc.getAsInt();
    }

    /**
     * The known seed's context for one worldgen version and dimension through the production
     * cache path; tests on this thread share it, and {@link #releaseContexts()} closes them
     * after each test.
     */
    private static CubiomesContext contextAt(final int mc, final DimensionId dimension) {
        final CubiomesContext ctx = CubiomesContexts.get(
            mc, KNOWN_SEED, PredictionDimensions.nativeStructureDim(dimension), 0
        );
        assertNotNull(ctx, "context creation must succeed for a valid version/dim");
        return ctx;
    }

    private static CubiomesContext knownSeedContext() {
        return contextAt(mcOf("1.21.9"), DimensionId.OVERWORLD);
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
        final int[] itemCounts = new int[stackCap];
        final String[] items = new String[stackCap];
        final String[] effects = new String[stackCap];
        final String[] enchNames = new String[enchCap];
        final int[] enchLevels = new int[enchCap];
        final int stacks = knownSeedContext().rollChestLoot(
            OUTPOST_TABLE, KNOWN_LOOT_SEED, stackCap,
            counts, itemCounts, items, effects, enchNames, enchLevels, enchCap
        );
        assertEquals(7, stacks, "the fork's known roll yields 7 stacks");
        assertArrayEquals(GOLDEN_OUTPOST_ITEM_COUNTS, Arrays.copyOf(itemCounts, stacks),
            "per-stack item counts=" + Arrays.toString(Arrays.copyOf(itemCounts, stacks)));
        assertArrayEquals(GOLDEN_OUTPOST_ITEM_NAMES, Arrays.copyOf(items, stacks),
            "item names=" + Arrays.toString(Arrays.copyOf(items, stacks)));

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
            new int[stackCap + 1], new int[stackCap], new String[stackCap], new String[stackCap],
            new String[enchCap], new int[enchCap], enchCap
        );
        assertEquals(-1, status, "an unrollable table must report -1, not throw or roll");
    }

    /**
     * Every {@link #MATRIX_VERSIONS} x {@link #CHEST_PIECE_TYPES} pair must agree with {@link
     * StructureLootSupport#supports}: a support-claimed pair must enumerate at least one chest
     * within the searched radius and every enumerated chest must roll its table (>= 0, never
     * the -2 native fault); a pair below its version gate must report unsupported (-1)
     * without needing an instance search.
     */
    @Test
    void lootCoverageMatrixAgreesWithFrozenSupportSet() {
        for (final String version : MATRIX_VERSIONS) {
            final int mc = mcOf(version);
            for (final StructureIndex.StructureType type : CHEST_PIECE_TYPES) {
                if (StructureLootSupport.supports(type, mc)) {
                    assertClaimedChests(version, mc, type);
                } else {
                    // The fork's salt config gates these before any position-dependent work
                    // (Ruined_Portal_N returns mc >= MC_1_18, Abandoned_Camp mc >= MC_26_3),
                    // so one nominal probe pins the -1 for the pair without an instance scan.
                    assertEquals(-1, chestStatus(type, mc, 0, 0),
                        version + " " + type.id() + " below its version gate must report unsupported");
                }
            }
        }
    }

    /**
     * Pins the excluded arm of the frozen support set: every {@code StructureType} outside
     * {@link StructureLootSupport}'s claimed set reports -1 at a modern version. Nine fail in
     * the fork's {@code getStructureSaltConfig}; Nether_Fossil has a salt config entry but no
     * case in the fork's piece switch, so its enumeration fails one step later with the same
     * status.
     */
    @Test
    void excludedStructureTypesYieldNoChests() {
        final int mc = mcOf("1.21.9");
        final StructureIndex.StructureType[] excluded = {
            StructureIndex.StructureType.VILLAGE,
            StructureIndex.StructureType.OCEAN_RUIN,
            StructureIndex.StructureType.WOODLAND_MANSION,
            StructureIndex.StructureType.ANCIENT_CITY,
            StructureIndex.StructureType.MINESHAFT,
            StructureIndex.StructureType.TRIAL_CHAMBERS,
            StructureIndex.StructureType.SWAMP_HUT,
            StructureIndex.StructureType.OCEAN_MONUMENT,
            StructureIndex.StructureType.TRAIL_RUINS,
            StructureIndex.StructureType.NETHER_FOSSIL,
        };
        for (final StructureIndex.StructureType type : excluded) {
            assertEquals(-1, chestStatus(type, mc, 0, 0), type.id() + " must report unsupported");
        }
    }

    /**
     * A null table name must take the same -1 "not rollable" status as an unknown name - the
     * shim rejects it before ever touching the string - never a crash into the -2 fault path.
     */
    @Test
    void nullLootTableReportsUnrollableStatus() {
        final int stackCap = CubiomesContext.MAX_LOOT_STACKS;
        final int enchCap = stackCap * CubiomesContext.MAX_ENCHANTS_PER_STACK;
        final int status = knownSeedContext().rollChestLoot(
            null, KNOWN_LOOT_SEED, stackCap,
            new int[stackCap + 1], new int[stackCap], new String[stackCap], new String[stackCap],
            new String[enchCap], new int[enchCap], enchCap
        );
        assertEquals(-1, status, "a null table must report -1, not crash");
    }

    /**
     * Enumerates instances of one support-claimed (version, type) pair and requires at least
     * one chest whose table rolls. Bastion and camp chests additionally pin the fork's Y=0
     * piece contract (their piece paths leave pos unset; the shim zeroes the buffer).
     */
    private static void assertClaimedChests(
        final String version, final int mc, final StructureIndex.StructureType type
    ) {
        final CubiomesContext ctx = contextAt(mc, dimensionOf(type));
        final long[] instances = new long[MATRIX_INSTANCE_CAP];
        int instanceCount;
        if (type == StructureIndex.StructureType.STRONGHOLD) {
            // strongholds() walks vanilla's ring iterator, so the first entries are the
            // innermost-ring (nearest) strongholds; three are plenty for a chest pin.
            instanceCount = Math.min(ctx.strongholds(instances), 3);
        } else {
            final int halfRegions =
                (int) Math.ceil((double) matrixRadius(type) / type.regionSizeBlocks(mc));
            instanceCount = ctx.viableStructures(
                type.nativeId(), -halfRegions, -halfRegions, halfRegions, halfRegions, instances
            );
        }
        assertTrue(instanceCount >= 1,
            version + " " + type.id() + ": support-claimed but no instance found");

        final int[] positions = chestPositions();
        final long[] seeds = new long[CubiomesContext.MAX_CHESTS_PER_STRUCTURE];
        final String[] tables = new String[CubiomesContext.MAX_CHESTS_PER_STRUCTURE];
        final int stackCap = CubiomesContext.MAX_LOOT_STACKS;
        final int enchCap = stackCap * CubiomesContext.MAX_ENCHANTS_PER_STACK;
        int chests = 0;
        for (int i = 0; i < instanceCount; i++) {
            final int status = ctx.structureChests(
                type.nativeId(), (int) (instances[i] >> 32), (int) instances[i],
                CubiomesContext.MAX_CHESTS_PER_STRUCTURE, positions, seeds, tables
            );
            assertTrue(status >= -1,
                version + " " + type.id() + " chest enumeration fault " + status);
            // A return above the written count means truncation; only written slots hold data.
            final int written = Math.min(status, CubiomesContext.MAX_CHESTS_PER_STRUCTURE);
            for (int c = 0; c < written; c++) {
                if (type == StructureIndex.StructureType.BASTION_REMNANT
                    || type == StructureIndex.StructureType.ABANDONED_CAMP) {
                    assertEquals(0, positions[3 * c + 1],
                        "bastion/camp pieces leave pos unset, so chest Y must marshal as 0");
                }
                assertTrue(tables[c] != null && !tables[c].isEmpty(),
                    version + " " + type.id() + " chest " + c + " has no loot table");
                final int stacks = ctx.rollChestLoot(
                    tables[c], seeds[c], stackCap,
                    new int[stackCap + 1], new int[stackCap], new String[stackCap],
                    new String[stackCap], new String[enchCap], new int[enchCap], enchCap
                );
                assertTrue(stacks >= 0,
                    version + " " + type.id() + " " + tables[c] + " rolled " + stacks
                        + "; a chest of a support-claimed type must roll");
                chests++;
            }
        }
        assertTrue(chests >= 1,
            version + " " + type.id() + ": support-claimed but no chest within the searched radius");
    }

    private static DimensionId dimensionOf(final StructureIndex.StructureType type) {
        return switch (type) {
            case FORTRESS, BASTION_REMNANT, RUINED_PORTAL_NETHER, NETHER_FOSSIL -> DimensionId.NETHER;
            case END_CITY -> DimensionId.END;
            default -> DimensionId.OVERWORLD;
        };
    }

    private static int matrixRadius(final StructureIndex.StructureType type) {
        return switch (type) {
            case BURIED_TREASURE -> TREASURE_RADIUS_BLOCKS;
            case END_CITY -> END_CITY_RADIUS_BLOCKS;
            default -> MATRIX_RADIUS_BLOCKS;
        };
    }

    /** One chest probe at a nominal position; the salt-config gate is position-independent. */
    private static int chestStatus(
        final StructureIndex.StructureType type, final int mc, final int blockX, final int blockZ
    ) {
        return contextAt(mc, dimensionOf(type)).structureChests(
            type.nativeId(), blockX, blockZ, CubiomesContext.MAX_CHESTS_PER_STRUCTURE,
            chestPositions(), new long[CubiomesContext.MAX_CHESTS_PER_STRUCTURE],
            new String[CubiomesContext.MAX_CHESTS_PER_STRUCTURE]
        );
    }

    private static int[] chestPositions() {
        return new int[3 * CubiomesContext.MAX_CHESTS_PER_STRUCTURE];
    }

    private static int[] chestAt(final int[] positions, final int chest) {
        return Arrays.copyOfRange(positions, 3 * chest, 3 * chest + 3);
    }
}
