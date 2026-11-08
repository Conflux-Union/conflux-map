/* Legacy (pre-1.18) overview accuracy matrix.
 *
 * Pits the accelerated legacy overview paths against both reference points
 * the production pipeline pins them to:
 *
 *   1. The frozen pre-optimization behaviour, reconstructed here from the
 *      unchanged primitives: per-pixel full-detail cfxLegacyOverviewHeight
 *      heights (bit-identical to master's formula) over per-row scale-4
 *      biome sampling (master's cfxGenerateBiomesStrided row loop). This
 *      quantifies the macro-grid (stride < 4) and octave-truncation
 *      (stride >= 4) drift, and must match bit-for-bit wherever the dispatch
 *      keeps the full-detail per-pixel entry (micro queries and the small
 *      exact-anchor grids).
 *
 *   2. cubiomes' exact column generation (mapOverworldSurfaceColumns), which
 *      is what the Java quality contract gates on (mean MAE <= 8, p95 <= 24,
 *      cf. PredictionNativeIntegrationTest.
 *      overviewPipelineHasTheSameQualityContractAcrossVersionsAndLods).
 *
 *   3. The banded dense biome generation must reproduce master's per-row
 *      sampling bit-for-bit at every stride >= 4, the single-slot biome memo
 *      must be transparent (the surfaceBiomes pass reads the grid master
 *      would have regenerated), and overlapping windows must agree
 *      cell-for-cell (position purity: the macro lattice is world-aligned,
 *      never window-aligned).
 *
 * Build (same source list as bench_legacy.c):
 *   zig cc -O2 -Inative/jni -Inative/cubiomes native/cubiomes/... \
 *       native/test_legacy_matrix.c -lm -o build/bin/test_legacy_matrix
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <math.h>
#include <jni.h>

#include "shim/confluxnative.c"

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

/* Master's per-row scale-4 biome sampling, rebuilt from the unchanged
 * cfxGenerateBiomes primitive. blockStride must be a multiple of 4. */
static int refBiomesStrided(
    CfxContext *ctx, int blockX, int blockZ, int w, int h, int blockStride, int *out)
{
    const int x4 = floordiv(blockX, 4);
    const int z4 = floordiv(blockZ, 4);
    const int stride = blockStride / 4;
    const int rawWidth = (w - 1) * stride + 1;
    int j;
    for (j = 0; j < h; j++)
    {
        const Range r = { 4, x4, z4 + j*stride, rawWidth, 1, 0, 1 };
        int *row = allocCache(&ctx->g, r);
        if (row == NULL)
            return CFX_ERR_ALLOC;
        const int err = cfxGenerateBiomes(ctx, row, r, CFX_NETHER_ROOF_Y);
        if (err != 0)
        {
            free(row);
            return CFX_ERR_GENERATION;
        }
        int i;
        for (i = 0; i < w; i++)
            out[j*w+i] = row[i*stride];
        free(row);
    }
    return CFX_OK;
}

static int cmp_ints(const int *a, const int *b, int n)
{
    int i;
    for (i = 0; i < n; i++)
        if (a[i] != b[i])
            return i;
    return -1;
}

static int icmp(const void *a, const void *b)
{
    const int x = *(const int *) a, y = *(const int *) b;
    return (x > y) - (x < y);
}

typedef struct {
    long long cases;
    long long sumDiff;
    long long maxDiff;
    long long over2;
    long long waterFlips;
    long long cells;
} DriftStats;

static void accumulateDrift(
    DriftStats *st, const int *opt, const int *ref, int n)
{
    int i;
    for (i = 0; i < n; i++)
    {
        const int d = abs(opt[i] - ref[i]);
        st->sumDiff += d;
        if (d > st->maxDiff)
            st->maxDiff = d;
        if (d > 2)
            st->over2++;
        if ((ref[i] <= 62) != (opt[i] <= 62))
            st->waterFlips++;
    }
    st->cells += n;
    st->cases++;
}

