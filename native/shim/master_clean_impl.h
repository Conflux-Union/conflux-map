/*
 * FROZEN MASTER BASELINE SNAPSHOT
 * Source: origin/master commit a966dcf124fbfb26234f82570f710e728e946d93 (native/shim/confluxnative.c)
 * Generated via prefix-isolation for bit-exact in-memory reference parity testing.
 * Do NOT edit manually. If origin/master modifies overview logic, re-extract this snapshot.
 */
/*
 * confluxnative - flat handle-based JNI shim around vendored cubiomes.
 *
 * This file is Conflux Map's own code (GPL-3.0), NOT part of the vendored
 * cubiomes sources in native/cubiomes/ (MIT, see native/CUBIOMES_COMMIT).
 * It only ever includes cubiomes' public headers and calls its public API;
 * no cubiomes internals are duplicated here.
 *
 * Every exported function is batch-only and takes a "handle" - an opaque
 * pointer to a malloc'd MasterCfxContext, created by master_cfxCreate and freed by
 * master_cfxDestroy. A context is NOT thread-safe to share concurrently: cubiomes'
 * Generator/SurfaceNoise structs hold no global state (confirmed by
 * inspection - every mutable field lives inside the struct itself), so two
 * contexts on two threads never interfere with each other, but one context
 * must only ever be driven by one thread at a time. The Java side enforces
 * this by construction (CubiomesContext is documented thread-confined).
 *
 * Status codes returned by the query functions (0 is always success):
 *   0  MASTER_CFX_OK
 *   1  MASTER_CFX_ERR_BAD_HANDLE    - handle was 0/NULL
 *   2  MASTER_CFX_ERR_BAD_SIZE      - w/h/cap out of range (<=0 or over the cap)
 *   3  MASTER_CFX_ERR_BAD_ARGS      - other invalid argument (e.g. unsupported scale)
 *   4  MASTER_CFX_ERR_ALLOC         - native allocation failed
 *   5  MASTER_CFX_ERR_GENERATION    - cubiomes reported a generation failure
 *   6  MASTER_CFX_ERR_WRONG_DIM     - handle's dimension doesn't support this query
 *   7  MASTER_CFX_ERR_FEATURE_PARTIAL - requested feature coverage is unavailable
 */

/* cpu_set_t/sched_setaffinity are _GNU_SOURCE extensions in glibc; the feature
 * macro must be set before the first system header is included. */
#if defined(__linux__) && defined(__x86_64__)
#define _GNU_SOURCE
#endif

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <limits.h>
#include <math.h>

#include "finders.h"
#include "features/end_city.h"
#include "terrain_features.h"

#define MASTER_CFX_ABI 15
#define MASTER_CFX_NETHER_ROOF_Y 127

#define MASTER_CFX_OK              0
#define MASTER_CFX_ERR_BAD_HANDLE  1
#define MASTER_CFX_ERR_BAD_SIZE    2
#define MASTER_CFX_ERR_BAD_ARGS    3
#define MASTER_CFX_ERR_ALLOC       4
#define MASTER_CFX_ERR_GENERATION  5
#define MASTER_CFX_ERR_WRONG_DIM   6
#define MASTER_CFX_ERR_FEATURE_PARTIAL 7

/* Largest w*h (or region count) accepted by any batch query. */
#define MASTER_CFX_MAX_CELLS (1 << 20)

typedef struct {
    Generator g;
    SurfaceNoise sn; /* Overworld terrain columns and End surface heights. */
    BlendedNoise netherNoise;
    int netherNoiseReady;
    int mc;
    int dim;
} MasterCfxContext;

static MasterCfxContext *master_cfxHandle(jlong handle) {
    return (MasterCfxContext *) (intptr_t) handle;
}

static int master_cfxValidCells(jint w, jint h) {
    if (w <= 0 || h <= 0) {
        return 0;
    }
    return (int64_t) w * (int64_t) h <= MASTER_CFX_MAX_CELLS;
}

static int master_cfxValidScale(jint scale) {
    return scale == 1 || scale == 4 || scale == 16 || scale == 64 || scale == 256;
}

static int master_cfxGenerateBiomes(MasterCfxContext *ctx, int *out, Range r, int netherY) {
    if (ctx->dim == DIM_NETHER) {
        /* Nether biomes are three-dimensional: scale 1 takes block coordinates, while every
         * coarser supported scale addresses the underlying 1:4 noise lattice on Y. */
        r.y = r.scale == 1 ? netherY : floordiv(netherY, 4);
    }
    if (ctx->dim == DIM_OVERWORLD && ctx->mc >= MC_1_18
        && (r.scale == 1 || r.scale == 4)) {
        return mapOverworldSurfaceBiome(
            out, &ctx->g.bn, ctx->g.sha, r.scale, r.x, r.z, r.sx, r.sz
        );
    }
    return genBiomes(&ctx->g, out, r);
}

