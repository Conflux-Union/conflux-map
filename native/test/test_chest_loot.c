/* Chest-loot harness (testNativeChestLoot).
 *
 * Pins the cubiomes fork's loot library: the fork's outpost known-answer
 * case (Fortune I book), then a per-structure-type smoke matrix over
 * MC 1.21.9 / 1.17 that rolls every loot table reachable from structure
 * pieces and counts rollable vs unrollable chests. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "finders.h"
#include "biomenoise.h"
#include "util.h"
#include "loot/items.h"
#include "loot/loot_tables.h"

// One seed for everything; a later task freezes the printed output verbatim.
static const uint64_t SEED = -7594343816690189614LL;

// Above END_CITY_PIECES_MAX (421) and real fortress/stronghold counts, so no
// chest-bearing piece list truncates.
#define PIECE_BUF_MAX 1024
#define MAX_INSTANCES 8
#define SCAN_RADIUS 16       // region scan halfwidth
#define TREASURE_RADIUS 128  // buried treasure has one-chunk regions
#define END_CITY_RADIUS 64   // end highlands only start past the center islands
#define STACKS_MAX 27        // LootTableContext.generated_items capacity

static void cfx_check(int condition, const char *expr, int line) {
    if (!condition) {
        fprintf(stderr, "FATAL CHECK FAILED: %s at test_chest_loot.c:%d\n", expr, line);
        exit(1);
    }
}

#define REQUIRE(expr) cfx_check(!!(expr), #expr, __LINE__)

// Every type with a nonzero getLootTableCountForStructure entry, including the
// ones the fork's piece path does not support yet.
static const int LOOT_TYPES[] = {
    Desert_Pyramid, Jungle_Temple, Igloo, Village, Ocean_Ruin, Shipwreck,
    Mansion, Outpost, Ruined_Portal, Ruined_Portal_N, Ancient_City, Treasure,
    Mineshaft, Fortress, Bastion, End_City, Trial_Chambers, Abandoned_Camp,
    Stronghold,
};

static int dimension_of(int type) {
    if (type == End_City) {
        return DIM_END;
    }
    if (type == Fortress || type == Bastion || type == Ruined_Portal_N) {
        return DIM_NETHER;
    }
    return DIM_OVERWORLD;
}

// The shim resolves a biome only for the variant-sensitive types
// (cfxStructureVariant); everything else passes -1 like the fork's own tests.
static int needs_biome(int type) {
    return type == Shipwreck || type == Ruined_Portal || type == Ruined_Portal_N;
}

static int collect_instances(Pos *positions, int type, int mc, Generator *g,
    const SurfaceNoise *endNoise) {
    if (type == Stronghold) {
        StrongholdIter iter;
        initFirstStronghold(&iter, mc, g->seed);
        int found = 0;
        int remaining;
        do {
            remaining = nextStronghold(&iter, g);
            positions[found++] = iter.pos;
        } while (remaining > 0 && found < MAX_INSTANCES);
        return found;
    }
    const int radius = type == End_City ? END_CITY_RADIUS
        : type == Treasure ? TREASURE_RADIUS
        : SCAN_RADIUS;
    int found = 0;
    for (int regZ = -radius; regZ <= radius && found < MAX_INSTANCES; regZ++) {
        for (int regX = -radius; regX <= radius && found < MAX_INSTANCES; regX++) {
            Pos pos;
            if (!getStructurePos(type, mc, g->seed, regX, regZ, &pos)) {
                continue;
            }
            if (!isViableStructurePos(type, g, pos.x, pos.z, 0)) {
                continue;
            }
            if (type == End_City && !isViableEndCityTerrain(g, endNoise, pos.x, pos.z)) {
                continue;
            }
            positions[found++] = pos;
        }
    }
    return found;
}

// Returns 0 for fork-unsupported types: the salt config prints its own ERR
// line and the matrix row still shows chests=0, never a silent skip.
static int smoke_type(Piece *pieces, int type, int mc,
    int *outChests, int *outRollable, int *outUnrollable) {
    StructureSaltConfig probe;
    if (!getStructureSaltConfig(type, mc, -1, &probe)) {
        return 0;
    }

    const int dim = dimension_of(type);
    Generator g;
    setupGenerator(&g, mc, 0);
    applySeed(&g, dim, SEED);
    SurfaceNoise endNoise;
    const SurfaceNoise *endNoisePtr = NULL;
    if (type == End_City) {
        initSurfaceNoise(&endNoise, DIM_END, SEED);
        endNoisePtr = &endNoise;
    }

    Pos positions[MAX_INSTANCES];
    const int instanceCount = collect_instances(positions, type, mc, &g, endNoisePtr);

    int chests = 0, rollable = 0, unrollable = 0;
    for (int inst = 0; inst < instanceCount; inst++) {
        const Pos pos = positions[inst];
        int biome = -1;
        if (needs_biome(type)) {
            // Same sampling as the shim's cfxStructureBiome generic branch.
            const int sampleY = dim == DIM_NETHER ? 32 >> 2 : 80 >> 2;
            biome = getBiomeAt(&g, 4, (pos.x >> 4) * 4 + 2, sampleY, (pos.z >> 4) * 4 + 2);
        }
        StructureSaltConfig saltConfig;
        REQUIRE(getStructureSaltConfig(type, mc, biome, &saltConfig));
        StructureVariant sv;
        StructureVariant *svp = NULL;
        if (getVariant(&sv, type, mc, SEED, pos.x, pos.z, biome)) {
            svp = &sv;
        }
        const int pieceCount = getStructurePieces(pieces, PIECE_BUF_MAX, type,
            saltConfig, svp, mc, SEED, pos.x, pos.z);
        if (pieceCount <= 0) {
            fprintf(stderr, "FATAL: pieces=%d for %s at mc %d\n",
                pieceCount, struct2str(type), mc);
            return 1;
        }
        for (int p = 0; p < pieceCount; p++) {
            const Piece *piece = &pieces[p];
            chests += piece->chestCount;
            for (int c = 0; c < piece->chestCount; c++) {
                const char *table = piece->lootTables[c];
                if (table == NULL) {
                    fprintf(stderr, "FATAL: NULL loot table on piece '%s' of %s\n",
                        piece->name, struct2str(type));
                    return 1;
                }
                LootTableContext *ltc = NULL;
                // init_loot_table_name returns static singletons for its
                // generated tables; free_loot_table is only for the JSON
                // parser path and aborts on these, so never free.
                if (!init_loot_table_name(&ltc, table, mc)) {
                    unrollable++;
                    continue;
                }
                set_loot_seed(ltc, piece->lootSeeds[c]);
                generate_loot(ltc);
                if (ltc->generated_item_count < 0 || ltc->generated_item_count > STACKS_MAX) {
                    fprintf(stderr, "FATAL: %d generated stacks for %s\n",
                        ltc->generated_item_count, table);
                    return 1;
                }
                rollable++;
            }
        }
    }
    *outChests = chests;
    *outRollable = rollable;
    *outUnrollable = unrollable;
    return 0;
}

// The fork's own outpost case (tests/test_loot.c), verbatim values.
static void known_answer(void) {
    printf("--- known answer: outpost Fortune I book (fork tests/test_loot.c) ---\n");
    const int mc = MC_1_21_9;
    const int x = 1344;
    const int z = -9872;

    StructureSaltConfig saltConfig;
    // in 1.18+ the outpost salts do not depend on the biome
    REQUIRE(getStructureSaltConfig(Outpost, mc, -1, &saltConfig));

    StructureVariant sv;
    // the biome does not matter for the outpost variant
    REQUIRE(getVariant(&sv, Outpost, mc, SEED, x, z, -1));

    Piece piece;
    REQUIRE(getStructurePieces(&piece, 1, Outpost, saltConfig, &sv, mc, SEED, x, z) == 1);
    REQUIRE(piece.chestCount == 1);
    REQUIRE(strcmp(piece.lootTables[0], "chests/pillager_outpost") == 0);

    // The fork models chests in 2D plus the owning piece's Y: that triple is
    // what the JNI marshalling carries for chest positions.
    printf("known-answer chest: x=%d y=%d z=%d (table=%s seed=%lld)\n",
        piece.chestPoses[0].x, piece.pos.y, piece.chestPoses[0].z,
        piece.lootTables[0], (long long) piece.lootSeeds[0]);
    REQUIRE(piece.chestPoses[0].x > x - 32 && piece.chestPoses[0].x < x + 32);
    REQUIRE(piece.chestPoses[0].z > z - 32 && piece.chestPoses[0].z < z + 32);
    REQUIRE(piece.pos.y > 0 && piece.pos.y < 320);

    LootTableContext *ltc = NULL;
    REQUIRE(init_loot_table_name(&ltc, piece.lootTables[0], mc));
    set_loot_seed(ltc, piece.lootSeeds[0]);
    generate_loot(ltc);

    int hasFortune1Book = 0;
    const int lootCount = ltc->generated_item_count;
    REQUIRE(lootCount >= 0 && lootCount <= STACKS_MAX);
    for (int lootIdx = 0; lootIdx < lootCount; lootIdx++) {
        const ItemStack itemStack = ltc->generated_items[lootIdx];
        printf("  stack %d: %s x%d enchantments=%d\n", lootIdx,
            get_item_name(ltc, itemStack.item), itemStack.count,
            itemStack.enchantment_count);
        if (get_global_item_id(ltc, itemStack.item) != ITEM_BOOK) {
            continue;
        }
        for (int enchantmentIdx = 0; enchantmentIdx < itemStack.enchantment_count; enchantmentIdx++) {
            const EnchantInstance enchantInstance = itemStack.enchantments[enchantmentIdx];
            if (enchantInstance.enchantment == FORTUNE && enchantInstance.level == 1) {
                hasFortune1Book = 1;
            }
        }
    }
    REQUIRE(hasFortune1Book);
    printf("  fortune I book present\n");
}

int main(void) {
    Piece *pieces = malloc(sizeof(Piece) * PIECE_BUF_MAX);
    REQUIRE(pieces != NULL);

    known_answer();

    printf("--- loot smoke matrix (seed %lld) ---\n", (long long) SEED);
    static const struct { const char *label; int mc; } mcs[] = {
        {"MC_1_21_9", MC_1_21_9}, {"MC_1_17", MC_1_17},
    };
    int failures = 0;
    for (int m = 0; m < (int)(sizeof(mcs) / sizeof(mcs[0])); m++) {
        const int mc = mcs[m].mc;
        printf("mc %s\n", mcs[m].label);
        for (int t = 0; t < (int)(sizeof(LOOT_TYPES) / sizeof(LOOT_TYPES[0])); t++) {
            int chests = 0, rollable = 0, unrollable = 0;
            if (smoke_type(pieces, LOOT_TYPES[t], mc, &chests, &rollable, &unrollable) != 0) {
                failures++;
            }
            printf("%s: chests=%d rollable=%d unrollable=%d\n",
                struct2str(LOOT_TYPES[t]), chests, rollable, unrollable);
        }
    }
    free(pieces);
    fflush(stdout);
    return failures;
}