static void printDrift(const char *label, const DriftStats *st)
{
    printf("%s: cases %lld | meanDiff %.4f p95 n/a max %lld over2 %.4f%% waterFlips %.4f%%\n",
        label, st->cases,
        st->cells ? (double) st->sumDiff / st->cells : 0.0,
        st->maxDiff,
        st->cells ? 100.0 * st->over2 / st->cells : 0.0,
        st->cells ? 100.0 * st->waterFlips / st->cells : 0.0);
}

/* Full-tile exact sweep: MAE / p95 / max of the optimized overview against
 * cubiomes' exact solid surface, gated like the Java quality contract. */
static int fullTileExact(
    JNIEnv *env, CfxContext *ctx, int blockX, int blockZ, int stride, int *hardFailures)
{
    const int cells = TILE * TILE;
    int *optH = malloc(sizeof(int) * (size_t) cells);
    int *solid = malloc(sizeof(int) * (size_t) cells);
    int *fluid = malloc(sizeof(int) * (size_t) cells);
    int *surfY = malloc(sizeof(int) * (size_t) cells);
    int *flags = malloc(sizeof(int) * (size_t) cells);
    int rc = 0;
    const jint err = cfxOverviewHeightsImpl(
        env, ctx, blockX, blockZ, TILE, TILE, stride, (jintArray) optH,
        &CFX_DETAIL_FULL);
    const int exErr = mapOverworldSurfaceColumns(
        solid, fluid, surfY, flags, &ctx->g, &ctx->sn,
        blockX, blockZ, TILE, TILE, stride);
    if (err != CFX_OK || exErr != 0)
    {
        printf("FULL-TILE stride=%d: ERROR opt=%d exact=%d\n",
            stride, (int) err, exErr);
        (*hardFailures)++;
        rc = -1;
    }
    else
    {
        double sum = 0.0;
        int k, over8 = 0;
        int *ed = malloc(sizeof(int) * (size_t) cells);
        for (k = 0; k < cells; k++)
        {
            const int d = abs(optH[k] - solid[k]);
            sum += d;
            ed[k] = d;
            if (d > 8)
                over8++;
        }
        qsort(ed, (size_t) cells, sizeof(int), icmp);
        const double mae = sum / cells;
        const int p95 = ed[(int) (cells * 0.95)];
        const int maxD = ed[cells - 1];
        printf("FULL-TILE exact stride=%-2d o=(%d,%d): MAE %.3f p95 %d max %d over8 %.3f%%\n",
            stride, blockX, blockZ, mae, p95, maxD, 100.0 * over8 / cells);
        if (mae > 8.0 || p95 > 24)
        {
            printf("  GATE VIOLATION (Java contract: MAE<=8, p95<=24)\n");
            (*hardFailures)++;
            rc = -1;
        }
        free(ed);
    }
    free(optH); free(solid); free(fluid); free(surfY); free(flags);
    return rc;
}