static int master_cfxGenerateBiomesStrided(
    MasterCfxContext *ctx, int scale, int x, int z, int w, int h, int stride,
    int netherY, int *sampled
) {
    const int64_t rawWidth64 = (int64_t) (w - 1) * stride + 1;
    const int64_t rawHeight64 = (int64_t) (h - 1) * stride + 1;
    if (rawWidth64 <= 0 || rawWidth64 > MASTER_CFX_MAX_CELLS
        || rawHeight64 <= 0 || rawHeight64 > MASTER_CFX_MAX_CELLS)
        return MASTER_CFX_ERR_BAD_SIZE;
    const int rawWidth = (int) rawWidth64;

    if (scale == 1)
    {
        /* The final Voronoi layer is not query-shape invariant, so scale-one
         * sampling must retain the full 2D source. cubiomes derives the
         * Voronoi source plane with a "-2 then floor" origin, which for
         * origins at 2 or 3 mod 4 omits the west/north neighbour cells, so
         * patches of boundary cells resolve to the wrong biome (ocean seams
         * on 1.17 tile edges). Query a two-cell margin and read only the
         * interior so every sampled cell matches an isolated single-column
         * query. */
        const int margin = 8;
        const int64_t denseWidth64 = rawWidth64 + 2*margin;
        const int64_t denseHeight64 = rawHeight64 + 2*margin;
        if (denseWidth64 * denseHeight64 > MASTER_CFX_MAX_CELLS)
            return MASTER_CFX_ERR_BAD_SIZE;
        const int denseWidth = (int) denseWidth64;
        const int denseHeight = (int) denseHeight64;
        const Range r = { scale, x - margin, z - margin, denseWidth, denseHeight, 0, 1 };
        int *dense = allocCache(&ctx->g, r);
        if (dense == NULL)
            return MASTER_CFX_ERR_ALLOC;
        const int err = master_cfxGenerateBiomes(ctx, dense, r, netherY);
        if (err != 0)
        {
            free(dense);
            return MASTER_CFX_ERR_GENERATION;
        }
        int j, i;
        for (j = 0; j < h; j++)
            for (i = 0; i < w; i++)
                sampled[j*w+i] =
                    dense[(j*stride + margin)*denseWidth + (i*stride + margin)];
        free(dense);
        return MASTER_CFX_OK;
    }

    int j;
    for (j = 0; j < h; j++)
    {
        const Range r = { scale, x, z + j*stride, rawWidth, 1, 0, 1 };
        int *row = allocCache(&ctx->g, r);
        if (row == NULL)
            return MASTER_CFX_ERR_ALLOC;
        const int err = master_cfxGenerateBiomes(ctx, row, r, netherY);
        if (err != 0)
        {
            free(row);
            return MASTER_CFX_ERR_GENERATION;
        }
        int i;
        for (i = 0; i < w; i++)
            sampled[j*w+i] = row[i*stride];
        free(row);
    }
    return MASTER_CFX_OK;
}

static int master_cfxGenerateOverviewBiomes(
    MasterCfxContext *ctx, int blockX, int blockZ, int w, int h, int blockStride, int *out
) {
    if (blockStride < 4)
        return master_cfxGenerateBiomesStrided(
            ctx, 1, blockX, blockZ, w, h, blockStride, MASTER_CFX_NETHER_ROOF_Y, out
        );
    if (blockStride % 4 != 0)
        return MASTER_CFX_ERR_BAD_ARGS;
    return master_cfxGenerateBiomesStrided(
        ctx, 4, floordiv(blockX, 4), floordiv(blockZ, 4),
        w, h, blockStride / 4, MASTER_CFX_NETHER_ROOF_Y, out
    );
}

/* JNI function stripped */

/* JNI function stripped */

/* JNI function stripped */

/* JNI function stripped */

/* JNI function stripped */

/* JNI function stripped */

/*
 * Sparse coarse-biome sampling for wide-area search. master_cfxBiomesStrided materializes
 * dense source rows and discards (stride-1)/stride of that work, which dominates any
 * search grid whose stride exceeds the biome cell; here every output cell is exactly
 * one independent biome-source query. Supported combinations only - Overworld
 * post-1.18 at scale 16 (climate query, no terrain solve), Nether at scale 4, End at
 * scale 4/16. Overworld scale 4 embeds the exact terrain surface solve (hundreds of
 * microseconds per cell) and pre-1.18 Overworld biome stacks are layer-based, so both
 * stay on the dense path; anything unsupported returns MASTER_CFX_ERR_BAD_ARGS and the caller
 * falls back.
 */
/* JNI function stripped */

/* The scale-16 grid samples at quart y=0, where a column under tall terrain resolves
 * to an underground biome (a stony-peaks summit with surface y=181 reads deep-dark),
 * so surface-only biomes are invisible to the scan at any stride. Re-test the climate
 * on the surface slice: depth 0 is where the caller's surface refinement samples, and
 * the parameter pre-filter has already validated every horizontal parameter. */
static int master_cfxSurfaceSliceIsTarget(const MasterCfxContext *ctx, int cx, int cz, int target)
{
    const double qx = (double) (cx * 4 + 2);
    const double qz = (double) (cz * 4 + 2);
    const float t = (float) sampleDoublePerlin(
        &ctx->g.bn.climate[NP_TEMPERATURE], qx, 0, qz);
    const float h = (float) sampleDoublePerlin(
        &ctx->g.bn.climate[NP_HUMIDITY], qx, 0, qz);
    const float c = (float) sampleDoublePerlin(
        &ctx->g.bn.climate[NP_CONTINENTALNESS], qx, 0, qz);
    const float e = (float) sampleDoublePerlin(
        &ctx->g.bn.climate[NP_EROSION], qx, 0, qz);
    const float w = (float) sampleDoublePerlin(
        &ctx->g.bn.climate[NP_WEIRDNESS], qx, 0, qz);
    const uint64_t np[6] = {
        (uint64_t) (int64_t) (10000.0F * t),
        (uint64_t) (int64_t) (10000.0F * h),
        (uint64_t) (int64_t) (10000.0F * c),
        (uint64_t) (int64_t) (10000.0F * e),
        0,
        (uint64_t) (int64_t) (10000.0F * w),
    };
    return climateToBiome(ctx->g.bn.mc, np, NULL) == target;
}

