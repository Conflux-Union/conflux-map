/* Web predictor legacy-path cross-check.
 *
 * Includes confluxpredict.c directly and compares its pre-1.18 overview
 * heights against the JNI shim's implementation for identical arguments.
 * Both must agree bit-for-bit at every LOD (same cubiomes biome sampling,
 * same macro lattice, same octave truncation), which pins the browser
 * predictor to the same behaviour the desktop pipeline validates. Also
 * verifies that cfxWebInit invalidates the overview biome memo: generating
 * the same window after a seed change must follow the new seed.
 *
 * Build (same source list as bench_legacy.c, plus the web predictor):
 *   zig cc -O2 -Inative/jni -Inative/cubiomes native/cubiomes/... \
 *       native/test_web_legacy.c -lm -o build/bin/test_web_legacy
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <jni.h>

/* The web predictor defines exported symbols with visibility attributes and
 * module-level state; including it twice is not possible, so pull the shim in
 * with a header-style include guard collision avoided by compiling them as
 * one translation unit is not an option either (both define floordiv-using
 * statics with distinct names - fine - but both define JNIEXPORT functions
 * only in the shim; the web file exports cfxWeb* only). One TU, both files:
 * name collisions are limited to cubiomes headers, which have guards. */
#include "shim/confluxnative.c"
#include "web/confluxpredict.c"

#define TILE 258

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

int main(void)
{
    struct JNINativeInterface_ iface;
    memset(&iface, 0, sizeof(iface));
    iface.GetArrayLength = dummy_GetArrayLength;
    iface.SetIntArrayRegion = dummy_SetIntArrayRegion;
    const struct JNINativeInterface_ *p_iface = &iface;
    JNIEnv *p_env = (JNIEnv *) &p_iface;

    static const int64_t seeds[2] = { 146008555, 987654321 };
    static const int origins[2][2] = { { 256, -256 }, { 255, 1023 } };
    static const int strides[5] = { 1, 2, 4, 8, 16 };
    int hardFailures = 0;

    int si, oi, st;
    for (si = 0; si < 2; si++)
    {
        CfxContext ctx;
        memset(&ctx, 0, sizeof(ctx));
        ctx.mc = MC_1_17;
        ctx.dim = DIM_OVERWORLD;
        setupGenerator(&ctx.g, ctx.mc, 0);
        applySeed(&ctx.g, ctx.dim, (uint64_t) seeds[si]);
        initSurfaceNoise(&ctx.sn, ctx.dim, (uint64_t) seeds[si]);
        if (cfxWebInit(MC_1_17, seeds[si], DIM_OVERWORLD, 0) != CFX_WEB_OK)
        {
            printf("cfxWebInit failed for seed %lld\n", (long long) seeds[si]);
            return 1;
        }

        for (oi = 0; oi < 2; oi++)
        {
            for (st = 0; st < 5; st++)
            {
                const int stride = strides[st];
                const int cells = TILE * TILE;
                int *webH = malloc(sizeof(int) * (size_t) cells);
                int *shimH = malloc(sizeof(int) * (size_t) cells);

                const int errWeb = cfxWebOverviewHeights(
                    origins[oi][0], origins[oi][1], TILE, TILE, stride, webH);
                const jint errShim = cfxOverviewHeightsImpl(
                    p_env, &ctx, origins[oi][0], origins[oi][1], TILE, TILE,
                    stride, (jintArray) shimH, &CFX_DETAIL_FULL, 0);
                if (errWeb != CFX_WEB_OK || errShim != CFX_OK)
                {
                    printf("seed %lld o=(%d,%d) s=%d: ERROR web=%d shim=%d\n",
                        (long long) seeds[si], origins[oi][0], origins[oi][1],
                        stride, errWeb, (int) errShim);
                    hardFailures++;
                }
                else
                {
                    int k, at = -1;
                    for (k = 0; k < cells; k++)
                        if (webH[k] != shimH[k])
                        {
                            at = k;
                            break;
                        }
                    if (at >= 0)
                    {
                        printf("seed %lld o=(%d,%d) s=%d: WEB/SHIM mismatch at %d (%d vs %d)\n",
                            (long long) seeds[si], origins[oi][0], origins[oi][1],
                            stride, at, webH[at], shimH[at]);
                        hardFailures++;
                    }
                }
                free(webH); free(shimH);
            }
        }

        /* Memo invalidation: the second seed reuses the first seed's tile
         * geometry through a fresh cfxWebInit; a stale memo would keep the
         * old biome grid and diverge from the shim immediately. (The loop
         * above already generated every geometry for this seed, so the memo
         * slots hold this seed's grids until the next cfxWebInit clears
         * them - covered by the next si iteration.) */
    }

    /* Explicit seed-swap probe: generate at seed A, re-init to seed B, and
     * compare the same geometry against the shim at seed B. */
    {
        CfxContext ctxB;
        memset(&ctxB, 0, sizeof(ctxB));
        ctxB.mc = MC_1_17;
        ctxB.dim = DIM_OVERWORLD;
        setupGenerator(&ctxB.g, ctxB.mc, 0);
        applySeed(&ctxB.g, ctxB.dim, (uint64_t) seeds[1]);
        initSurfaceNoise(&ctxB.sn, ctxB.dim, (uint64_t) seeds[1]);

        if (cfxWebInit(MC_1_17, seeds[0], DIM_OVERWORLD, 0) != CFX_WEB_OK)
            return 1;
        int *warm = malloc(sizeof(int) * (size_t) TILE * TILE);
        cfxWebOverviewHeights(256, -256, TILE, TILE, 1, warm);
        free(warm);
        if (cfxWebInit(MC_1_17, seeds[1], DIM_OVERWORLD, 0) != CFX_WEB_OK)
            return 1;

        const int cells = TILE * TILE;
        int *webH = malloc(sizeof(int) * (size_t) cells);
        int *shimH = malloc(sizeof(int) * (size_t) cells);
        cfxWebOverviewHeights(256, -256, TILE, TILE, 1, webH);
        cfxOverviewHeightsImpl(p_env, &ctxB, 256, -256, TILE, TILE, 1,
            (jintArray) shimH, &CFX_DETAIL_FULL, 0);
        int k, at = -1;
        for (k = 0; k < cells; k++)
            if (webH[k] != shimH[k])
            {
                at = k;
                break;
            }
        if (at >= 0)
        {
            printf("SEED-SWAP: stale memo detected at %d (%d vs %d)\n",
                at, webH[at], shimH[at]);
            hardFailures++;
        }
        free(webH); free(shimH);
    }

    printf("web legacy cross-check failures: %d\n", hardFailures);
    return hardFailures == 0 ? 0 : 1;
}
