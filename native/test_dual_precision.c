#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <math.h>
#include <string.h>
#include <assert.h>
#include <jni.h>

#include "shim/confluxnative.c"

#define CFX_CHECK(expr) do { \
    if (!(expr)) { \
        fprintf(stderr, "FATAL CHECK FAILED: %s at %s:%d\n", #expr, __FILE__, __LINE__); \
        exit(1); \
    } \
} while (0)

static jsize JNICALL dummy_GetArrayLength(JNIEnv *env, jarray array) { return 10000000; }
static void JNICALL dummy_SetIntArrayRegion(JNIEnv *env, jintArray array, jsize start, jsize len, const jint *buf) {
    (void)env; memcpy((int*)array + start, buf, len * sizeof(jint));
}

// 1. Position Purity Property Test
void test_position_purity(JNIEnv *env, CfxContext *ctx) {
    printf("--- Running Position Purity Property Assertions ---\n");
    // Shape A: clean macro tile (0, 0, 512, 512, stride 1)
    const int wA = 512, hA = 512;
    int *bufA = malloc(wA * hA * sizeof(int));
    CfxNoiseDetail detailA = cfxNoiseDetailForStride(&ctx->g.bn.terrain, 1);
    CFX_CHECK(cfxOverviewHeightsImpl(env, ctx, 0, 0, wA, hA, 1, (jintArray)bufA, &detailA) == CFX_OK);

    // Shape B: standard production tile (-1, -1, 258, 258, stride 1)
    const int oxB = -1, ozB = -1, wB = 258, hB = 258;
    int *bufB = malloc(wB * hB * sizeof(int));
    CfxNoiseDetail detailB = cfxNoiseDetailForStride(&ctx->g.bn.terrain, 1);
    CFX_CHECK(cfxOverviewHeightsImpl(env, ctx, oxB, ozB, wB, hB, 1, (jintArray)bufB, &detailB) == CFX_OK);

    // Shape C: off-phase tile (+2, +3, 258, 258, stride 1)
    const int oxC = 2, ozC = 3, wC = 258, hC = 258;
    int *bufC = malloc(wC * hC * sizeof(int));
    CfxNoiseDetail detailC = cfxNoiseDetailForStride(&ctx->g.bn.terrain, 1);
    CFX_CHECK(cfxOverviewHeightsImpl(env, ctx, oxC, ozC, wC, hC, 1, (jintArray)bufC, &detailC) == CFX_OK);

    int mismatch_BA = 0;
    for (int z = 0; z < 256; z++) {
        for (int x = 0; x < 256; x++) {
            if (bufA[z * wA + x] != bufB[(z - ozB) * wB + (x - oxB)])
                mismatch_BA++;
        }
    }
    printf("  Assert Standard Tile vs Clean Grid: Mismatches = %d / 65536\n", mismatch_BA);
    CFX_CHECK(mismatch_BA == 0 && "FATAL: Position purity violated on standard tile geometry!");

    int mismatch_CA = 0;
    for (int z = ozC; z < ozC + wC && z < 256; z++) {
        for (int x = oxC; x < oxC + wC && x < 256; x++) {
            if (bufA[z * wA + x] != bufC[(z - ozC) * wC + (x - oxC)])
                mismatch_CA++;
        }
    }
    printf("  Assert Off-phase Tile vs Clean Grid: Mismatches = %d / 64262\n", mismatch_CA);
    CFX_CHECK(mismatch_CA == 0 && "FATAL: Position purity violated on off-phase geometry!");

    printf("  ===> All Position Purity Assertions PASSED (100%% Bit-Identical Across Shapes)\n\n");

    free(bufA);
    free(bufB);
    free(bufC);
}

void eval_comprehensive_accuracy(JNIEnv *env, CfxContext *ctx, int stride, int64_t ox, int64_t oz) {
    const int count = 256;
    const int total = count * count;
    int *pred = malloc(total * sizeof(int));
    int *truth_solid = malloc(total * sizeof(int));
    int *truth_fluid = malloc(total * sizeof(int));

    CfxNoiseDetail detail = cfxNoiseDetailForStride(&ctx->g.bn.terrain, stride);
    cfxOverviewHeightsImpl(env, ctx, ox, oz, count, count, stride, (jintArray)pred, &detail);
    mapOverworldSurfaceHeightBlocks(truth_solid, truth_fluid, &ctx->g.bn, (int)ox, (int)oz, count, count, stride);

    int diff_within_2 = 0;
    int diff_within_4 = 0;
    double total_diff = 0;
    for (int i = 0; i < total; i++) {
        int d = abs(pred[i] - truth_solid[i]);
        total_diff += d;
        if (d <= 2) diff_within_2++;
        if (d <= 4) diff_within_4++;
    }

    printf("  [Stride %2d] @ (%8lld, %8lld): MAE = %.4f | <=2 Blocks = %6.2f%% | <=4 Blocks = %6.2f%%\n",
           stride, (long long)ox, (long long)oz,
           total_diff / total, (diff_within_2 * 100.0) / total, (diff_within_4 * 100.0) / total);
    fflush(stdout);

    free(pred);
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

    uint64_t seeds[] = {146008555LL, 123456789LL, 987654321LL};

    printf("=====================================================================================\n");
    printf("   COMPREHENSIVE MULTI-SEED & MULTI-STRIDE GROUND TRUTH & PURITY AUDIT\n");
    printf("=====================================================================================\n");

    CfxContext pCtx;
    memset(&pCtx, 0, sizeof(pCtx));
    pCtx.mc = MC_1_21_1;
    pCtx.dim = DIM_OVERWORLD;
    setupGenerator(&pCtx.g, pCtx.mc, 0);
    applySeed(&pCtx.g, pCtx.dim, seeds[0]);
    initSurfaceNoise(&pCtx.sn, pCtx.dim, seeds[0]);
    test_position_purity(p_env, &pCtx);

    for (int s = 0; s < 3; s++) {
        printf(">>> Evaluating Seed: %llu <<<\n", (unsigned long long)seeds[s]);
        fflush(stdout);
        CfxContext ctx;
        memset(&ctx, 0, sizeof(ctx));
        ctx.mc = MC_1_21_1;
        ctx.dim = DIM_OVERWORLD;
        setupGenerator(&ctx.g, ctx.mc, 0);
        applySeed(&ctx.g, ctx.dim, seeds[s]);
        initSurfaceNoise(&ctx.sn, ctx.dim, seeds[s]);

        eval_comprehensive_accuracy(p_env, &ctx, 1, 0, 0);
        eval_comprehensive_accuracy(p_env, &ctx, 2, 0, 0);
        eval_comprehensive_accuracy(p_env, &ctx, 4, 0, 0);
        eval_comprehensive_accuracy(p_env, &ctx, 16, 0, 0);
        eval_comprehensive_accuracy(p_env, &ctx, 1, 5000000, 5000000);
        eval_comprehensive_accuracy(p_env, &ctx, 1, 30000000, 30000000);
    }
    return 0;
}