/*
 * Search-targeted variant of master_cfxBiomesSparseStrided: only cells that could still be
 * the target biome pay the full biome query. Overworld post-1.18 at scale 16 consults
 * the target's climate-parameter envelope (getBiomeParaLimits). The scale-16 full
 * query samples its climate parameters at quart coordinates (x + i*stride)*4 + 2 with
 * SAMPLE_NO_SHIFT, so the prefilter samples the same DoublePerlin noise at the same
 * coordinates: a parameter outside the envelope proves the cell cannot be the target.
 * Parameter quantization mirrors sampleBiomeNoise exactly
 * ((int64_t)(10000.0F * (float) v)) so a cell sitting on an envelope edge cannot be
 * wrongly rejected. Rejected cells are reported as -1; every other cell carries its
 * y=0 biome id unless the surface-slice re-test (master_cfxSurfaceSliceIsTarget) promotes it
 * to the target. Combinations without a parameter table (Nether scale 4, End scale
 * 4/16, unlisted Overworld ids) fall back to the plain per-cell query.
 */
/* JNI function stripped */

/* JNI function stripped */

/* JNI function stripped */

/* --- 1.18+ overview terrain height ---------------------------------------
 *
 * cubiomes builds a surface by sampling a full density column and searching it
 * downwards, which costs ~49 3D noise evaluations per 4-block cell - far too
 * much for a whole tile (measured 37s for one LOD4 tile). The overview instead
 * inverts the density function analytically.
 *
 * Vanilla's pre-cave density along a column is
 *     depth(y)        = 1 - y/128 + offset + jagged
 *     initialDensity  = depth*factor, quadrupled where depth > 0
 *     slopedCheese    = initialDensity + terrainNoise3D(x,y,z)
 * so the surface (slopedCheese = 0) satisfies
 *     y = 128 * (1 + offset + jagged + N/factor)
 * with N the 3D noise and the 4x branch applying below the crossing. N depends
 * on y, so this iterates to a fixed point; two rounds converge (a third changed
 * nothing measurably) and the whole thing costs 1-2 noise samples per cell.
 *
 * The previous implementation returned the offset spline's zero-crossing alone
 * (NP_DEPTH at quart Y=0), i.e. the surface you would get if the 3D noise were
 * identically zero. That reproduces the macro shape but none of the relief, and
 * left the sparse exact anchors to correct residuals they were far too coarse
 * to reach: measured against real generation, terrain gradient correlation was
 * 0.80 at LOD4 and as low as 0.39 in places, and mean height error 3.1 blocks.
 * Restoring the noise term takes those to 0.86 and 1.4 blocks.
 *
 * sampleTerrainParameters, sampleModernTerrainNoise, peaksAndValleys and
 * maintainPrecisionModern are static inside cubiomes' biomenoise.c, so the
 * three helpers below re-derive them from public primitives (samplePerlin,
 * sampleDoublePerlin, getSpline) and the public BiomeNoise/SurfaceNoise
 * layout - the same approach master_cfxLegacyOverviewHeight already takes for the
 * pre-1.18 terrain model. overviewHeightsTrackExactGeneration in
 * PredictionNativeIntegrationTest pins the result against cubiomes' own exact
 * column generation, so a submodule bump that changes the noise definitions
 * fails loudly instead of drifting.
 */
static double master_cfxMaintainPrecision(double value)
{
    return value - floor(value / 33554432.0 + 0.5) * 33554432.0;
}

static float master_cfxPeaksAndValleys(float weirdness)
{
    return -(fabsf(fabsf(weirdness) - 0.6666667F) - 0.33333334F) * 3.0F;
}

static void master_cfxTerrainParameters(const BiomeNoise *bn, int cellX, int cellZ, float values[4])
{
    double px = cellX;
    double pz = cellZ;
    px += sampleDoublePerlin(&bn->climate[NP_SHIFT], cellX, 0, cellZ) * 4.0;
    pz += sampleDoublePerlin(&bn->climate[NP_SHIFT], cellZ, cellX, 0) * 4.0;
    const float weirdness = sampleDoublePerlin(&bn->climate[NP_WEIRDNESS], px, 0, pz);
    values[SP_CONTINENTALNESS] = sampleDoublePerlin(&bn->climate[NP_CONTINENTALNESS], px, 0, pz);
    values[SP_EROSION] = sampleDoublePerlin(&bn->climate[NP_EROSION], px, 0, pz);
    values[SP_RIDGES] = master_cfxPeaksAndValleys(weirdness);
    values[SP_WEIRDNESS] = weirdness;
}

/*
 * Octave truncation for the coarse LOD lattices. With the caller's sampling
 * stride known, terrain-noise octaves whose wavelength is shorter than that
 * stride cannot show up in the output grid except as aliasing, so the coarse
 * entry point skips them outright: per octave i the wavelength is
 * 2^i / (684.412*xzScale) blocks for octmin/octmax and
 * 2^i / (684.412*xzScale/xzFactor) for octmain, whose finer octaves only steer
 * the octmin/octmax blend and are therefore held to a quarter of the stride. The
 * jaggedness term is skipped where the spline output is exactly zero (83% of
 * solves - the jagged spline vanishes outside peak terrain, and the product
 * with a zero multiplier is zero anyway) and otherwise sampled over its first
 * four octaves, which carry ~94% of its energy (per-octave amplitude halves
 * with xOctaveInit's persistence). The cut keeps one octave beyond the strict
 * stride Nyquist limit (octmin/octmax at wavelength >= stride/2, octmain at
 * >= stride/4): the strict cut measured 0.38% ocean-to-land flips on the
 * visual corpus against a 0.3% gate - one block of ripple moves real
 * shorelines - while this cut measures 0.15%. Truncation deliberately changes
 * results at strides >= 8 only; the per-pixel full-detail entry keeps LOD0-1
 * overviews, the waterline-band solves and the exact-residual anchors at
 * today's values bit-for-bit.
 */
typedef struct {
    int mainFirst;   /* first octmain octave evaluated */
    int minMaxFirst; /* first octmin/octmax octave evaluated */
    int truncated;
} MasterCfxNoiseDetail;

