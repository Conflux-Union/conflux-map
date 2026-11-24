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
 *   4. COLSTRAT2: the shim's exact-column path (cfxSurfaceColumns ->
 *      cfxLegacyExactColumns, the lazy quart-column solver) must be
 *      bit-identical to cubiomes' mapOverworldSurfaceColumns in all four
 *      planes at the same geometry. COLSTRAT only compares cubiomes' dense
 *      sweep against its own 1x1 loop, so without COLSTRAT2 nothing in the
 *      tree catches a transcription slip in the lazy solver.
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
    long long sumSignedDiff;
    long long posDiffs;
    long long negDiffs;
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
        const int diff = opt[i] - ref[i];
        const int d = abs(diff);
        st->sumDiff += d;
        st->sumSignedDiff += diff;
        if (diff > 0) st->posDiffs++;
        else if (diff < 0) st->negDiffs++;
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
    printf("%s: cases %lld | meanDiff %.4f (signed %+.4f) max %lld over2 %.4f%% waterFlips %.4f%%\n",
        label, st->cases,
        st->cells ? (double) st->sumDiff / st->cells : 0.0,
        st->cells ? (double) st->sumSignedDiff / st->cells : 0.0,
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
        &CFX_DETAIL_FULL, stride >= 4 ? 1 : 0);
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

/* Positive evidence for CI logs: gates that only print on failure would
 * otherwise look like they never ran. */
static int colstrat2Cases = 0;
static int truncentryCases = 0;

/* COLSTRAT2: the shim's exact-column planes must equal cubiomes' at the same
 * geometry. This is the only gate that exercises cfxLegacyExactColumns (the
 * lazy quart-column solver behind cfxSurfaceColumns). */
static void checkSurfaceColumns(
    JNIEnv *env, CfxContext *ctx, long long seed, int blockX, int blockZ,
    int g, int gs, int *hardFailures)
{
    const int n = g * g;
    int *ref[4], *opt[4];
    colstrat2Cases++;
    int k;
    for (k = 0; k < 4; k++)
    {
        ref[k] = malloc(sizeof(int) * (size_t) n);
        opt[k] = malloc(sizeof(int) * (size_t) n);
        if (ref[k] == NULL || opt[k] == NULL)
        {
            printf("COLSTRAT2 seed=%lld o=(%d,%d) s=%d: ERROR alloc\n",
                seed, blockX, blockZ, gs);
            (*hardFailures)++;
            for (k = 0; k < 4; k++) { free(ref[k]); free(opt[k]); }
            return;
        }
    }
    const int er = mapOverworldSurfaceColumns(
        ref[0], ref[1], ref[2], ref[3],
        &ctx->g, &ctx->sn, blockX, blockZ, g, g, gs);
    const jint eo =
        Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxSurfaceColumns(
            env, NULL, (jlong) (intptr_t) ctx, blockX, blockZ, g, g, gs,
            (jintArray) opt[0], (jintArray) opt[1],
            (jintArray) opt[2], (jintArray) opt[3]);
    if (er != 0 || eo != CFX_OK)
    {
        printf("COLSTRAT2 seed=%lld o=(%d,%d) s=%d: ERROR cubiomes=%d shim=%d\n",
            seed, blockX, blockZ, gs, er, (int) eo);
        (*hardFailures)++;
    }
    else
    {
        for (k = 0; k < 4; k++)
        {
            const int at = cmp_ints(ref[k], opt[k], n);
            if (at >= 0)
            {
                printf("COLSTRAT2 seed=%lld o=(%d,%d) s=%d: plane %d mismatch at (%d,%d) (%d vs %d)\n",
                    seed, blockX, blockZ, gs, k, at % g, at / g,
                    ref[k][at], opt[k][at]);
                (*hardFailures)++;
            }
        }
    }
    for (k = 0; k < 4; k++) { free(ref[k]); free(opt[k]); }
}

/* TRUNCENTRY: truncation must follow the entry, not the window geometry.
 * A 64x64 stride-8 window is 4096 cells (at CFX_LEGACY_TRUNCATE_MIN_CELLS),
 * so it sits exactly where the old geometry gate would truncate: the full
 * entry must still reproduce master bit-for-bit, and the coarse entry must
 * still actually truncate (otherwise the optimisation silently died). */