int main(void)
{
    struct JNINativeInterface_ iface;
    memset(&iface, 0, sizeof(iface));
    iface.GetArrayLength = dummy_GetArrayLength;
    iface.SetIntArrayRegion = dummy_SetIntArrayRegion;
    const struct JNINativeInterface_ *p_iface = &iface;
    JNIEnv *p_env = (JNIEnv *) &p_iface;

    static const int64_t seeds[3] = { 146008555, 123456789, 987654321 };
    /* Tile origins exercise every mod-4 phase (the macro lattice floors),
     * negative coordinates, and inland/coastal spans. */
    static const int origins[6][2] = {
        { 256, -256 }, { 255, 1023 }, { -1022, 512 },
        { 4096, 4097 }, { 254, 258 }, { -4095, -257 },
    };
    static const int strides[5] = { 1, 2, 4, 8, 16 };

    DriftStats macroStats = {0};
    DriftStats truncStats = {0};
    int hardFailures = 0;

    int si, oi, st;
    for (si = 0; si < 3; si++)
    {
        CfxContext ctx;
        memset(&ctx, 0, sizeof(ctx));
        ctx.mc = MC_1_17;
        ctx.dim = DIM_OVERWORLD;
        setupGenerator(&ctx.g, ctx.mc, 0);
        applySeed(&ctx.g, ctx.dim, (uint64_t) seeds[si]);
        initSurfaceNoise(&ctx.sn, ctx.dim, (uint64_t) seeds[si]);

        for (oi = 0; oi < 6; oi++)
        {
            const int blockX = origins[oi][0];
            const int blockZ = origins[oi][1];

            /* Exact-anchor grid gate: LOD0-1's 34x34 stride-8 residual
             * anchors stay on the full-detail per-pixel entry, so they must
             * reproduce master bit-for-bit (own geometry, ref biomes sampled
             * at the same stride-8 grid via the row path). */
            {
                const int g = 34, gs = 8;
                const int gn = g * g;
                int *anchorOpt = malloc(sizeof(int) * (size_t) gn);
                int *anchorRef = malloc(sizeof(int) * (size_t) gn);
                int *anchorB = malloc(sizeof(int) * (size_t) gn);
                const jint e2 = cfxOverviewHeightsImpl(
                    p_env, &ctx, blockX, blockZ, g, g, gs,
                    (jintArray) anchorOpt, &CFX_DETAIL_FULL);
                const int eb = refBiomesStrided(&ctx, blockX, blockZ, g, g, gs, anchorB);
                if (e2 != CFX_OK || eb != CFX_OK)
                {
                    printf("ANCHOR o=(%d,%d): ERROR opt=%d biomes=%d\n",
                        blockX, blockZ, (int) e2, eb);
                    hardFailures++;
                }
                else
                {
                    int j, i;
                    for (j = 0; j < g; j++)
                        for (i = 0; i < g; i++)
                            anchorRef[j*g+i] = cfxLegacyOverviewHeight(
                                &ctx, anchorB[j*g+i], blockX + i*gs, blockZ + j*gs);
                    const int at = cmp_ints(anchorOpt, anchorRef, gn);
                    if (at >= 0)
                    {
                        printf("ANCHOR o=(%d,%d): mismatch at %d (%d vs %d)\n",
                            blockX, blockZ, at, anchorOpt[at], anchorRef[at]);
                        hardFailures++;
                    }
                }
                free(anchorOpt); free(anchorRef); free(anchorB);
            }

            /* Position purity: two overlapping stride-1/2 windows must agree
             * on the shared cells (world-aligned macro lattice). */
            if (oi == 0)
            {
                int pw;
                for (pw = 0; pw < 2; pw++)
                {
                    const int pstride = pw == 0 ? 1 : 2;
                    const int w = 130;
                    const int shift = 64;
                    int *a = malloc(sizeof(int) * (size_t) w * w);
                    int *b = malloc(sizeof(int) * (size_t) w * w);
                    const jint ea = cfxOverviewHeightsImpl(
                        p_env, &ctx, blockX, blockZ, w, w, pstride,
                        (jintArray) a, &CFX_DETAIL_FULL);
                    const jint eb2 = cfxOverviewHeightsImpl(
                        p_env, &ctx, blockX + shift*pstride, blockZ + shift*pstride,
                        w, w, pstride, (jintArray) b, &CFX_DETAIL_FULL);
                    if (ea != CFX_OK || eb2 != CFX_OK)
                    {
                        printf("PURITY s=%d: ERROR %d %d\n", pstride, (int) ea, (int) eb2);
                        hardFailures++;
                    }
                    else
                    {
                        int j, i;
                        for (j = shift; j < w; j++)
                            for (i = shift; i < w; i++)
                                if (a[j*w+i] != b[(j-shift)*w + (i-shift)])
                                {
                                    printf("PURITY s=%d o=(%d,%d): seam at pixel (%d,%d): %d vs %d\n",
                                        pstride, blockX, blockZ, i, j,
                                        a[j*w+i], b[(j-shift)*w + (i-shift)]);
                                    hardFailures++;
                                    j = w; break;
                                }
                    }
                    free(a); free(b);
                }
            }

            for (st = 0; st < 5; st++)
            {
                const int stride = strides[st];
                const int cells = TILE * TILE;
                int *optH = malloc(sizeof(int) * (size_t) cells);
                int *refH = malloc(sizeof(int) * (size_t) cells);
                int *refB = malloc(sizeof(int) * (size_t) cells);
                int *surfB = malloc(sizeof(int) * (size_t) cells);

                const jint errOpt = cfxOverviewHeightsImpl(
                    p_env, &ctx, blockX, blockZ, TILE, TILE, stride,
                    (jintArray) optH, &CFX_DETAIL_FULL);

                /* Reference biome grid: master's row loop at stride >= 4,
                 * the unchanged dense path at stride < 4. */
                const int errRef = stride >= 4
                    ? refBiomesStrided(&ctx, blockX, blockZ, TILE, TILE, stride, refB)
                    : cfxGenerateOverviewBiomes(&ctx, blockX, blockZ, TILE, TILE, stride, refB);

                /* Master's per-pixel full-detail heights. */
                int j, i;
                for (j = 0; j < TILE; j++)
                    for (i = 0; i < TILE; i++)
                        refH[j*TILE+i] = cfxLegacyOverviewHeight(
                            &ctx, refB[j*TILE+i],
                            blockX + i*stride, blockZ + j*stride);

                /* Production dedup pattern: the surfaceBiomes pass after the
                 * overview pass must return the reference grid. */
                const jint errSurf = cfxGenerateOverviewBiomes(
                    &ctx, blockX, blockZ, TILE, TILE, stride, surfB);

                if (errOpt != CFX_OK || errRef != CFX_OK || errSurf != CFX_OK)
                {
                    printf("CASE seed=%lld o=(%d,%d) s=%d: ERROR opt=%d ref=%d surf=%d\n",
                        (long long) seeds[si], blockX, blockZ, stride,
                        (int) errOpt, errRef, (int) errSurf);
                    hardFailures++;
                }
                else
                {
                    const int at = cmp_ints(surfB, refB, cells);
                    if (at >= 0)
                    {
                        printf("CASE seed=%lld o=(%d,%d) s=%d: BIOME mismatch at %d (surf %d ref %d)\n",
                            (long long) seeds[si], blockX, blockZ, stride, at,
                            surfB[at], refB[at]);
                        hardFailures++;
                    }

                    /* Micro-query gate: 1x1 stride-4 solves stay full detail. */
                    if (stride >= 4)
                    {
                        int micro[1];
                        const jint e1 = cfxOverviewHeightsImpl(
                            p_env, &ctx, blockX + 5*stride, blockZ + 7*stride,
                            1, 1, 4, (jintArray) micro, &CFX_DETAIL_FULL);
                        const int refMicro = cfxLegacyOverviewHeight(
                            &ctx, refB[7*TILE+5],
                            blockX + 5*stride, blockZ + 7*stride);
                        if (e1 != CFX_OK || micro[0] != refMicro)
                        {
                            printf("CASE seed=%lld o=(%d,%d) s=%d: MICRO mismatch (%d vs %d, err %d)\n",
                                (long long) seeds[si], blockX, blockZ, stride,
                                micro[0], refMicro, (int) e1);
                            hardFailures++;
                        }
                    }

                    accumulateDrift(stride < 4 ? &macroStats : &truncStats,
                        optH, refH, cells);
                }

                free(optH); free(refH); free(refB); free(surfB);
            }
        }
    }

    /* Full-tile exact sweeps: two origins per stride. */
    {
        CfxContext ctx;
        memset(&ctx, 0, sizeof(ctx));
        ctx.mc = MC_1_17;
        ctx.dim = DIM_OVERWORLD;
        setupGenerator(&ctx.g, ctx.mc, 0);
        applySeed(&ctx.g, ctx.dim, (uint64_t) seeds[0]);
        initSurfaceNoise(&ctx.sn, ctx.dim, (uint64_t) seeds[0]);
        for (st = 0; st < 5; st++)
        {
            fullTileExact(p_env, &ctx, 256, -256, strides[st], &hardFailures);
            fullTileExact(p_env, &ctx, 255, 1023, strides[st], &hardFailures);
        }
    }

    printf("\n=== legacy overview drift vs master reference ===\n");
    printDrift("macro windows (stride<4) ", &macroStats);
    printDrift("trunc windows (stride>=4)", &truncStats);

    printf("\nhard failures: %d\n", hardFailures);
    return hardFailures == 0 ? 0 : 1;
}