static const MasterCfxNoiseDetail MASTER_CFX_DETAIL_FULL = {0, 0, 0};

#define MASTER_CFX_DETAIL_MIN_STRIDE 8
#define MASTER_CFX_JAGGED_KEEP_OCTAVES 4

static MasterCfxNoiseDetail master_cfxNoiseDetailForStride(const SurfaceNoise *sn, int stride)
{
    MasterCfxNoiseDetail d = MASTER_CFX_DETAIL_FULL;
    if (stride < MASTER_CFX_DETAIL_MIN_STRIDE)
        return d;
    d.truncated = 1;
    const double scaledXz = 684.412 * sn->xzScale;
    double w = 1.0 / scaledXz;
    while (d.minMaxFirst < 16 && w < (double) stride * 0.5)
    {
        w *= 2.0;
        d.minMaxFirst++;
    }
    w = 1.0 / (scaledXz / sn->xzFactor);
    while (d.mainFirst < 8 && w < (double) stride * 0.25)
    {
        w *= 2.0;
        d.mainFirst++;
    }
    return d;
}

/* sampleDoublePerlin over only the first maxOct octaves (coarsest first). */
static double master_cfxSampleDoublePerlinPrefix(
    const DoublePerlinNoise *dp, double x, double y, double z, int maxOct
) {
    const double f = 337.0 / 331.0;
    double v = 0.0;
    int i;
    for (i = 0; i < maxOct && i < dp->octA.octcnt; i++)
    {
        const PerlinNoise *p = dp->octA.octaves + i;
        const double lf = p->lacunarity;
        v += p->amplitude * samplePerlin(
            p, maintainPrecision(x * lf), maintainPrecision(y * lf),
            maintainPrecision(z * lf), 0, 0);
    }
    for (i = 0; i < maxOct && i < dp->octB.octcnt; i++)
    {
        const PerlinNoise *p = dp->octB.octaves + i;
        const double lf = p->lacunarity;
        const double bx = x * f;
        const double by = y * f;
        const double bz = z * f;
        v += p->amplitude * samplePerlin(
            p, maintainPrecision(bx * lf), maintainPrecision(by * lf),
            maintainPrecision(bz * lf), 0, 0);
    }
    return v * dp->amplitude;
}

static double master_cfxModernTerrainNoise(
    const SurfaceNoise *sn, int x, int y, int z, const MasterCfxNoiseDetail *detail
)
{
    const double scaledXz = 684.412 * sn->xzScale;
    const double scaledY = 684.412 * sn->yScale;
    const double dx = x * scaledXz;
    const double dy = y * scaledY;
    const double dz = z * scaledXz;
    const double mainX = dx / sn->xzFactor;
    const double mainY = dy / sn->yFactor;
    const double mainZ = dz / sn->xzFactor;
    const double smearY = scaledY * 8.0;
    const double mainSmearY = smearY / sn->yFactor;
    double interpolation = 0.0;
    double frequency = ldexp(1.0, -detail->mainFirst);
    int i;

    for (i = detail->mainFirst; i < 8; i++)
    {
        interpolation += samplePerlin(&sn->octmain.octaves[i],
            master_cfxMaintainPrecision(mainX * frequency),
            master_cfxMaintainPrecision(mainY * frequency),
            master_cfxMaintainPrecision(mainZ * frequency),
            mainSmearY * frequency,
            mainY * frequency) / frequency;
        frequency *= 0.5;
    }

    const double blend = (interpolation / 10.0 + 1.0) * 0.5;
    double lower = 0.0;
    double upper = 0.0;
    frequency = ldexp(1.0, -detail->minMaxFirst);
    for (i = detail->minMaxFirst; i < 16; i++)
    {
        const double sx = master_cfxMaintainPrecision(dx * frequency);
        const double sy = master_cfxMaintainPrecision(dy * frequency);
        const double sz = master_cfxMaintainPrecision(dz * frequency);
        const double yScale = smearY * frequency;
        if (blend < 1.0)
            lower += samplePerlin(&sn->octmin.octaves[i],
                sx, sy, sz, yScale, dy * frequency) / frequency;
        if (blend > 0.0)
            upper += samplePerlin(&sn->octmax.octaves[i],
                sx, sy, sz, yScale, dy * frequency) / frequency;
        frequency *= 0.5;
    }
    return clampedLerp(blend, lower / 512.0, upper / 512.0) / 128.0;
}

/** Fixed-point rounds solving slopedCheese(y) = 0; 2 converges, 3 adds nothing. */
#define MASTER_CFX_OVERVIEW_ITERATIONS 2

static int master_cfxModernOverviewHeight(
    const MasterCfxContext *ctx, int x4, int z4, const MasterCfxNoiseDetail *detail
)
{
    const BiomeNoise *bn = &ctx->g.bn;
    float values[4];
    master_cfxTerrainParameters(bn, x4, z4, values);
    const double offset = -0.50375F + getSpline(bn->sp, values);
    const double factor = getSpline(bn->factorSp, values);
    const double jaggedness = getSpline(bn->jaggedSp, values);
    double jagged = 0.0;
    if (!detail->truncated)
    {
        jagged = sampleDoublePerlin(&bn->jagged, x4 * 4 * 1500.0, 0.0, z4 * 4 * 1500.0);
        if (jagged < 0.0)
            jagged *= 0.5;
        jagged *= jaggedness;
    }
    else if (jaggedness != 0.0F)
    {
        jagged = master_cfxSampleDoublePerlinPrefix(
            &bn->jagged, x4 * 4 * 1500.0, 0.0, z4 * 4 * 1500.0,
            MASTER_CFX_JAGGED_KEEP_OCTAVES
        );
        if (jagged < 0.0)
            jagged *= 0.5;
        jagged *= jaggedness;
    }

    /* Zero-crossing of the initial density, i.e. the old NP_DEPTH answer. */
    const double base = 1.0 + offset + jagged;
    double y = 128.0 * base;
    if (factor <= 1e-9)
        return (int) floor(y);

    int i;
    for (i = 0; i < MASTER_CFX_OVERVIEW_ITERATIONS; i++)
    {
        const double noise = master_cfxModernTerrainNoise(
            &bn->terrain, x4 * 4, (int) floor(y), z4 * 4, detail
        );
        /* Above the crossing the density slope is factor; below it, 4*factor. */
        const double above = 128.0 * (base + noise / factor);
        const double below = 128.0 * (base + noise / (4.0 * factor));
        const double next = above >= 128.0 * base ? above : below;
        if (fabs(next - y) < 0.5)
        {
            y = next;
            break;
        }
        y = next;
    }
    return (int) floor(y);
}

