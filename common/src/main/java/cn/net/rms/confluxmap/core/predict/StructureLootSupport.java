package cn.net.rms.confluxmap.core.predict;

import java.util.EnumSet;
import java.util.Set;

/**
 * Which structure types' chests the native library can enumerate, and from which mc version.
 * Frozen 2026-11-15 from the {@code testNativeChestLoot} matrix (seed -7594343816690189614 at
 * MC_1_21_9/MC_1_17). Excluded: Village, Ocean_Ruin, Mansion, Ancient_City, Mineshaft, and
 * Trial_Chambers are missing from the fork's {@code getStructureSaltConfig}, so chest
 * enumeration returns -1 at every version; Swamp_Hut, Monument, Nether_Fossil, and Trail_Ruins
 * carry no loot tables at all. Dungeon is outside this feature entirely.
 */
public final class StructureLootSupport {
    /** cubiomes constant of the 1.18 worldgen line (fork gate for Ruined_Portal_N chests). */
    private static final int MC_1_18 = 22;
    /** cubiomes constant of 26.3 (fork gate for abandoned-camp chests). */
    private static final int MC_26_3 = 35;

    private static final Set<StructureIndex.StructureType> ROLLABLE = EnumSet.of(
        StructureIndex.StructureType.DESERT_PYRAMID,
        StructureIndex.StructureType.JUNGLE_TEMPLE,
        StructureIndex.StructureType.IGLOO,
        StructureIndex.StructureType.SHIPWRECK,
        StructureIndex.StructureType.PILLAGER_OUTPOST,
        StructureIndex.StructureType.RUINED_PORTAL,
        StructureIndex.StructureType.BURIED_TREASURE,
        StructureIndex.StructureType.FORTRESS,
        StructureIndex.StructureType.BASTION_REMNANT,
        StructureIndex.StructureType.END_CITY,
        StructureIndex.StructureType.STRONGHOLD
    );

    private StructureLootSupport() {
    }

    /** Whether the native library can enumerate {@code type}'s chests at {@code mcVersion}. */
    public static boolean supports(final StructureIndex.StructureType type, final int mcVersion) {
        return switch (type) {
            case RUINED_PORTAL_NETHER -> mcVersion >= MC_1_18;
            case ABANDONED_CAMP -> mcVersion >= MC_26_3;
            default -> ROLLABLE.contains(type);
        };
    }
}
