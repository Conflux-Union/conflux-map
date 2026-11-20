/* Legacy (pre-1.18) overview-pipeline benchmark.
 *
 * Mirrors native/bench_single.c on the perf/cpu-noise-simd-avx2 branch, but
 * targets MC 1.17.1. Per LOD it times, on a fixed 512x512 block region:
 *   - overview:    cfxOverviewHeightsImpl with a fresh origin every iteration,
 *                  i.e. the production per-tile cost (biome generation on the
 *                  first consumer of a window + macro grid / truncated noise);
 *   - biomes-only: the underlying cfxGenerateBiomesStrided generation with no
 *                  memo involvement, for cost attribution;
 *   - memo hit:    cfxGenerateOverviewBiomes on a fixed window, i.e. the
 *                  cfxSurfaceBiomes dedup path (memcpy of the stored grid).
 *
 * Build (host):
 *   zig cc -O2 -Inative/jni -Inative/cubiomes native/cubiomes/{biomenoise,biomes,generator,layers,noise,terrainnoise,finders,util}.c \
 *       native/cubiomes/features/{end_city,fortress,abandoned_camp,stronghold}.c native/cubiomes/terrain_features.c \
 *       native/bench_legacy.c -lm -o build/bin/bench_legacy
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <time.h>
#include <jni.h>

#include "shim/confluxnative.c"

#define BENCH_SEED 146008555LL
#define TILE_SIZE 258   /* BaselineGrid.SIZE: the real per-tile window */
#define TILE_ORIGIN_STEP 512  /* fresh origin per iteration: memo cold, like consecutive tiles */
#define MC_BENCH MC_1_17
#define MC_BENCH_NAME "1.17.1"

static inline uint64_t fnv1a_update(uint64_t hash, int32_t val)
{
    hash ^= (uint32_t) val;
    hash *= 0x100000001b3ULL;
    return hash;
}

static double get_time_sec(void)
{
#ifdef _WIN32
    LARGE_INTEGER freq, counter;
    QueryPerformanceFrequency(&freq);
    QueryPerformanceCounter(&counter);
    return (double) counter.QuadPart / (double) freq.QuadPart;
#else
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec * 1e-9;
#endif
}

static jsize JNICALL dummy_GetArrayLength(JNIEnv *env, jarray array)
{
    (void) env; (void) array; return 10000000;
}
static void JNICALL dummy_SetIntArrayRegion(
    JNIEnv *env, jintArray array, jsize start, jsize len, const jint *buf)
{
    (void) env;
    int *dst = (int *) array;
    memcpy(dst + start, buf, (size_t) len * sizeof(jint));
}
static void JNICALL dummy_GetIntArrayRegion(
    JNIEnv *env, jintArray array, jsize start, jsize len, jint *buf)
{
    (void) env;
    const int *src = (const int *) array;
    memcpy(buf, src + start, (size_t) len * sizeof(jint));
}

static uint64_t checksum(const int *vals, int cells)
{
    uint64_t chk = 0xcbf29ce484222325ULL;
    int i;
    for (i = 0; i < cells; i++)
        chk = fnv1a_update(chk, vals[i]);
    return chk;
}