static int master_cfxLegacyOverviewHeight(
    const MasterCfxContext *ctx, int biome, int blockX, int blockZ
) {
    double biomeDepth, biomeScale;
    getBiomeDepthAndScale(biome, &biomeDepth, &biomeScale, NULL);
    (void) biomeScale;
    const double blendedDepth = ((biomeDepth * 4.0 - 1.0) / 8.0)
        * (17.0 / 64.0);
    const double quartX = blockX / 4.0;
    const double quartZ = blockZ / 4.0;
    double offset = sampleOctaveAmp(
        &ctx->sn.octdepth, quartX * 200.0, 10.0, quartZ * 200.0,
        1.0, 0.0, 1.0
    );
    offset *= 65535.0 / 8000.0;
    if (offset < 0.0)
        offset *= -0.3;
    offset = offset * 3.0 - 2.0;
    if (offset > 1.0)
        offset = 1.0;
    offset *= 17.0 / 64.0;
    const double densityOffset = offset < 0.0
        ? offset / 28.0
        : offset / 40.0;
    return (int) floor(128.0 * (0.53125 + densityOffset + blendedDepth));
}

static jint master_cfxOverviewHeightsImpl(
    JNIEnv *env, MasterCfxContext *ctx,
    jint blockX, jint blockZ, jint w, jint h, jint stride,
    jintArray outTerrainY, const MasterCfxNoiseDetail *detail
) {
    if (ctx->dim != DIM_OVERWORLD)
        return MASTER_CFX_ERR_WRONG_DIM;
    if (!master_cfxValidCells(w, h) || stride <= 0)
        return MASTER_CFX_ERR_BAD_SIZE;
    const int64_t lastX = (int64_t)blockX + (int64_t)(w-1)*stride;
    const int64_t lastZ = (int64_t)blockZ + (int64_t)(h-1)*stride;
    if (lastX < INT32_MIN || lastX > INT32_MAX
        || lastZ < INT32_MIN || lastZ > INT32_MAX)
        return MASTER_CFX_ERR_BAD_ARGS;
    const int cells = w*h;
    if ((*env)->GetArrayLength(env, outTerrainY) < cells)
        return MASTER_CFX_ERR_BAD_SIZE;

    int *heights = malloc(sizeof(int) * (size_t)cells);
    if (heights == NULL)
        return MASTER_CFX_ERR_ALLOC;

    if (ctx->mc >= MC_1_18)
    {
        if (stride < 4)
        {
            const int minX4 = floordiv(blockX, 4);
            const int minZ4 = floordiv(blockZ, 4);
            const int maxX4 = floordiv((int)lastX, 4) + 1;
            const int maxZ4 = floordiv((int)lastZ, 4) + 1;
            const int gridW = maxX4 - minX4 + 1;
            const int gridH = maxZ4 - minZ4 + 1;
            int *macro = malloc(sizeof(int) * (size_t)gridW * gridH);
            if (macro == NULL)
            {
                free(heights);
                return MASTER_CFX_ERR_ALLOC;
            }
            int j, i;
            for (j = 0; j < gridH; j++)
                for (i = 0; i < gridW; i++)
                    macro[j*gridW+i] = master_cfxModernOverviewHeight(
                        ctx, minX4+i, minZ4+j, detail
                    );
            for (j = 0; j < h; j++)
            {
                const int sampleZ = (int)((int64_t)blockZ + (int64_t)j*stride);
                const int z4 = floordiv(sampleZ, 4);
                const int fz = sampleZ - z4*4;
                const int iz = z4 - minZ4;
                for (i = 0; i < w; i++)
                {
                    const int sampleX = (int)((int64_t)blockX + (int64_t)i*stride);
                    const int x4 = floordiv(sampleX, 4);
                    const int fx = sampleX - x4*4;
                    const int ix = x4 - minX4;
                    const int top = macro[iz*gridW+ix]
                        + floordiv((macro[iz*gridW+ix+1] - macro[iz*gridW+ix])*fx, 4);
                    const int bottom = macro[(iz+1)*gridW+ix]
                        + floordiv((macro[(iz+1)*gridW+ix+1] - macro[(iz+1)*gridW+ix])*fx, 4);
                    heights[j*w+i] = top + floordiv((bottom-top)*fz, 4);
                }
            }
            free(macro);
        }
        else
        {
            int j, i;
            for (j = 0; j < h; j++)
            {
                const int sampleZ = (int)((int64_t)blockZ + (int64_t)j*stride);
                for (i = 0; i < w; i++)
                {
                    const int sampleX = (int)((int64_t)blockX + (int64_t)i*stride);
                    heights[j*w+i] = master_cfxModernOverviewHeight(
                        ctx, floordiv(sampleX, 4), floordiv(sampleZ, 4), detail);
                }
            }
        }
    }
    else
    {
        int *biomes = malloc(sizeof(int) * (size_t)cells);
        if (biomes == NULL)
        {
            free(heights);
            return MASTER_CFX_ERR_ALLOC;
        }
        const int err = master_cfxGenerateOverviewBiomes(
            ctx, blockX, blockZ, w, h, stride, biomes
        );
        if (err != MASTER_CFX_OK)
        {
            free(biomes);
            free(heights);
            return err;
        }
        int j, i;
        for (j = 0; j < h; j++)
        {
            const int sampleZ = (int)((int64_t)blockZ + (int64_t)j*stride);
            for (i = 0; i < w; i++)
            {
                const int sampleX = (int)((int64_t)blockX + (int64_t)i*stride);
                heights[j*w+i] = master_cfxLegacyOverviewHeight(
                    ctx, biomes[j*w+i], sampleX, sampleZ
                );
            }
        }
        free(biomes);
    }

    (*env)->SetIntArrayRegion(env, outTerrainY, 0, cells, heights);
    free(heights);
    return MASTER_CFX_OK;
}

