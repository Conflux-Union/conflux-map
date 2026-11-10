#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <math.h>
#include <string.h>
#include <stddef.h>
#include <assert.h>
#include <time.h>
#include <jni.h>
#ifdef _WIN32
#include <windows.h>
#endif

#include "shim/confluxnative.c"
#include "shim/master_clean_impl.h"

#define CFX_CHECK(expr) do { \
    if (!(expr)) { \
        fprintf(stderr, "FATAL CHECK FAILED: %s at %s:%d\n", #expr, __FILE__, __LINE__); \
        exit(1); \
    } \
} while (0)

// The master snapshot overlays the live context: the shared prefix (through `dim`)
// must keep its layout, and new fields may only be appended after it.
_Static_assert(offsetof(CfxContext, dim) == offsetof(MasterCfxContext, dim), "Context prefix layout mismatch between opt and master");
_Static_assert(sizeof(MasterCfxContext) <= sizeof(CfxContext), "Master snapshot no longer overlays the live context");
_Static_assert(sizeof(CfxNoiseDetail) == sizeof(MasterCfxNoiseDetail), "NoiseDetail size mismatch");

static jsize JNICALL dummy_GetArrayLength(JNIEnv *env, jarray array) { return 10000000; }
static void JNICALL dummy_SetIntArrayRegion(JNIEnv *env, jintArray array, jsize start, jsize len, const jint *buf) {
    (void)env; memcpy((int*)array + start, buf, len * sizeof(jint));
}
static void JNICALL dummy_GetIntArrayRegion(JNIEnv *env, jintArray array, jsize start, jsize len, jint *buf) {
    (void)env; memcpy(buf, (int*)array + start, len * sizeof(jint));
}
static jboolean JNICALL dummy_ExceptionCheck(JNIEnv *env) { (void)env; return JNI_FALSE; }

typedef struct {
    double y_mae;
    double le2_rate;
    double le4_rate;
    int max_err;
    double vs_master_mae;
    double vs_master_le2;
    double biome_flip_opt;
    double biome_flip_master;
    double water_flip_opt;
    double water_flip_master;
    int coast_count;
    double coast_y_mae;
    double coast_biome_flip;
    double time_opt_ms;
    double time_master_ms;
} CaseResult;

static double get_time_ms(void) {
#ifdef _WIN32
    LARGE_INTEGER ticks, frequency;
    QueryPerformanceCounter(&ticks);
    QueryPerformanceFrequency(&frequency);
    return (double)ticks.QuadPart * 1000.0 / (double)frequency.QuadPart;
#else
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec * 1000.0 + (double)ts.tv_nsec / 1e6;
#endif
}

/* Match cfxSurfaceBiomes: block coordinates select a Voronoi quart nucleus. */
static int surface_biome_at_height(const CfxContext *ctx, int blockX, int blockZ, int terrainY) {
    const int surfaceY = terrainY < 62 ? 62 : terrainY;
    int x4, y4, z4;
    voronoiAccess3D(ctx->g.sha, blockX, surfaceY, blockZ, &x4, &y4, &z4);
    return sampleBiomeNoise(&ctx->g.bn, NULL, x4, y4, z4, NULL, 0);
}

static void test_surface_biome_sampling(JNIEnv *env, CfxContext *ctx) {
    const int origins[][2] = {{0, 0}, {1, 3}, {-4099, 8193}, {5000001, 5000003}, {-25000003, -10000001}};
    const int heights[] = {32, 62, 128, 224};
    for (size_t p = 0; p < sizeof(origins) / sizeof(origins[0]); p++) {
        for (size_t y = 0; y < sizeof(heights) / sizeof(heights[0]); y++) {
            int terrainY = heights[y];
            int expected = -1;
            CFX_CHECK(Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxSurfaceBiomes(
                env, NULL, (jlong)(intptr_t)ctx, origins[p][0], origins[p][1], 1, 1, 1,
                (jintArray)&terrainY, (jintArray)&expected) == CFX_OK);
            CFX_CHECK(surface_biome_at_height(ctx, origins[p][0], origins[p][1], terrainY) == expected);
        }
    }
    puts("Surface-biome audit sampling matches the production JNI entry at 20 columns.");
}

