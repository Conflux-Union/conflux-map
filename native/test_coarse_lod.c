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

int main(void) {
    struct JNINativeInterface_ iface;
    memset(&iface, 0, sizeof(iface));
    iface.GetArrayLength = dummy_GetArrayLength;
    iface.SetIntArrayRegion = dummy_SetIntArrayRegion;
    const struct JNINativeInterface_ *p_iface = &iface;
    JNIEnv *p_env = (JNIEnv *)&p_iface;

    uint64_t seeds[5] = {146008555LL, 123456789LL, 987654321LL, 2026110701ULL, 2026110702ULL};
    int64_t locs[3][2] = {{0, 0}, {5000000, 5000000}, {30000000, 30000000}};

    printf("=======================================================================\n");
    printf("   LOD3/4 (Stride 8 & 16) AUDIT (5 Seeds x 3 Locations x 2 Strides)\n");
    printf("=======================================================================\n");

    double total_mae_diff = 0;
    int count = 0;

    for (int s = 0; s < 5; s++) {
        CfxContext ctx;
        memset(&ctx, 0, sizeof(ctx));
        ctx.mc = MC_1_21_1;
        ctx.dim = DIM_OVERWORLD;
        setupGenerator(&ctx.g, ctx.mc, 0);
        applySeed(&ctx.g, ctx.dim, seeds[s]);
        initSurfaceNoise(&ctx.sn, ctx.dim, seeds[s]);

        for (int l = 0; l < 3; l++) {
            int strides[2] = {8, 16};
            for (int st = 0; st < 2; st++) {
                int stride = strides[st];
                const int w = 64, h = 64;
                const int total = w * h;
                int *pred_opt = malloc(total * sizeof(int));
                int *pred_master = malloc(total * sizeof(int));

                CfxNoiseDetail detail_opt = cfxNoiseDetailForStride(&ctx.g.bn.terrain, stride);
                cfxOverviewHeightsImpl(p_env, &ctx, locs[l][0], locs[l][1], w, h, stride, (jintArray)pred_opt, &detail_opt);

                CfxNoiseDetail detail_master = CFX_DETAIL_FULL;
                for (int j = 0; j < h; j++) {
                    const int sampleZ = (int)(locs[l][1] + (int64_t)j * stride);
                    for (int i = 0; i < w; i++) {
                        const int sampleX = (int)(locs[l][0] + (int64_t)i * stride);
                        pred_master[j * w + i] = cfxModernOverviewHeight(&ctx, floordiv(sampleX, 4), floordiv(sampleZ, 4), &detail_master);
                    }
                }

                double diff = 0;
                int exact = 0;
                for (int i = 0; i < total; i++) {
                    int d = abs(pred_opt[i] - pred_master[i]);
                    diff += d;
                    if (d == 0) exact++;
                }

                double mae = diff / total;
                total_mae_diff += mae;
                count++;

                printf("Seed %llu @ Loc(%lld,%lld) Stride %2d: vs Master MAE = %.4f | Exact = %.2f%%\n",
                       (unsigned long long)seeds[s], (long long)locs[l][0], (long long)locs[l][1],
                       stride, mae, (exact * 100.0) / total);

                free(pred_opt);
                free(pred_master);
            }
        }
    }

    printf("\n>>> Overall LOD3/4 vs Master Average MAE: %.4f <<<\n", total_mae_diff / count);
    return 0;
}