/* JNI function stripped */

/*
 * Coarse-lattice variant of master_cfxOverviewHeights for the LOD2-4 anchor sweeps:
 * same geometry and validation, but terrain-noise octaves shorter than the
 * sampling stride are skipped (see MasterCfxNoiseDetail). Waterline-band and
 * exact-residual callers must keep the full-detail entry above.
 */
/* JNI function stripped */

/*
 * Per-call memo for master_cfxSurfaceBiomes: the biome of a voronoi nucleus is a pure
 * function of (nucleus, generator state), and fine sampling strides revisit the
 * same nucleus constantly - measured 10.6x unique-sample reuse at block stride,
 * 2.9x at stride 2, 1.16-1.17x at the coarse strides. Each call gets one open-
 * addressing table sized to twice its cells (load factor <= 0.5); entries store
 * biome+1 so 0 marks an empty slot and biome id 0 (ocean) stays representable.
 * A hit returns exactly what sampleBiomeNoise returned for the same nucleus,
 * so results are bit-identical to the unmemoized loop; allocation failure or a
 * table beyond MASTER_CFX_NUCLEUS_MEMO_MAX_ENTRIES simply declines and the caller runs
 * its plain loop. Lives in its own function so the plain sweep below keeps its
 * exact original shape; strides past MASTER_CFX_NUCLEUS_MEMO_MAX_STRIDE never reach it
 * because their nucleus reuse cannot pay for the table.
 */
typedef struct {
    int32_t x4, y4, z4;
    int32_t biomePlusOne;
} MasterCfxNucleusEntry;

#define MASTER_CFX_NUCLEUS_MEMO_MIN_CELLS 64
#define MASTER_CFX_NUCLEUS_MEMO_MAX_ENTRIES (1u << 20)
#define MASTER_CFX_NUCLEUS_MEMO_MAX_STRIDE 2

static uint32_t master_cfxNucleusHash(int32_t x4, int32_t y4, int32_t z4)
{
    uint64_t h = (uint32_t) x4 * UINT64_C(0x9E3779B97F4A7C15)
        ^ (uint32_t) y4 * UINT64_C(0xC2B2AE3D27D4EB4F)
        ^ (uint32_t) z4 * UINT64_C(0x85EBCA77C2B2AE63);
    h ^= h >> 33;
    h *= UINT64_C(0xFF51AFD7ED558CCD);
    h ^= h >> 29;
    return (uint32_t) (h >> 32);
}

/* Returns 1 when the batch was filled, 0 when the caller should run unmemoized. */
static int master_cfxSurfaceBiomesMemoized(
    const MasterCfxContext *ctx, int blockX, int blockZ, int w, int h, int stride,
    const int *terrainY, int *biomes, int cells)
{
    if (stride > MASTER_CFX_NUCLEUS_MEMO_MAX_STRIDE || cells < MASTER_CFX_NUCLEUS_MEMO_MIN_CELLS)
        return 0;
    uint32_t size = 64;
    while (size < (uint32_t) cells * 2u && size < MASTER_CFX_NUCLEUS_MEMO_MAX_ENTRIES)
        size <<= 1;
    if (size < (uint32_t) cells * 2u)
        return 0;
    MasterCfxNucleusEntry *memo = calloc(size, sizeof(MasterCfxNucleusEntry));
    if (memo == NULL)
        return 0;
    const uint32_t mask = size - 1;
    int j, i;
    for (j = 0; j < h; j++)
    {
        const int sampleZ = (int) ((int64_t) blockZ + (int64_t) j * stride);
        for (i = 0; i < w; i++)
        {
            const int index = j * w + i;
            const int sampleX = (int) ((int64_t) blockX + (int64_t) i * stride);
            const int surface = terrainY[index] < 62 ? 62 : terrainY[index];
            int x4, y4, z4;
            voronoiAccess3D(ctx->g.sha, sampleX, surface, sampleZ, &x4, &y4, &z4);
            uint32_t slot = master_cfxNucleusHash(
                (int32_t) x4, (int32_t) y4, (int32_t) z4) & mask;
            while (memo[slot].biomePlusOne != 0)
            {
                if (memo[slot].x4 == (int32_t) x4
                    && memo[slot].y4 == (int32_t) y4
                    && memo[slot].z4 == (int32_t) z4)
                    break;
                slot = (slot + 1) & mask;
            }
            if (memo[slot].biomePlusOne != 0)
            {
                biomes[index] = memo[slot].biomePlusOne - 1;
                continue;
            }
            biomes[index] = sampleBiomeNoise(&ctx->g.bn, NULL, x4, y4, z4, NULL, 0);
            memo[slot].x4 = (int32_t) x4;
            memo[slot].y4 = (int32_t) y4;
            memo[slot].z4 = (int32_t) z4;
            memo[slot].biomePlusOne = (int32_t) biomes[index] + 1;
        }
    }
    free(memo);
    return 1;
}

