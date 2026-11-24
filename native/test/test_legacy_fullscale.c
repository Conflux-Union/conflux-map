/* Full-scale block-by-block validation of the accelerated pre-1.18 overview.
 *
 * For one (seed, region) unit this harness walks a 4096x4096 block region in
 * production tile shapes (258x258-pixel windows, interior 256x256, every LOD
 * stride 1/2/4/8/16) and compares, per output cell:
 *
 *   - optimized dispatch heights   (this branch's cfxOverviewHeightsImpl)
 *   - master-equivalent heights    (the frozen per-pixel formula over master's
 *     per-row / dense biome sampling, bit-identical to origin/master)
 *   - cubiomes exact generation    (mapOverworldSurfaceColumns: vanilla's own
 *     terrain model, the same oracle the in-game GameTests calibrate against)
 *
 * Dimensions per cell: surface height (solidY), surface biome id, water/land
 * kind (solid < 62), and fluid depth in the baseline's buckets (0, 1-3, 4-9,
 * 10+). At stride 1 it additionally hunts localized artifacts - the small-area
 * sudden terrain changes a coarse interpolated lattice could in principle
 * introduce or swallow:
 *
 *   - false cliff:  comparator 1-block gradient >= 6 where exact gradient <= 2
 *   - missed cliff: exact gradient >= 6 where comparator gradient <= 2
 *   - spike: |comparator - exact| >= 10 at a cell whose four neighbours all
 *     agree within 3 (an isolated pole the surrounding area does not have)
 *
 * Run one unit per process and aggregate:
 *   test_legacy_fullscale <seedIndex 0..9> <regionIndex 0..4>
 * Output: one "UNIT" line per stride with every metric, machine-parseable.
 *
 * Build (same source list as bench_legacy.c).
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <jni.h>

#include "shim/confluxnative.c"

#define REGION 4096
#define INTERIOR 256
#define WINDOW (INTERIOR + 2) /* production 258x258 margin shape */

static const int64_t kSeeds[10] = {
    1509797269253326924LL, 4589371586495684741LL, 4128368105411603888LL,
    1778750403292628679LL,  67986890840262971LL, 3515529913517636733LL,
    4539869800134215359LL,   374269928810421942LL, 1958584886842335593LL,
    4377252966816779461LL,
};
static const int kRegions[5][2] = {
    { 0, 0 }, { 5000000, 5000000 }, { -5000000, -5000000 },
    { 30000000, 30000000 }, { -30000000, -30000000 },
};

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

/* Master's per-row scale-4 biome sampling (origin/master semantics for
 * stride >= 4; stride < 4 uses the unchanged scale-1 dense path directly). */