int main(void)
{
    struct JNINativeInterface_ iface;
    memset(&iface, 0, sizeof(iface));
    iface.GetArrayLength = dummy_GetArrayLength;
    iface.SetIntArrayRegion = dummy_SetIntArrayRegion;
    iface.GetIntArrayRegion = dummy_GetIntArrayRegion;
    const struct JNINativeInterface_ *p_iface = &iface;
    JNIEnv *p_env = (JNIEnv *) &p_iface;

    CfxContext ctx;
    memset(&ctx, 0, sizeof(ctx));
    ctx.mc = MC_BENCH;
    ctx.dim = DIM_OVERWORLD;
    setupGenerator(&ctx.g, ctx.mc, 0);
    applySeed(&ctx.g, ctx.dim, (uint64_t) BENCH_SEED);
    initSurfaceNoise(&ctx.sn, ctx.dim, (uint64_t) BENCH_SEED);

    const int lod_strides[5] = {1, 2, 4, 8, 16};
    const int iters = 5;

    printf("=======================================================================\n");
    printf("   Legacy overview benchmark | Tile: %dx%d per LOD | Seed: %lld | MC: %s\n",
        TILE_SIZE, TILE_SIZE, (long long) BENCH_SEED, MC_BENCH_NAME);
    printf("=======================================================================\n");

    for (int lod = 0; lod < 5; lod++)
    {
        const int stride = lod_strides[lod];
        const int count_1d = TILE_SIZE;
        const int cells = count_1d * count_1d;
        int *buf = (int *) malloc(sizeof(int) * (size_t) cells);

        double ms_overview = 0.0, ms_biomes = 0.0, ms_memo = 0.0;
        uint64_t chk_overview = 0, chk_biomes = 0, chk_memo = 0;

        /* Production cost: a fresh window origin per iteration keeps the
         * single-slot biome memo cold, exactly like consecutive tiles. */
        for (int iter = 0; iter <= iters; iter++)
        {
            const int originX = iter * TILE_ORIGIN_STEP;
            memset(buf, 0x5a, (size_t) cells * sizeof(int));
            const double t0 = get_time_sec();
            const jint err = cfxOverviewHeightsImpl(
                p_env, &ctx, originX, 0, count_1d, count_1d, stride,
                (jintArray) buf, &CFX_DETAIL_FULL, 0);
            const double t1 = get_time_sec();
            if (err != CFX_OK)
            {
                printf("[LOD %d] overview ERROR %d\n", lod, (int) err);
                return 1;
            }
            if (iter == 0)
                continue;
            ms_overview += t1 - t0;
            chk_overview = checksum(buf, cells);
        }

        /* Pure biome generation with no memo involvement. */
        for (int iter = 0; iter <= iters; iter++)
        {
            const int originX = (iter * TILE_ORIGIN_STEP) + TILE_ORIGIN_STEP / 2;
            int err;
            memset(buf, 0x5a, (size_t) cells * sizeof(int));
            const double t0 = get_time_sec();
            if (stride < 4)
                err = cfxGenerateBiomesStrided(
                    &ctx, 1, originX, 0, count_1d, count_1d, stride,
                    CFX_NETHER_ROOF_Y, buf);
            else
                err = cfxGenerateBiomesStrided(
                    &ctx, 4, floordiv(originX, 4), 0, count_1d, count_1d,
                    stride / 4, CFX_NETHER_ROOF_Y, buf);
            const double t1 = get_time_sec();
            if (err != CFX_OK)
            {
                printf("[LOD %d] biomes ERROR %d\n", lod, err);
                return 1;
            }
            if (iter == 0)
                continue;
            ms_biomes += t1 - t0;
            chk_biomes = checksum(buf, cells);
        }

        /* Memo-hit path: fixed window, every call after the first is the
         * cfxSurfaceBiomes dedup memcpy. */
        for (int iter = 0; iter <= iters; iter++)
        {
            memset(buf, 0x5a, (size_t) cells * sizeof(int));
            const double t0 = get_time_sec();
            const jint err = cfxGenerateOverviewBiomes(
                &ctx, 1 << 20, 0, count_1d, count_1d, stride, buf);
            const double t1 = get_time_sec();
            if (err != CFX_OK)
            {
                printf("[LOD %d] memo ERROR %d\n", lod, (int) err);
                return 1;
            }
            if (iter == 0)
                continue;
            ms_memo += t1 - t0;
            chk_memo = checksum(buf, cells);
        }

        printf("[LOD %d] stride=%-2d cells=%-6d | overview %8.3f ms (chk %016llx) | biomes-only %7.3f ms | memo-hit %7.3f ms (chk %016llx)\n",
            lod, stride, cells, ms_overview / iters * 1000.0,
            (unsigned long long) chk_overview, ms_biomes / iters * 1000.0,
            ms_memo / iters * 1000.0, (unsigned long long) chk_memo);
        fflush(stdout);

        free(buf);
    }
    printf("=======================================================================\n");
    return 0;
}