static void checkTruncEntryGates(
    JNIEnv *env, CfxContext *ctx, long long seed, int blockX, int blockZ,
    int *hardFailures)
{
    const int g = 64, gs = 8;
    const int n = g * g;
    truncentryCases++;
    int *biomes = malloc(sizeof(int) * (size_t) n);
    int *full = malloc(sizeof(int) * (size_t) n);
    int *coarse = malloc(sizeof(int) * (size_t) n);
    if (biomes == NULL || full == NULL || coarse == NULL)
    {
        printf("TRUNCENTRY seed=%lld: ERROR alloc\n", seed);
        (*hardFailures)++;
        free(biomes); free(full); free(coarse);
        return;
    }
    if (refBiomesStrided(ctx, blockX, blockZ, g, g, gs, biomes) != CFX_OK)
    {
        printf("TRUNCENTRY seed=%lld o=(%d,%d): ERROR biomes\n", seed, blockX, blockZ);
        (*hardFailures)++;
        free(biomes); free(full); free(coarse);
        return;
    }
    const jint ef = cfxOverviewHeightsImpl(
        env, ctx, blockX, blockZ, g, g, gs, (jintArray) full, &CFX_DETAIL_FULL, 0);
    const CfxNoiseDetail coarseDetail =
        cfxNoiseDetailForStride(&ctx->g.bn.terrain, gs);
    const jint ec = cfxOverviewHeightsImpl(
        env, ctx, blockX, blockZ, g, g, gs, (jintArray) coarse, &coarseDetail, 1);
    if (ef != CFX_OK || ec != CFX_OK)
    {
        printf("TRUNCENTRY seed=%lld o=(%d,%d): ERROR full=%d coarse=%d\n",
            seed, blockX, blockZ, (int) ef, (int) ec);
        (*hardFailures)++;
    }
    else
    {
        int j, i, mismatch = 0, truncated = 0;
        for (j = 0; j < g; j++)
            for (i = 0; i < g; i++)
            {
                const int idx = j * g + i;
                const int ref = cfxLegacyOverviewHeight(
                    ctx, biomes[idx], blockX + i * gs, blockZ + j * gs);
                if (full[idx] != ref) mismatch++;
                if (coarse[idx] != full[idx]) truncated++;
            }
        if (mismatch)
        {
            printf("TRUNCENTRY seed=%lld o=(%d,%d): full entry truncated %d cells (contract violation)\n",
                seed, blockX, blockZ, mismatch);
            (*hardFailures)++;
        }
        if (!truncated)
        {
            printf("TRUNCENTRY seed=%lld o=(%d,%d): coarse entry did not truncate (optimisation dead)\n",
                seed, blockX, blockZ);
            (*hardFailures)++;
        }
    }
    free(biomes); free(full); free(coarse);
}