static void run_case(
    JNIEnv *env, CfxContext *ctx, uint64_t seed,
    int64_t ox, int64_t oz, int w, int h, int stride,
    CaseResult *res
)
{
    const int total = w * h;
    int *pred_opt = calloc(total, sizeof(int));
    int *pred_master = calloc(total, sizeof(int));
    int *truth_solid = calloc(total, sizeof(int));
    int *truth_fluid = calloc(total, sizeof(int));

    // 1. Optimized implementation solve
    CfxNoiseDetail detail_opt = cfxNoiseDetailForStride(&ctx->g.bn.terrain, stride);
    double t0_opt = get_time_ms();
    CFX_CHECK(cfxOverviewHeightsImpl(env, ctx, ox, oz, w, h, stride, (jintArray)pred_opt, &detail_opt, 0) == CFX_OK);
    res->time_opt_ms = get_time_ms() - t0_opt;

    // 2. Authentic master baseline solve (zero file IO)
    MasterCfxNoiseDetail detail_master = master_cfxNoiseDetailForStride(&ctx->g.bn.terrain, stride);
    double t0_mst = get_time_ms();
    CFX_CHECK(master_cfxOverviewHeightsImpl(env, (MasterCfxContext *)ctx, ox, oz, w, h, stride, (jintArray)pred_master, &detail_master) == 0);
    res->time_master_ms = get_time_ms() - t0_mst;

    // 3. Ground truth evaluation with error checks
    mapOverworldSurfaceHeightBlocks(truth_solid, truth_fluid, &ctx->g.bn, (int)ox, (int)oz, w, h, stride);

    double total_diff_y = 0;
    int le2_count = 0, le4_count = 0, max_e = 0;
    double total_diff_master = 0;
    int master_le2_count = 0;

    int biome_mismatch_opt = 0;
    int biome_mismatch_master = 0;
    int water_flip_opt = 0;
    int water_flip_master = 0;

    int coast_pts = 0;
    double coast_diff_y = 0;
    int coast_biome_mismatch = 0;

    // 100% exhaustive pixel-by-pixel inspection (zero subsampling)
    const int step = 1;
    int sampled_count = 0;

    for (int j = 0; j < h; j += step) {
        for (int i = 0; i < w; i += step) {
            int idx = j * w + i;
            int y_true = truth_solid[idx];
            int y_opt  = pred_opt[idx];
            int y_mst  = pred_master[idx];

            int dy = abs(y_opt - y_true);
            total_diff_y += dy;
            if (dy <= 2) le2_count++;
            if (dy <= 4) le4_count++;
            if (dy > max_e) max_e = dy;

            int dm = abs(y_opt - y_mst);
            total_diff_master += dm;
            if (dm <= 2) master_le2_count++;

            int is_water_true = (y_true < 63);
            int is_water_opt  = (y_opt < 63);
            int is_water_mst  = (y_mst < 63);
            if (is_water_opt != is_water_true) water_flip_opt++;
            if (is_water_mst != is_water_true) water_flip_master++;

            int worldX = (int)(ox + (int64_t)i * stride);
            int worldZ = (int)(oz + (int64_t)j * stride);
            int b_true = surface_biome_at_height(ctx, worldX, worldZ, y_true);
            int b_opt = surface_biome_at_height(ctx, worldX, worldZ, y_opt);
            int b_mst = surface_biome_at_height(ctx, worldX, worldZ, y_mst);

            if (b_opt != b_true) biome_mismatch_opt++;
            if (b_mst != b_true) biome_mismatch_master++;

            if (y_true >= 60 && y_true <= 66) {
                coast_pts++;
                coast_diff_y += dy;
                if (b_opt != b_true) coast_biome_mismatch++;
            }

            sampled_count++;
        }
    }

    res->y_mae = total_diff_y / sampled_count;
    res->le2_rate = (le2_count * 100.0) / sampled_count;
    res->le4_rate = (le4_count * 100.0) / sampled_count;
    res->max_err = max_e;
    res->vs_master_mae = total_diff_master / sampled_count;
    res->vs_master_le2 = (master_le2_count * 100.0) / sampled_count;
    res->biome_flip_opt = (biome_mismatch_opt * 100.0) / sampled_count;
    res->biome_flip_master = (biome_mismatch_master * 100.0) / sampled_count;
    res->water_flip_opt = (water_flip_opt * 100.0) / sampled_count;
    res->water_flip_master = (water_flip_master * 100.0) / sampled_count;
    res->coast_count = coast_pts;
    res->coast_y_mae = coast_pts > 0 ? (coast_diff_y / coast_pts) : 0.0;
    res->coast_biome_flip = coast_pts > 0 ? ((coast_biome_mismatch * 100.0) / coast_pts) : 0.0;

    free(pred_opt);
    free(pred_master);
    free(truth_solid);
    free(truth_fluid);
}