static int masterBiomes(
    CfxContext *ctx, int blockX, int blockZ, int w, int h, int blockStride, int *out)
{
    if (blockStride < 4)
        return cfxGenerateBiomesStrided(
            ctx, 1, blockX, blockZ, w, h, blockStride, CFX_NETHER_ROOF_Y, out);
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

typedef struct {
    long long cells;
    double sumAbsDiff;
    long long hit2, hit4;
    long long maxDiff;
    long long waterFlips;
    long long bucketMismatch;
    long long biomeMismatch;
    /* stride-1 artifact hunt */
    long long falseCliffs, missedCliffs;
    long long spikes;
    long long gradHist[7]; /* |gradC-gradE|: 0,1,2,3-4,5-6,7-10,>10 */
} Metrics;

static int fluidBucket(int height)
{
    const int depth = height < 62 ? 62 - height : 0;
    if (depth == 0) return 0;
    if (depth <= 3) return 1;
    if (depth <= 9) return 2;
    return 3;
}

static void accumulateHeights(
    Metrics *m, const int *windowH, int windowW,
    const int *interiorSolid, int interiorW, int interiorH)
{
    int j, i;
    for (j = 0; j < interiorH; j++)
    {
        for (i = 0; i < interiorW; i++)
        {
            const int hVal = windowH[(j + 1) * windowW + (i + 1)];
            const int exVal = interiorSolid[j * interiorW + i];
            const int d = abs(hVal - exVal);
            m->sumAbsDiff += d;
            if (d <= 2) m->hit2++;
            if (d <= 4) m->hit4++;
            if (d > m->maxDiff) m->maxDiff = d;
            if (((hVal < 62) != (exVal < 62))) m->waterFlips++;
            if (fluidBucket(hVal) != fluidBucket(exVal)) m->bucketMismatch++;
            m->cells++;
        }
    }
}

static int grad(const int *h, int w, int i, int j)
{
    const int gx = abs(h[j*w + i + 1] - h[j*w + i]);
    const int gz = abs(h[(j+1)*w + i] - h[j*w + i]);
    return gx > gz ? gx : gz;
}

static int gradBucket(int g)
{
    if (g == 0) return 0;
    if (g == 1) return 1;
    if (g == 2) return 2;
    if (g <= 4) return 3;
    if (g <= 6) return 4;
    if (g <= 10) return 5;
    return 6;
}

int main(int argc, char **argv)
{
    if (argc < 3)
    {
        printf("usage: test_legacy_fullscale <seedIndex 0..9> <regionIndex 0..4>\n");
        return 2;
    }
    const int si = atoi(argv[1]);
    const int ri = atoi(argv[2]);
    if (si < 0 || si > 9 || ri < 0 || ri > 4)
    {
        printf("bad unit indices\n");
        return 2;
    }

    struct JNINativeInterface_ iface;
    memset(&iface, 0, sizeof(iface));
    iface.GetArrayLength = dummy_GetArrayLength;
    iface.SetIntArrayRegion = dummy_SetIntArrayRegion;
    const struct JNINativeInterface_ *p_iface = &iface;
    JNIEnv *p_env = (JNIEnv *) &p_iface;

    CfxContext ctx;
    memset(&ctx, 0, sizeof(ctx));
    ctx.mc = MC_1_17;
    ctx.dim = DIM_OVERWORLD;
    setupGenerator(&ctx.g, ctx.mc, 0);
    applySeed(&ctx.g, ctx.dim, (uint64_t) kSeeds[si]);
    initSurfaceNoise(&ctx.sn, ctx.dim, (uint64_t) kSeeds[si]);

    static const int strides[5] = { 1, 2, 4, 8, 16 };

    int st;
    for (st = 0; st < 5; st++)
    {
        const int stride = strides[st];
        const int tilesPerAxis = REGION / stride / INTERIOR;
        Metrics opt = {0}, mast = {0};
        long long biomeCells = 0, optBiomeAgree = 0, mastBiomeAgree = 0;

        int tj, ti;
        for (tj = 0; tj < tilesPerAxis; tj++)
        {
            for (ti = 0; ti < tilesPerAxis; ti++)
            {
                const int tileX = kRegions[ri][0] + ti * INTERIOR * stride;
                const int tileZ = kRegions[ri][1] + tj * INTERIOR * stride;
                const int winX = tileX - stride; /* P_MIN margin */
                const int winZ = tileZ - stride;
                const int cells = WINDOW * WINDOW;

                int *optH = malloc(sizeof(int) * (size_t) cells);
                int *mastH = malloc(sizeof(int) * (size_t) cells);
                int *optB = malloc(sizeof(int) * (size_t) cells);
                int *mastB = malloc(sizeof(int) * (size_t) cells);
                int *exSolid = malloc(sizeof(int) * (size_t) INTERIOR * INTERIOR);
                int *exFluid = malloc(sizeof(int) * (size_t) INTERIOR * INTERIOR);
                int *exSurf = malloc(sizeof(int) * (size_t) INTERIOR * INTERIOR);
                int *exFlags = malloc(sizeof(int) * (size_t) INTERIOR * INTERIOR);
                int *exB = malloc(sizeof(int) * (size_t) INTERIOR * INTERIOR);
                if (!optH || !mastH || !optB || !mastB || !exSolid || !exFluid
                    || !exSurf || !exFlags || !exB)
                    return 3;

                /* Optimized: the production call pair (overview, then the
                 * surface-biome pass that hits the memo). */
                if (cfxOverviewHeightsImpl(p_env, &ctx, winX, winZ, WINDOW,
                        WINDOW, stride, (jintArray) optH,
                        &CFX_DETAIL_FULL, 0) != CFX_OK)
                    return 4;
                if (cfxGenerateOverviewBiomes(&ctx, winX, winZ, WINDOW,
                        WINDOW, stride, optB) != CFX_OK)
                    return 4;
                if (cfxGenerateOverviewBiomes(&ctx, winX, winZ, WINDOW,
                        WINDOW, stride, optB) != CFX_OK) /* memo path */
                    return 4;

                /* Master-equivalent. */
                if (masterBiomes(&ctx, winX, winZ, WINDOW, WINDOW, stride,
                        mastB) != CFX_OK)
                    return 4;
                int j, i;
                for (j = 0; j < WINDOW; j++)
                    for (i = 0; i < WINDOW; i++)
                        mastH[j*WINDOW+i] = cfxLegacyOverviewHeight(
                            &ctx, mastB[j*WINDOW+i],
                            winX + i*stride, winZ + j*stride);

                /* Exact vanilla columns for the interior. */
                if (mapOverworldSurfaceColumns(exSolid, exFluid, exSurf,
                        exFlags, &ctx.g, &ctx.sn, tileX, tileZ,
                        INTERIOR, INTERIOR, stride) != 0)
                    return 4;

                /* Exact biome: scale-1 single-query semantics (the margin
                 * path) at stride <= 2; scale-4 grid at stride >= 4. */
                if (stride <= 2)
                {
                    if (cfxGenerateBiomesStrided(&ctx, 1, tileX, tileZ,
                            INTERIOR, INTERIOR, stride,
                            CFX_NETHER_ROOF_Y, exB) != CFX_OK)
                        return 4;
                }
                else
                {
                    if (masterBiomes(&ctx, tileX, tileZ, INTERIOR, INTERIOR,
                            stride, exB) != CFX_OK)
                        return 4;
                }

                /* Interior height/kind/depth metrics. */
                accumulateHeights(&opt, optH, WINDOW, exSolid, INTERIOR, INTERIOR);
                accumulateHeights(&mast, mastH, WINDOW, exSolid, INTERIOR, INTERIOR);

                /* Biome agreement on the interior. */
                for (j = 0; j < INTERIOR; j++)
                {
                    for (i = 0; i < INTERIOR; i++)
                    {
                        const int ex = exB[j*INTERIOR+i];
                        biomeCells++;
                        if (optB[(j+1)*WINDOW+(i+1)] == ex) optBiomeAgree++;
                        if (mastB[(j+1)*WINDOW+(i+1)] == ex) mastBiomeAgree++;
                    }
                }

                /* Stride-1 artifact hunt on the full 258 window (interior
                 * cells only for counting; the margin supplies neighbours). */
                if (stride == 1)
                {
                    for (j = 2; j < WINDOW - 2; j++)
                    {
                        for (i = 2; i < WINDOW - 2; i++)
                        {
                            const int idx = j*WINDOW + i;
                            /* exact position for this window cell */
                            const int exi = i - 1, exj = j - 1;
                            const int eIdx = exj*INTERIOR + exi;
                            const int gc = grad(optH, WINDOW, i, j);
                            const int ge = grad(exSolid, INTERIOR, exi, exj);
                            opt.gradHist[gradBucket(abs(gc - ge))]++;
                            if (gc >= 6 && ge <= 2) opt.falseCliffs++;
                            if (ge >= 6 && gc <= 2) opt.missedCliffs++;
                            const int dm = abs(optH[idx] - exSolid[eIdx]);
                            if (dm >= 10
                                && abs(optH[idx-1] - exSolid[eIdx-1]) <= 3
                                && abs(optH[idx+1] - exSolid[eIdx+1]) <= 3
                                && abs(optH[idx-WINDOW] - exSolid[eIdx-INTERIOR]) <= 3
                                && abs(optH[idx+WINDOW] - exSolid[eIdx+INTERIOR]) <= 3)
                                opt.spikes++;
                            const int gcm = grad(mastH, WINDOW, i, j);
                            mast.gradHist[gradBucket(abs(gcm - ge))]++;
                            if (gcm >= 6 && ge <= 2) mast.falseCliffs++;
                            if (ge >= 6 && gcm <= 2) mast.missedCliffs++;
                            const int dmm = abs(mastH[idx] - exSolid[eIdx]);
                            if (dmm >= 10
                                && abs(mastH[idx-1] - exSolid[eIdx-1]) <= 3
                                && abs(mastH[idx+1] - exSolid[eIdx+1]) <= 3
                                && abs(mastH[idx-WINDOW] - exSolid[eIdx-INTERIOR]) <= 3
                                && abs(mastH[idx+WINDOW] - exSolid[eIdx+INTERIOR]) <= 3)
                                mast.spikes++;
                        }
                    }
                }

                free(optH); free(mastH); free(optB); free(mastB);
                free(exSolid); free(exFluid); free(exSurf); free(exFlags);
                free(exB);
            }
        }

        printf("UNIT seed=%d region=%d stride=%d "
            "opt: cells=%lld mae=%.4f p2=%.4f%% p4=%.4f%% max=%lld flips=%.4f%% bucket=%.4f%% biome=%.4f%%"
            " falseCliff=%lld missedCliff=%lld spike=%lld gradHist=%lld,%lld,%lld,%lld,%lld,%lld,%lld\n",
            si, ri, stride,
            opt.cells, opt.sumAbsDiff / opt.cells,
            100.0 * opt.hit2 / opt.cells, 100.0 * opt.hit4 / opt.cells,
            opt.maxDiff, 100.0 * opt.waterFlips / opt.cells,
            100.0 * opt.bucketMismatch / opt.cells,
            stride <= 2 ? 100.0 * optBiomeAgree / biomeCells : -1.0,
            opt.falseCliffs, opt.missedCliffs, opt.spikes,
            opt.gradHist[0], opt.gradHist[1], opt.gradHist[2], opt.gradHist[3],
            opt.gradHist[4], opt.gradHist[5], opt.gradHist[6]);
        printf("UNIT seed=%d region=%d stride=%d "
            "mast: cells=%lld mae=%.4f p2=%.4f%% p4=%.4f%% max=%lld flips=%.4f%% bucket=%.4f%% biome=%.4f%%"
            " falseCliff=%lld missedCliff=%lld spike=%lld gradHist=%lld,%lld,%lld,%lld,%lld,%lld,%lld\n",
            si, ri, stride,
            mast.cells, mast.sumAbsDiff / mast.cells,
            100.0 * mast.hit2 / mast.cells, 100.0 * mast.hit4 / mast.cells,
            mast.maxDiff, 100.0 * mast.waterFlips / mast.cells,
            100.0 * mast.bucketMismatch / mast.cells,
            stride <= 2 ? 100.0 * mastBiomeAgree / biomeCells : -1.0,
            mast.falseCliffs, mast.missedCliffs, mast.spikes,
            mast.gradHist[0], mast.gradHist[1], mast.gradHist[2], mast.gradHist[3],
            mast.gradHist[4], mast.gradHist[5], mast.gradHist[6]);
        fflush(stdout);
    }
    return 0;
}
