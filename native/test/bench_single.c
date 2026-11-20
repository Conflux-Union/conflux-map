#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <time.h>
#include <string.h>
#include <jni.h>

#include "shim/confluxnative.c"

#define BENCH_SEED 146008555LL
#define REGION_SIZE 512

static inline uint64_t fnv1a_update(uint64_t hash, int32_t val) {
    hash ^= (uint32_t)val;
    hash *= 0x100000001b3ULL;
    return hash;
}

static double get_time_sec(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec * 1e-9;
}

static jsize JNICALL dummy_GetArrayLength(JNIEnv *env, jarray array) {
    (void)env; (void)array; return 10000000;
}
static void JNICALL dummy_SetIntArrayRegion(JNIEnv *env, jintArray array, jsize start, jsize len, const jint *buf) {
    (void)env;
    int *dst = (int *)array;
    memcpy(dst + start, buf, len * sizeof(jint));
}

int main(void) {
    struct JNINativeInterface_ iface;
    memset(&iface, 0, sizeof(iface));
    iface.GetArrayLength = dummy_GetArrayLength;
    iface.SetIntArrayRegion = dummy_SetIntArrayRegion;
    const struct JNINativeInterface_ *p_iface = &iface;
    JNIEnv *p_env = (JNIEnv *)&p_iface;

    CfxContext ctx;
    memset(&ctx, 0, sizeof(ctx));
    ctx.mc = MC_1_21_1;
    ctx.dim = DIM_OVERWORLD;
    setupGenerator(&ctx.g, ctx.mc, 0);
    applySeed(&ctx.g, ctx.dim, BENCH_SEED);
    initSurfaceNoise(&ctx.sn, ctx.dim, BENCH_SEED);

    const int lod_strides[5] = {1, 2, 4, 8, 16};

    printf("=======================================================================\n");
    printf("   [Strict Single-Thread Objective Baseline Benchmark]\n");
    printf("   Region: 512x512 | Seed: %lld | MC: 1.21.1\n", (long long)BENCH_SEED);
    printf("=======================================================================\n");

    for (int lod = 0; lod < 5; lod++) {
        int stride = lod_strides[lod];
        int count_1d = REGION_SIZE / stride;
        int cells = count_1d * count_1d;
        int *heights = (int *)malloc(cells * sizeof(int));

        CfxNoiseDetail detail = cfxNoiseDetailForStride(&ctx.g.bn.terrain, stride);

        // Warmup
        cfxOverviewHeightsImpl(p_env, &ctx, 0, 0, count_1d, count_1d, stride, (jintArray)heights, &detail, 0);

        double total_time = 0.0;
        uint64_t final_chk = 0;
        const int iters = 5;

        for (int iter = 0; iter < iters; iter++) {
            memset(heights, 0x5a, cells * sizeof(int)); // Poison memory to detect unwritten slots
            double t0 = get_time_sec();
            cfxOverviewHeightsImpl(p_env, &ctx, 0, 0, count_1d, count_1d, stride, (jintArray)heights, &detail, 0);
            double t1 = get_time_sec();
            total_time += (t1 - t0);

            uint64_t chk = 0xcbf29ce484222325ULL;
            for (int i = 0; i < cells; i++) {
                chk = fnv1a_update(chk, heights[i]);
            }
            final_chk = chk;
        }

        double avg_ms = (total_time / iters) * 1000.0;
        printf("[LOD %d] Stride=%-2d Cells=%-6d | Single-Core: %8.3f ms | Checksum: 0x%016llx\n",
               lod, stride, cells, avg_ms, (unsigned long long)final_chk);

        free(heights);
    }
    printf("=======================================================================\n");
    return 0;
}