int main(int argc, char **argv)
{
    const int fullSweep = argc > 1 && strcmp(argv[1], "--full") == 0;
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
                    (jintArray) anchorOpt, &CFX_DETAIL_FULL, 0);
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

            /* Exact-column strategy gate: the dense strided call and the
             * per-anchor 1x1 loop must produce identical solid/fluid/surface
             * flag quadruples (the dispatch picks between them on a cost
             * model, never on semantics). */
            {
                const int g = 34, gs = 8;
                const int gn = g * g;
                int *dense[4], *sparse[4];
                int k;
                for (k = 0; k < 4; k++)
                {
                    dense[k] = malloc(sizeof(int) * (size_t) gn);
                    sparse[k] = malloc(sizeof(int) * (size_t) gn);
                }
                const int ed = mapOverworldSurfaceColumns(
                    dense[0], dense[1], dense[2], dense[3],
                    &ctx.g, &ctx.sn, blockX, blockZ, g, g, gs);
                int es = 0;
                int j, i;
                for (j = 0; j < g && es == 0; j++)
                    for (i = 0; i < g && es == 0; i++)
                        es = mapOverworldSurfaceColumns(
                            sparse[0] + j*g+i, sparse[1] + j*g+i,
                            sparse[2] + j*g+i, sparse[3] + j*g+i,
                            &ctx.g, &ctx.sn, blockX + i*gs, blockZ + j*gs,
                            1, 1, 1);
                if (ed != 0 || es != 0)
                {
                    printf("COLSTRAT o=(%d,%d): ERROR dense=%d sparse=%d\n",
                        blockX, blockZ, ed, es);
                    hardFailures++;
                }
                else
                {
                    for (k = 0; k < 4; k++)
                    {
                        const int at = cmp_ints(dense[k], sparse[k], gn);
                        if (at >= 0)
                        {
                            printf("COLSTRAT o=(%d,%d): plane %d mismatch at %d (%d vs %d)\n",
                                blockX, blockZ, k, at, dense[k][at], sparse[k][at]);
                            hardFailures++;
                        }
                    }
                }
                for (k = 0; k < 4; k++)
                {
                    free(dense[k]);
                    free(sparse[k]);
                }
            }

            /* COLSTRAT2: production LOD0 anchor geometry (34x34 stride 8) on
             * every seed/origin, plus the other strides on two origins to bound
             * runtime. Pass --full to sweep all 90 combinations. */
            {
                static const int sc[5] = { 1, 4, 8, 16, 32 };
                int q;
                for (q = 0; q < 5; q++)
                {
                    if (sc[q] != 8 && !fullSweep && (si != 0 || oi >= 2))
                        continue;
                    checkSurfaceColumns(
                        p_env, &ctx, (long long) seeds[si], blockX, blockZ,
                        34, sc[q], &hardFailures);
                }
            }

            /* TRUNCENTRY: entry contract for truncation, once per seed. */
            if (oi == 0)
                checkTruncEntryGates(p_env, &ctx, (long long) seeds[si],
                    blockX, blockZ, &hardFailures);

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
                        (jintArray) a, &CFX_DETAIL_FULL, 0);
                    const jint eb2 = cfxOverviewHeightsImpl(
                        p_env, &ctx, blockX + shift*pstride, blockZ + shift*pstride,
                        w, w, pstride, (jintArray) b, &CFX_DETAIL_FULL, 0);
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

                /* Mirror the production entry: the coarse lattice runs through
                 * cfxOverviewHeightsCoarse, which is the only caller allowed to
                 * truncate. Truncation is bound to the entry, never to the
                 * window geometry. */
                const CfxNoiseDetail latticeDetail = stride >= 4
                    ? cfxNoiseDetailForStride(&ctx.g.bn.terrain, stride)
                    : CFX_DETAIL_FULL;
                const jint errOpt = cfxOverviewHeightsImpl(
                    p_env, &ctx, blockX, blockZ, TILE, TILE, stride,
                    (jintArray) optH, &latticeDetail, stride >= 4 ? 1 : 0);

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
                            1, 1, 4, (jintArray) micro, &CFX_DETAIL_FULL, 0);
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

    /* Enforce rigid drift thresholds: macro & trunc must never exceed maxDiff=1,
     * over2 must be strictly 0, and meanDiff must not regress beyond 0.05.
     * Any synthetic or real +2 error mutation will trip this and fail hard. */
    if (macroStats.maxDiff > 1 || macroStats.over2 > 0 ||
        (macroStats.cells && (double) macroStats.sumDiff / macroStats.cells > 0.05))
    {
        printf("FAIL: macroStats drift threshold breached! maxDiff=%lld over2=%lld meanDiff=%.4f\n",
            macroStats.maxDiff, macroStats.over2,
            macroStats.cells ? (double) macroStats.sumDiff / macroStats.cells : 0.0);
        hardFailures++;
    }
    if (truncStats.maxDiff > 1 || truncStats.over2 > 0 ||
        (truncStats.cells && (double) truncStats.sumDiff / truncStats.cells > 0.05))
    {
        printf("FAIL: truncStats drift threshold breached! maxDiff=%lld over2=%lld meanDiff=%.4f\n",
            truncStats.maxDiff, truncStats.over2,
            truncStats.cells ? (double) truncStats.sumDiff / truncStats.cells : 0.0);
        hardFailures++;
    }

    printf("\ngates: COLSTRAT2 %d cases, TRUNCENTRY %d cases\n",
        colstrat2Cases, truncentryCases);
    printf("\nhard failures: %d\n", hardFailures);
    return hardFailures == 0 ? 0 : 1;
}
