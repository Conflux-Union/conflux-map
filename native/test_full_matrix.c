#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <math.h>
#include <string.h>
#include <assert.h>
#include <jni.h>

#include "shim/confluxnative.c"

static jsize JNICALL dummy_GetArrayLength(JNIEnv *env, jarray array) { return 10000000; }
static void JNICALL dummy_SetIntArrayRegion(JNIEnv *env, jintArray array, jsize start, jsize len, const jint *buf) {
    (void)env; memcpy((int*)array + start, buf, len * sizeof(jint));
}

// 模拟 Master 原版基准的高度生成
static void eval_master_baseline(const CfxContext *ctx, int64_t blockX, int64_t blockZ, int w, int h, int stride, int *out) {
    CfxNoiseDetail detail = CFX_DETAIL_FULL;
    if (stride <= 4) {
        const int64_t lastX = blockX + (int64_t)(w - 1) * stride;
        const int64_t lastZ = blockZ + (int64_t)(h - 1) * stride;
        const int minX4 = floordiv(blockX, 4);
        const int minZ4 = floordiv(blockZ, 4);
        const int maxX4 = floordiv((int)lastX, 4) + 1;
        const int maxZ4 = floordiv((int)lastZ, 4) + 1;
        const int gridW = maxX4 - minX4 + 1;
        const int gridH = maxZ4 - minZ4 + 1;
        int *macro = malloc(gridW * gridH * sizeof(int));
        for (int j = 0; j < gridH; j++) {
            for (int i = 0; i < gridW; i++) {
                macro[j * gridW + i] = cfxModernOverviewHeight(ctx, minX4 + i, minZ4 + j, &detail);
            }
        }
        for (int j = 0; j < h; j++) {
            const int sampleZ = (int)((int64_t)blockZ + (int64_t)j * stride);
            const int z4 = floordiv(sampleZ, 4);
            const int fz = sampleZ - z4 * 4;
            const int iz = z4 - minZ4;
            for (int i = 0; i < w; i++) {
                const int sampleX = (int)((int64_t)blockX + (int64_t)i * stride);
                const int x4 = floordiv(sampleX, 4);
                const int fx = sampleX - x4 * 4;
                const int ix = x4 - minX4;
                const int top = macro[iz * gridW + ix] + floordiv((macro[iz * gridW + ix + 1] - macro[iz * gridW + ix]) * fx, 4);
                const int bottom = macro[(iz + 1) * gridW + ix] + floordiv((macro[(iz + 1) * gridW + ix + 1] - macro[(iz + 1) * gridW + ix]) * fx, 4);
                out[j * w + i] = top + floordiv((bottom - top) * fz, 4);
            }
        }
        free(macro);
    } else {
        for (int j = 0; j < h; j++) {
            const int sampleZ = (int)((int64_t)blockZ + (int64_t)j * stride);
            for (int i = 0; i < w; i++) {
                const int sampleX = (int)((int64_t)blockX + (int64_t)i * stride);
                out[j * w + i] = cfxModernOverviewHeight(ctx, floordiv(sampleX, 4), floordiv(sampleZ, 4), &detail);
            }
        }
    }
}

// 统计单案例全部指标
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
} CaseResult;