/* JNI function stripped */

/* JNI function stripped */

/* JNI function stripped */

/* JNI function stripped */

/* JNI function stripped */

/* JNI function stripped */

static int master_cfxIsViableStructure(
    MasterCfxContext *ctx,
    int structType,
    int blockX,
    int blockZ
) {
    if (structType == Nether_Fossil && ctx->mc >= MC_1_17) {
        if (!ctx->netherNoiseReady) {
            initBlendedNoise(&ctx->netherNoise, ctx->g.seed, DIM_NETHER);
            ctx->netherNoiseReady = 1;
        }
        return isViableNetherFossilPos(&ctx->g, &ctx->netherNoise, blockX, blockZ);
    }
    if (!isViableStructurePos(structType, &ctx->g, blockX, blockZ, 0)) {
        return 0;
    }
    if (structType == End_City) {
        return isViableEndCityTerrain(&ctx->g, &ctx->sn, blockX, blockZ);
    }
    return 1;
}

enum {
    MASTER_CFX_VARIANT_END_CITY_SHIP = 1,
    MASTER_CFX_VARIANT_IGLOO_BASEMENT = 1,
    MASTER_CFX_VARIANT_SHIPWRECK_BEACHED = 1,
    MASTER_CFX_VARIANT_VILLAGE_ZOMBIE = 1 << 3,
    MASTER_CFX_VARIANT_RUINED_PORTAL_GIANT = 1
};

static int master_cfxStructureBiome(MasterCfxContext *ctx, int structType, int blockX, int blockZ) {
    const int chunkX = blockX >> 4;
    const int chunkZ = blockZ >> 4;
    if (ctx->mc >= MC_1_18 && structType == Village) {
        const int variants[] = {plains, desert, savanna, taiga, snowy_tundra};
        for (size_t index = 0; index < sizeof(variants) / sizeof(variants[0]); index++) {
            StructureVariant variant;
            getVariant(
                &variant, Village, ctx->mc, ctx->g.seed,
                blockX, blockZ, variants[index]
            );
            const int sampleX = (chunkX * 32 + 2 * variant.x + variant.sx - 1) / 2 >> 2;
            const int sampleZ = (chunkZ * 32 + 2 * variant.z + variant.sz - 1) / 2 >> 2;
            const int biome = getBiomeAt(&ctx->g, 0, sampleX, 319 >> 2, sampleZ);
            if (biome == variants[index] || (biome == meadow && variants[index] == plains)) {
                return variants[index];
            }
        }
        return -1;
    }
    return getBiomeAt(
        &ctx->g, 4, chunkX * 4 + 2,
        ctx->dim == DIM_NETHER ? 32 >> 2 : 80 >> 2,
        chunkZ * 4 + 2
    );
}

static int master_cfxStructureVariant(MasterCfxContext *ctx, int structType, int blockX, int blockZ) {
    if (structType == End_City) {
        Piece pieces[END_CITY_PIECES_MAX];
        const int count = getEndCityPieces(
            pieces, ctx->g.seed, blockX >> 4, blockZ >> 4
        );
        for (int index = 0; index < count; index++) {
            if (pieces[index].type == END_SHIP) {
                return MASTER_CFX_VARIANT_END_CITY_SHIP;
            }
        }
        return 0;
    }

    int biome = -1;
    if (structType == Village || structType == Shipwreck
        || structType == Ruined_Portal || structType == Ruined_Portal_N) {
        biome = master_cfxStructureBiome(ctx, structType, blockX, blockZ);
    }
    StructureVariant variant;
    if (!getVariant(
        &variant, structType, ctx->mc, ctx->g.seed, blockX, blockZ, biome
    )) {
        return 0;
    }
    switch (structType) {
    case Village: {
        int type = 0;
        switch (variant.biome) {
        case desert: type = 1; break;
        case savanna: type = 2; break;
        case taiga: type = 3; break;
        case snowy_tundra: type = 4; break;
        default: break;
        }
        return type | (variant.abandoned ? MASTER_CFX_VARIANT_VILLAGE_ZOMBIE : 0);
    }
    case Igloo:
        return variant.basement ? MASTER_CFX_VARIANT_IGLOO_BASEMENT : 0;
    case Shipwreck:
        return isOceanic(variant.biome) ? 0 : MASTER_CFX_VARIANT_SHIPWRECK_BEACHED;
    case Bastion:
        return variant.start & 3;
    case Ruined_Portal:
    case Ruined_Portal_N:
        return variant.giant ? MASTER_CFX_VARIANT_RUINED_PORTAL_GIANT : 0;
    default:
        return 0;
    }
}

/* JNI function stripped */

/* JNI function stripped */

static void master_cfxTryNearestStructure(
    MasterCfxContext *ctx,
    int structType,
    int regX,
    int regZ,
    int blockX,
    int blockZ,
    int64_t maxDistanceSquared,
    int64_t *bestDistanceSquared,
    Pos *best,
    int *found
) {
    Pos pos;
    if (!getStructurePos(structType, ctx->mc, ctx->g.seed, regX, regZ, &pos)
        || !master_cfxIsViableStructure(ctx, structType, pos.x, pos.z)) {
        return;
    }
    const int64_t dx = (int64_t) pos.x - blockX;
    const int64_t dz = (int64_t) pos.z - blockZ;
    const int64_t distanceSquared = dx * dx + dz * dz;
    if (distanceSquared <= maxDistanceSquared
        && (!*found || distanceSquared < *bestDistanceSquared)) {
        *bestDistanceSquared = distanceSquared;
        *best = pos;
        *found = 1;
    }
}

/* JNI function stripped */

/* JNI function stripped */

