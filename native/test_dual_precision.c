#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <math.h>
#include <string.h>
#include <jni.h>

#include "shim/confluxnative.c"

static jsize JNICALL dummy_GetArrayLength(JNIEnv *env, jarray array) { return 10000000; }
static void JNICALL dummy_SetIntArrayRegion(JNIEnv *env, jintArray array, jsize start, jsize len, const jint *buf) {
    (void)env; memcpy((int*)array + start, buf, len * sizeof(jint));
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
    printf("   COMPREHENSIVE MULTI-SEED & MULTI-STRIDE GROUND TRUTH PRECISION AUDIT\n");
    printf("=====================================================================================\n");

    for (int s = 0; s < 3; s++) {
        printf("\n>>> Evaluating Seed: %llu <<<\n", (unsigned long long)seeds[s]);
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