static void run_case(
    JNIEnv *env, CfxContext *ctx, uint64_t seed,
    int64_t ox, int64_t oz, int w, int h, int stride,
    CaseResult *res
)
{
    const int total = w * h;
    int *pred_opt = malloc(total * sizeof(int));
    int *pred_master = malloc(total * sizeof(int));
    int *truth_solid = malloc(total * sizeof(int));
    int *truth_fluid = malloc(total * sizeof(int));

    CfxNoiseDetail detail_opt = cfxNoiseDetailForStride(&ctx->g.bn.terrain, stride);
    cfxOverviewHeightsImpl(env, ctx, ox, oz, w, h, stride, (jintArray)pred_opt, &detail_opt);
    eval_master_baseline(ctx, ox, oz, w, h, stride, pred_master);
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

    // 抽样步长: 若点数 > 16384 (如 256x256)，以步长 2 抽点 128x128 测 3D 群系以保障运行时间
    const int step = (w >= 256 && h >= 256) ? 2 : 1;
    int sampled_count = 0;

    for (int j = 0; j < h; j += step) {
        for (int i = 0; i < w; i += step) {
            int idx = j * w + i;
            int y_true = truth_solid[idx];
            int y_opt  = pred_opt[idx];
            int y_mst  = pred_master[idx];

            // 1. Y 误差
            int dy = abs(y_opt - y_true);
            total_diff_y += dy;
            if (dy <= 2) le2_count++;
            if (dy <= 4) le4_count++;
            if (dy > max_e) max_e = dy;

            // 2. vs Master
            int dm = abs(y_opt - y_mst);
            total_diff_master += dm;
            if (dm <= 2) master_le2_count++;

            // 3. 水陆翻转 (海平面 63)
            int is_water_true = (y_true < 63);
            int is_water_opt  = (y_opt < 63);
            int is_water_mst  = (y_mst < 63);
            if (is_water_opt != is_water_true) water_flip_opt++;
            if (is_water_mst != is_water_true) water_flip_master++;

            // 4. 3D 群系评估
            int worldX = (int)(ox + (int64_t)i * stride);
            int worldZ = (int)(oz + (int64_t)j * stride);
            int y_clamp_true = y_true < 62 ? 62 : y_true;
            int y_clamp_opt  = y_opt < 62 ? 62 : y_opt;
            int y_clamp_mst  = y_mst < 62 ? 62 : y_mst;

            int b_true = sampleBiomeNoise(&ctx->g.bn, 0, worldX, floordiv(y_clamp_true, 4), worldZ, 0, 0);
            int b_opt  = sampleBiomeNoise(&ctx->g.bn, 0, worldX, floordiv(y_clamp_opt, 4), worldZ, 0, 0);
            int b_mst  = sampleBiomeNoise(&ctx->g.bn, 0, worldX, floordiv(y_clamp_mst, 4), worldZ, 0, 0);

            if (b_opt != b_true) biome_mismatch_opt++;
            if (b_mst != b_true) biome_mismatch_master++;

            // 5. 海岸带子集 (60 <= y_true <= 66)
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

int main(void) {
    struct JNINativeInterface_ iface;
    memset(&iface, 0, sizeof(iface));
    iface.GetArrayLength = dummy_GetArrayLength;
    iface.SetIntArrayRegion = dummy_SetIntArrayRegion;
    const struct JNINativeInterface_ *p_iface = &iface;
    JNIEnv *p_env = (JNIEnv *)&p_iface;

    // 5 种子 (3 历史 + 2 新盲测)
    uint64_t seeds[5] = {
        146008555LL,
        123456789LL,
        987654321LL,
        2026110701ULL, // 盲测 1 (日期派生)
        2026110702ULL  // 盲测 2 (日期派生)
    };

    // 5 位置
    typedef struct { int64_t x; int64_t z; const char *name; } Loc;
    Loc locs[5] = {
        {0, 0, "Origin(0,0)"},
        {5000000, 5000000, "Mid(5M,5M)"},
        {-3000000, 2500000, "Q2(-3M,2.5M)"},
        {30000000, 30000000, "Border(30M,30M)"},
        {-25000000, -10000000, "Q3(-25M,-10M)"}
    };

    // 4 形状 (覆盖 4 种相位模 4 与奇偶宽高)
    typedef struct { int ox; int oz; int w; int h; const char *name; } Shape;
    Shape shapes[4] = {
        {0, 0, 256, 256, "Clean(mod4=0)"},
        {1, 1, 257, 257, "OddWidth(257x257)"},
        {-1, -1, 258, 258, "StandardTile(-1,-1,258)"},
        {2, 3, 258, 258, "OffPhase(2,3,258)"}
    };

    printf("========================================================================================================================================\n");
    printf("   COMPREHENSIVE B-MATRIX AUDIT REPORT (5 SEEDS x 5 LOCS x STRIDES x 4 SHAPES)\n");
    printf("========================================================================================================================================\n");
    printf("%-10s | %-16s | %-6s | %-22s | %-7s | %-7s | %-6s | %-11s | %-12s | %-12s | %-12s\n",
           "Seed", "Location", "Stride", "Shape", "Y_MAE", "Y<=2", "Y_Max", "vsMst<=2", "BiomeFlip(O/M)", "WaterFlip(O/M)", "CoastBiomeFlip");
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

        for (int l = 0; l < 5; l++) {
            // 测试 stride 1, 2, 4
            int strides[] = {1, 2, 4};
            for (int st = 0; st < 3; st++) {
                int stride = strides[st];
                // 轮换测试 4 种形状
                int sh_idx = (s + l + st) % 4;
                Shape sh = shapes[sh_idx];

                CaseResult res;
                run_case(p_env, &ctx, seeds[s], locs[l].x + sh.ox, locs[l].z + sh.oz, sh.w, sh.h, stride, &res);

                char bFlip[32], wFlip[32];
                snprintf(bFlip, sizeof(bFlip), "%4.1f%%/%4.1f%%", res.biome_flip_opt, res.biome_flip_master);
                snprintf(wFlip, sizeof(wFlip), "%4.1f%%/%4.1f%%", res.water_flip_opt, res.water_flip_master);

                printf("%-10llu | %-16s | Stride%-1d | %-22s | %7.3f | %6.2f%% | %6d | %10.2f%% | %-14s | %-14s | %6.2f%%\n",
                       (unsigned long long)seeds[s], locs[l].name, stride, sh.name,
                       res.y_mae, res.le2_rate, res.max_err, res.vs_master_le2,
                       bFlip, wFlip, res.coast_biome_flip);
                fflush(stdout);
            }
        }
    }
    return 0;
}