int main(int argc, char **argv) {
    struct JNINativeInterface_ iface;
    memset(&iface, 0, sizeof(iface));
    iface.GetArrayLength = dummy_GetArrayLength;
    iface.SetIntArrayRegion = dummy_SetIntArrayRegion;
    iface.GetIntArrayRegion = dummy_GetIntArrayRegion;
    iface.ExceptionCheck = dummy_ExceptionCheck;
    const struct JNINativeInterface_ *p_iface = &iface;
    JNIEnv *p_env = (JNIEnv *)&p_iface;

    uint64_t seeds[5] = {
        146008555LL,
        123456789LL,
        987654321LL,
        2026110701ULL,
        2026110702ULL
    };

    typedef struct { int64_t x; int64_t z; const char *name; } Loc;
    Loc locs[5] = {
        {0, 0, "Origin(0,0)"},
        {5000000, 5000000, "Mid(5M,5M)"},
        {-3000000, 2500000, "Q2(-3M,2.5M)"},
        {30000000, 30000000, "Border(30M,30M)"},
        {-25000000, -10000000, "Q3(-25M,-10M)"}
    };

    typedef struct { int ox; int oz; int w; int h; const char *name; } Shape;
    Shape shapes[4] = {
        {0, 0, 256, 256, "Clean(mod4=0)"},
        {1, 1, 257, 257, "OddWidth(257x257)"},
        {-1, -1, 258, 258, "StandardTile(-1,-1,258)"},
        {2, 3, 258, 258, "OffPhase(2,3,258)"}
    };

    printf("========================================================================================================================================\n");
    printf("   AUTHENTIC MASTER GROUND TRUTH MATRIX AUDIT (5 SEEDS x 5 LOCS x STRIDES x 4 SHAPES)\n");
    printf("========================================================================================================================================\n");
    printf("%-10s | %-16s | %-6s | %-22s | %-7s | %-7s | %-6s | %-11s | %-12s | %-12s | %-12s | %-14s\n",
           "Seed", "Location", "Stride", "Shape", "Y_MAE", "Y<=2", "Y_Max", "vsMst<=2", "BiomeFlip(O/M)", "WaterFlip(O/M)", "CoastBiomeFlip", "Time(Opt/Mst)");
    printf("----------------------------------------------------------------------------------------------------------------------------------------\n");
    fflush(stdout);

    for (int s = 0; s < 5; s++) {
        CfxContext ctx;
        memset(&ctx, 0, sizeof(ctx));
        ctx.mc = MC_1_21_1;
        ctx.dim = DIM_OVERWORLD;
        setupGenerator(&ctx.g, ctx.mc, 0);
        applySeed(&ctx.g, ctx.dim, seeds[s]);
        initSurfaceNoise(&ctx.sn, ctx.dim, seeds[s]);
        test_surface_biome_sampling(p_env, &ctx);
        if (argc > 1 && strcmp(argv[1], "--sampling-only") == 0)
            return 0;

        for (int l = 0; l < 5; l++) {
            int strides[] = {1, 2, 4};
            for (int st = 0; st < 3; st++) {
                int stride = strides[st];
                for (int sh_idx = 0; sh_idx < 4; sh_idx++) {
                    Shape sh = shapes[sh_idx];

                    CaseResult res;
                    run_case(p_env, &ctx, seeds[s], locs[l].x + sh.ox, locs[l].z + sh.oz, sh.w, sh.h, stride, &res);

                    char bFlip[32], wFlip[32], tStr[32];
                    snprintf(bFlip, sizeof(bFlip), "%4.1f%%/%4.1f%%", res.biome_flip_opt, res.biome_flip_master);
                    snprintf(wFlip, sizeof(wFlip), "%4.1f%%/%4.1f%%", res.water_flip_opt, res.water_flip_master);
                    snprintf(tStr, sizeof(tStr), "%4.1fms/%4.1fms", res.time_opt_ms, res.time_master_ms);

                    printf("%-10llu | %-16s | Stride%-1d | %-22s | %7.3f | %6.2f%% | %6d | %10.2f%% | %-14s | %-14s | %6.2f%% | %-14s\n",
                           (unsigned long long)seeds[s], locs[l].name, stride, sh.name,
                           res.y_mae, res.le2_rate, res.max_err, res.vs_master_le2,
                           bFlip, wFlip, res.coast_biome_flip, tStr);
                    fflush(stdout);

                    if (res.le2_rate < 80.0) {
                        fprintf(stderr, "FATAL: ground truth le2_rate fell below 80.0%% threshold (actual: %.2f%%) for seed %llu %s stride %d\n",
                                res.le2_rate, (unsigned long long)seeds[s], locs[l].name, stride);
                        return 1;
                    }
                    if (stride <= 2 && res.vs_master_le2 < 85.0) {
                        fprintf(stderr, "FATAL: vs_master_le2 agreement fell below 85.0%% threshold (actual: %.2f%%) for seed %llu %s stride %d\n",
                                res.vs_master_le2, (unsigned long long)seeds[s], locs[l].name, stride);
                        return 1;
                    }
                }
            }
        }
    }
    printf("\nAll test matrix cases passed verification thresholds.\n");
    return 0;
}