/*
 * CPU topology queries, the two exports here that have nothing to do with
 * cubiomes: the Java worker-pool sizing needs an explicit P/E-core labelling,
 * and this library is the only native code the mod already ships and loads.
 * Both exports exist on every target, but only ever report data where the OS
 * API exists - Windows reads GetLogicalProcessorInformationEx efficiency
 * classes, Linux x86_64 reads CPUID's hybrid core-type leaf - so any 0 return
 * or UnsatisfiedLinkError just means "no data" and Java falls back (sysfs
 * frequency grouping on Linux, a uniform CPU everywhere else).
 */
#ifdef _WIN32
#include <windows.h>

static int master_cfxWindowsEfficiencyClasses(JNIEnv *env, jintArray out) {
    DWORD size = 0;
    /* Sizing call: succeeds only when there is nothing to ask for. */
    if (!GetLogicalProcessorInformationEx(RelationProcessorCore, NULL, &size)
        || GetLastError() != ERROR_INSUFFICIENT_BUFFER || size == 0) {
        return 0;
    }
    SYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX *buffer = malloc(size);
    if (buffer == NULL) {
        return 0;
    }
    if (!GetLogicalProcessorInformationEx(RelationProcessorCore, buffer, &size)) {
        free(buffer);
        return 0;
    }
    DWORD offset = 0;
    int total = 0;
    while (offset < size) {
        SYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX *entry =
            (SYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX *) ((char *) buffer + offset);
        if (entry->Relationship == RelationProcessorCore) {
            for (WORD group = 0; group < entry->Processor.GroupCount; group++) {
                KAFFINITY mask = entry->Processor.GroupMask[group].Mask;
                while (mask != 0) {
                    if (mask & 1) {
                        total++;
                    }
                    mask >>= 1;
                }
            }
        }
        offset += entry->Size;
    }
    if (out == NULL || (*env)->GetArrayLength(env, out) < total) {
        free(buffer);
        return out == NULL ? total : 0;
    }
    jint *classes = malloc((size_t) total * sizeof(jint));
    if (classes == NULL) {
        free(buffer);
        return 0;
    }
    offset = 0;
    int written = 0;
    while (offset < size) {
        SYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX *entry =
            (SYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX *) ((char *) buffer + offset);
        if (entry->Relationship == RelationProcessorCore) {
            for (WORD group = 0; group < entry->Processor.GroupCount; group++) {
                KAFFINITY mask = entry->Processor.GroupMask[group].Mask;
                while (mask != 0) {
                    if (mask & 1) {
                        classes[written++] = (jint) entry->Processor.EfficiencyClass;
                    }
                    mask >>= 1;
                }
            }
        }
        offset += entry->Size;
    }
    (*env)->SetIntArrayRegion(env, out, 0, total, classes);
    free(classes);
    free(buffer);
    return written;
}

/* JNI function stripped */

#else /* !_WIN32 */

/* JNI function stripped */

#endif /* _WIN32 */

/*
 * Linux x86_64 hybrid core types, read straight from the CPU: CPUID leaf 0x1A
 * labels each logical processor 0x40 (Core) or 0x20 (Atom), but only once leaf
 * 7 subleaf 0's hybrid bit (EDX bit 15) says the part is hybrid at all. The
 * shim walks the calling thread's allowed CPUs one by one, pinning the thread
 * to each to read its per-CPU leaf, and puts the thread's original affinity
 * mask back before returning - success or failure. Java prefers this explicit
 * vendor label over grouping sysfs cpuinfo_max_freq, because it needs no
 * cpufreq driver to expose that node and merges differently-binned P cores
 * into one tier by definition.
 */
#if defined(__linux__) && defined(__x86_64__)

#include <sched.h>

static void master_cfxCpuid(unsigned int leaf, unsigned int subleaf, unsigned int out[4]) {
    __asm__ volatile ("cpuid"
        : "=a"(out[0]), "=b"(out[1]), "=c"(out[2]), "=d"(out[3])
        : "a"(leaf), "c"(subleaf));
}

static int master_cfxLinuxHybridCoreTypes(JNIEnv *env, jintArray out) {
    unsigned int info[4];
    master_cfxCpuid(0, 0, info);
    if (info[0] < 0x1A) {
        return 0;
    }
    master_cfxCpuid(7, 0, info);
    if ((info[3] & (1u << 15)) == 0) {
        return 0;
    }
    cpu_set_t original;
    CPU_ZERO(&original);
    if (sched_getaffinity(0, sizeof(original), &original) != 0) {
        return 0;
    }
    const int total = CPU_COUNT(&original);
    if (total <= 0) {
        return 0;
    }
    if (out == NULL || (*env)->GetArrayLength(env, out) < total) {
        return out == NULL ? total : 0;
    }
    jint *types = malloc((size_t) total * sizeof(jint));
    if (types == NULL) {
        return 0;
    }
    int written = 0;
    int failed = 0;
    cpu_set_t pinned;
    for (int cpu = 0; cpu < CPU_SETSIZE && written < total; cpu++) {
        if (!CPU_ISSET(cpu, &original)) {
            continue;
        }
        CPU_ZERO(&pinned);
        CPU_SET(cpu, &pinned);
        if (sched_setaffinity(0, sizeof(pinned), &pinned) != 0) {
            failed = 1;
            break;
        }
        master_cfxCpuid(0x1A, 0, info);
        types[written++] = (jint) ((info[0] >> 24) & 0xFFu);
    }
    if (sched_setaffinity(0, sizeof(original), &original) != 0) {
        failed = 1;
    }
    if (failed || written != total) {
        free(types);
        return 0;
    }
    (*env)->SetIntArrayRegion(env, out, 0, total, types);
    free(types);
    return written;
}

/* JNI function stripped */

#else /* !(__linux__ && __x86_64__) */

/* JNI function stripped */

#endif /* __linux__ && __x86_64__ */
