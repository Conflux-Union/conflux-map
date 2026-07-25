/*
 * confluxnative - flat handle-based JNI shim around vendored cubiomes.
 *
 * This file is Conflux Map's own code (GPL-3.0), NOT part of the vendored
 * cubiomes sources in native/cubiomes/ (MIT, see native/CUBIOMES_COMMIT).
 * It only ever includes cubiomes' public headers and calls its public API;
 * no cubiomes internals are duplicated here.
 *
 * Every exported function is batch-only and takes a "handle" - an opaque
 * pointer to a malloc'd CfxContext, created by cfxCreate and freed by
 * cfxDestroy. A context is NOT thread-safe to share concurrently: cubiomes'
 * Generator/SurfaceNoise structs hold no global state (confirmed by
 * inspection - every mutable field lives inside the struct itself), so two
 * contexts on two threads never interfere with each other, but one context
 * must only ever be driven by one thread at a time. The Java side enforces
 * this by construction (CubiomesContext is documented thread-confined).
 *
 * Status codes returned by the query functions (0 is always success):
 *   0  CFX_OK
 *   1  CFX_ERR_BAD_HANDLE    - handle was 0/NULL
 *   2  CFX_ERR_BAD_SIZE      - w/h/cap out of range (<=0 or over the cap)
 *   3  CFX_ERR_BAD_ARGS      - other invalid argument (e.g. unsupported scale)
 *   4  CFX_ERR_ALLOC         - native allocation failed
 *   5  CFX_ERR_GENERATION    - cubiomes reported a generation failure
 *   6  CFX_ERR_WRONG_DIM     - handle's dimension doesn't support this query
 *   7  CFX_ERR_FEATURE_PARTIAL - requested feature coverage is unavailable
 */

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <math.h>

#include "finders.h"
#include "terrain_features.h"

#define CFX_ABI 6

#define CFX_OK              0
#define CFX_ERR_BAD_HANDLE  1
#define CFX_ERR_BAD_SIZE    2
#define CFX_ERR_BAD_ARGS    3
#define CFX_ERR_ALLOC       4
#define CFX_ERR_GENERATION  5
#define CFX_ERR_WRONG_DIM   6
#define CFX_ERR_FEATURE_PARTIAL 7

/* Largest w*h (or region count) accepted by any batch query. */
#define CFX_MAX_CELLS (1 << 20)

typedef struct {
    Generator g;
    SurfaceNoise sn; /* Overworld terrain columns and End surface heights. */
    int mc;
    int dim;
} CfxContext;

static CfxContext *cfxHandle(jlong handle) {
    return (CfxContext *) (intptr_t) handle;
}

static int cfxValidCells(jint w, jint h) {
    if (w <= 0 || h <= 0) {
        return 0;
    }
    return (int64_t) w * (int64_t) h <= CFX_MAX_CELLS;
}

static int cfxValidScale(jint scale) {
    return scale == 1 || scale == 4 || scale == 16 || scale == 64 || scale == 256;
}

static int cfxGenerateBiomes(CfxContext *ctx, int *out, Range r) {
    if (ctx->dim == DIM_OVERWORLD && ctx->mc >= MC_1_18
        && (r.scale == 1 || r.scale == 4)) {
        return mapOverworldSurfaceBiome(
            out, &ctx->g.bn, ctx->g.sha, r.scale, r.x, r.z, r.sx, r.sz
        );
    }
    return genBiomes(&ctx->g, out, r);
}

static int cfxGenerateBiomesStrided(
    CfxContext *ctx, int scale, int x, int z, int w, int h, int stride, int *sampled
) {
    const int64_t rawWidth64 = (int64_t) (w - 1) * stride + 1;
    const int64_t rawHeight64 = (int64_t) (h - 1) * stride + 1;
    if (rawWidth64 <= 0 || rawWidth64 > CFX_MAX_CELLS
        || rawHeight64 <= 0 || rawHeight64 > CFX_MAX_CELLS)
        return CFX_ERR_BAD_SIZE;
    const int rawWidth = (int) rawWidth64;

    if (scale == 1)
    {
        /* The final Voronoi layer is not query-shape invariant for one-row
         * rectangles, so scale-one sampling must retain the full 2D source. */
        if (rawWidth64 * rawHeight64 > CFX_MAX_CELLS)
            return CFX_ERR_BAD_SIZE;
        const int rawHeight = (int) rawHeight64;
        const Range r = { scale, x, z, rawWidth, rawHeight, 0, 1 };
        int *dense = allocCache(&ctx->g, r);
        if (dense == NULL)
            return CFX_ERR_ALLOC;
        const int err = cfxGenerateBiomes(ctx, dense, r);
        if (err != 0)
        {
            free(dense);
            return CFX_ERR_GENERATION;
        }
        int j, i;
        for (j = 0; j < h; j++)
            for (i = 0; i < w; i++)
                sampled[j*w+i] = dense[(j*stride)*rawWidth + i*stride];
        free(dense);
        return CFX_OK;
    }

    int j;
    for (j = 0; j < h; j++)
    {
        const Range r = { scale, x, z + j*stride, rawWidth, 1, 0, 1 };
        int *row = allocCache(&ctx->g, r);
        if (row == NULL)
            return CFX_ERR_ALLOC;
        const int err = cfxGenerateBiomes(ctx, row, r);
        if (err != 0)
        {
            free(row);
            return CFX_ERR_GENERATION;
        }
        int i;
        for (i = 0; i < w; i++)
            sampled[j*w+i] = row[i*stride];
        free(row);
    }
    return CFX_OK;
}

static int cfxGenerateOverviewBiomes(
    CfxContext *ctx, int blockX, int blockZ, int w, int h, int blockStride, int *out
) {
    if (blockStride < 4)
        return cfxGenerateBiomesStrided(
            ctx, 1, blockX, blockZ, w, h, blockStride, out
        );
    if (blockStride % 4 != 0)
        return CFX_ERR_BAD_ARGS;
    return cfxGenerateBiomesStrided(
        ctx, 4, floordiv(blockX, 4), floordiv(blockZ, 4),
        w, h, blockStride / 4, out
    );
}

JNIEXPORT jint JNICALL Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxAbi(
    JNIEnv *env, jclass clazz
) {
    (void) env;
    (void) clazz;
    return CFX_ABI;
}

JNIEXPORT jlong JNICALL Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxCreate(
    JNIEnv *env, jclass clazz, jint mcVersion, jlong seed, jint dim, jint flags
) {
    (void) env;
    (void) clazz;
    if (mcVersion <= MC_UNDEF || mcVersion > MC_NEWEST) {
        return 0;
    }
    if (dim != DIM_OVERWORLD && dim != DIM_END) {
        /* Nether is out of scope for this milestone (no nether 3D density support here). */
        return 0;
    }

    CfxContext *ctx = calloc(1, sizeof(CfxContext));
    if (ctx == NULL) {
        return 0;
    }
    ctx->mc = (int) mcVersion;
    ctx->dim = (int) dim;
    setupGenerator(&ctx->g, ctx->mc, (uint32_t) flags);
    applySeed(&ctx->g, ctx->dim, (uint64_t) seed);
    initSurfaceNoise(&ctx->sn, ctx->dim, (uint64_t) seed);
    return (jlong) (intptr_t) ctx;
}

JNIEXPORT void JNICALL Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxDestroy(
    JNIEnv *env, jclass clazz, jlong handle
) {
    (void) env;
    (void) clazz;
    CfxContext *ctx = cfxHandle(handle);
    if (ctx != NULL) {
        free(ctx);
    }
}

JNIEXPORT jint JNICALL Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxBiomes(
    JNIEnv *env, jclass clazz, jlong handle, jint scale, jint x, jint z, jint w, jint h, jintArray out
) {
    (void) clazz;
    CfxContext *ctx = cfxHandle(handle);
    if (ctx == NULL) {
        return CFX_ERR_BAD_HANDLE;
    }
    if (!cfxValidCells(w, h)) {
        return CFX_ERR_BAD_SIZE;
    }
    if (!cfxValidScale(scale)) {
        return CFX_ERR_BAD_ARGS;
    }
    if ((*env)->GetArrayLength(env, out) < w * h) {
        return CFX_ERR_BAD_SIZE;
    }

    const Range r = { scale, x, z, w, h, 0, 1 };
    int *cache = allocCache(&ctx->g, r);
    if (cache == NULL) {
        return CFX_ERR_ALLOC;
    }
    const int err = cfxGenerateBiomes(ctx, cache, r);
    if (err != 0) {
        free(cache);
        return CFX_ERR_GENERATION;
    }

    (*env)->SetIntArrayRegion(env, out, 0, w * h, cache);
    free(cache);
    return CFX_OK;
}

JNIEXPORT jint JNICALL Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxBiomesStrided(
    JNIEnv *env, jclass clazz, jlong handle, jint scale, jint x, jint z, jint w, jint h, jint stride, jintArray out
) {
    (void) clazz;
    CfxContext *ctx = cfxHandle(handle);
    if (ctx == NULL) {
        return CFX_ERR_BAD_HANDLE;
    }
    if (!cfxValidCells(w, h) || stride <= 0) {
        return CFX_ERR_BAD_SIZE;
    }
    if (!cfxValidScale(scale)) {
        return CFX_ERR_BAD_ARGS;
    }
    const int64_t rawWidth64 = (int64_t) (w - 1) * stride + 1;
    const int64_t rawHeight64 = (int64_t) (h - 1) * stride + 1;
    if (rawWidth64 <= 0 || rawWidth64 > CFX_MAX_CELLS
        || rawHeight64 <= 0 || rawHeight64 > CFX_MAX_CELLS
        || (*env)->GetArrayLength(env, out) < w * h) {
        return CFX_ERR_BAD_SIZE;
    }
    int *sampled = malloc(sizeof(int) * (size_t) w * (size_t) h);
    if (sampled == NULL) {
        return CFX_ERR_ALLOC;
    }
    const int err = cfxGenerateBiomesStrided(
        ctx, scale, x, z, w, h, stride, sampled
    );
    if (err != CFX_OK) {
        free(sampled);
        return err;
    }

    (*env)->SetIntArrayRegion(env, out, 0, w * h, sampled);
    free(sampled);
    return CFX_OK;
}

JNIEXPORT jint JNICALL Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxHeights(
    JNIEnv *env, jclass clazz, jlong handle, jint x4, jint z4, jint w, jint h, jintArray outY, jintArray outIds
) {
    (void) clazz;
    CfxContext *ctx = cfxHandle(handle);
    if (ctx == NULL) {
        return CFX_ERR_BAD_HANDLE;
    }
    if (ctx->dim != DIM_OVERWORLD) {
        return CFX_ERR_WRONG_DIM;
    }
    if (!cfxValidCells(w, h)) {
        return CFX_ERR_BAD_SIZE;
    }
    if ((*env)->GetArrayLength(env, outY) < w * h || (*env)->GetArrayLength(env, outIds) < w * h) {
        return CFX_ERR_BAD_SIZE;
    }

    float *y = malloc(sizeof(float) * (size_t) w * (size_t) h);
    int *ids = malloc(sizeof(int) * (size_t) w * (size_t) h);
    if (y == NULL || ids == NULL) {
        free(y);
        free(ids);
        return CFX_ERR_ALLOC;
    }

    const int err = mapApproxHeight(y, ids, &ctx->g, &ctx->sn, x4, z4, w, h);
    if (err != 0) {
        free(y);
        free(ids);
        return CFX_ERR_GENERATION;
    }

    /* Single float->int floor spot for the whole predictor, per the determinism spec. */
    int *iy = malloc(sizeof(int) * (size_t) w * (size_t) h);
    if (iy == NULL) {
        free(y);
        free(ids);
        return CFX_ERR_ALLOC;
    }
    for (int i = 0; i < w * h; i++) {
        iy[i] = (int) floorf(y[i]);
    }

    (*env)->SetIntArrayRegion(env, outY, 0, w * h, iy);
    (*env)->SetIntArrayRegion(env, outIds, 0, w * h, ids);

    free(y);
    free(ids);
    free(iy);
    return CFX_OK;
}

JNIEXPORT jint JNICALL Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxSurfaceColumns(
    JNIEnv *env, jclass clazz, jlong handle,
    jint blockX, jint blockZ, jint w, jint h, jint stride,
    jintArray outSolidY, jintArray outFluidY, jintArray outSurfaceY, jintArray outFlags
) {
    (void) clazz;
    CfxContext *ctx = cfxHandle(handle);
    if (ctx == NULL) {
        return CFX_ERR_BAD_HANDLE;
    }
    if (ctx->dim != DIM_OVERWORLD) {
        return CFX_ERR_WRONG_DIM;
    }
    if (!cfxValidCells(w, h) || stride <= 0) {
        return CFX_ERR_BAD_SIZE;
    }
    const int64_t lastX = (int64_t) blockX + (int64_t) (w - 1) * stride;
    const int64_t lastZ = (int64_t) blockZ + (int64_t) (h - 1) * stride;
    if (lastX < INT32_MIN || lastX > INT32_MAX || lastZ < INT32_MIN || lastZ > INT32_MAX) {
        return CFX_ERR_BAD_ARGS;
    }
    const int cells = w * h;
    if ((*env)->GetArrayLength(env, outSolidY) < cells
        || (*env)->GetArrayLength(env, outFluidY) < cells
        || (*env)->GetArrayLength(env, outSurfaceY) < cells
        || (*env)->GetArrayLength(env, outFlags) < cells) {
        return CFX_ERR_BAD_SIZE;
    }

    int *solidY = malloc(sizeof(int) * (size_t) cells);
    int *fluidY = malloc(sizeof(int) * (size_t) cells);
    int *surfaceY = malloc(sizeof(int) * (size_t) cells);
    int *flags = malloc(sizeof(int) * (size_t) cells);
    if (solidY == NULL || fluidY == NULL || surfaceY == NULL || flags == NULL) {
        free(solidY);
        free(fluidY);
        free(surfaceY);
        free(flags);
        return CFX_ERR_ALLOC;
    }

    const int minCellX = floordiv(blockX, 4);
    const int minCellZ = floordiv(blockZ, 4);
    const int maxCellX = floordiv((int) lastX, 4) + 1;
    const int maxCellZ = floordiv((int) lastZ, 4) + 1;
    const int64_t denseColumns = (int64_t) (maxCellX - minCellX + 1)
        * (int64_t) (maxCellZ - minCellZ + 1);
    const int64_t sparseColumns = (int64_t) cells * 4;
    int err = 0;
    if (denseColumns > sparseColumns)
    {
        /* Coarse LOD anchors are deliberately sparse across a large world span. Letting
         * cubiomes materialize every intervening quart column defeats that sampling budget;
         * independent 1x1 queries need only the four columns surrounding each visible anchor. */
        int j, i;
        for (j = 0; j < h && err == 0; j++)
        {
            const int sampleZ = (int) ((int64_t) blockZ + (int64_t) j * stride);
            for (i = 0; i < w; i++)
            {
                const int sampleX = (int) ((int64_t) blockX + (int64_t) i * stride);
                const int index = j * w + i;
                err = mapOverworldSurfaceColumns(
                    solidY + index, fluidY + index, surfaceY + index, flags + index,
                    &ctx->g, &ctx->sn, sampleX, sampleZ, 1, 1, 1
                );
                if (err != 0)
                    break;
            }
        }
    }
    else
    {
        err = mapOverworldSurfaceColumns(
            solidY, fluidY, surfaceY, flags, &ctx->g, &ctx->sn,
            blockX, blockZ, w, h, stride
        );
    }
    if (err != 0) {
        free(solidY);
        free(fluidY);
        free(surfaceY);
        free(flags);
        return CFX_ERR_GENERATION;
    }

    (*env)->SetIntArrayRegion(env, outSolidY, 0, cells, solidY);
    (*env)->SetIntArrayRegion(env, outFluidY, 0, cells, fluidY);
    (*env)->SetIntArrayRegion(env, outSurfaceY, 0, cells, surfaceY);
    (*env)->SetIntArrayRegion(env, outFlags, 0, cells, flags);
    free(solidY);
    free(fluidY);
    free(surfaceY);
    free(flags);
    return CFX_OK;
}

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
 * layout - the same approach cfxLegacyOverviewHeight already takes for the
 * pre-1.18 terrain model. overviewHeightsTrackExactGeneration in
 * PredictionNativeIntegrationTest pins the result against cubiomes' own exact
 * column generation, so a submodule bump that changes the noise definitions
 * fails loudly instead of drifting.
 */
static double cfxMaintainPrecision(double value)
{
    return value - floor(value / 33554432.0 + 0.5) * 33554432.0;
}

static float cfxPeaksAndValleys(float weirdness)
{
    return -(fabsf(fabsf(weirdness) - 0.6666667F) - 0.33333334F) * 3.0F;
}

static void cfxTerrainParameters(const BiomeNoise *bn, int cellX, int cellZ, float values[4])
{
    double px = cellX;
    double pz = cellZ;
    px += sampleDoublePerlin(&bn->climate[NP_SHIFT], cellX, 0, cellZ) * 4.0;
    pz += sampleDoublePerlin(&bn->climate[NP_SHIFT], cellZ, cellX, 0) * 4.0;
    const float weirdness = sampleDoublePerlin(&bn->climate[NP_WEIRDNESS], px, 0, pz);
    values[SP_CONTINENTALNESS] = sampleDoublePerlin(&bn->climate[NP_CONTINENTALNESS], px, 0, pz);
    values[SP_EROSION] = sampleDoublePerlin(&bn->climate[NP_EROSION], px, 0, pz);
    values[SP_RIDGES] = cfxPeaksAndValleys(weirdness);
    values[SP_WEIRDNESS] = weirdness;
}

static double cfxModernTerrainNoise(const SurfaceNoise *sn, int x, int y, int z)
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
    double frequency = 1.0;
    int i;

    for (i = 0; i < 8; i++)
    {
        interpolation += samplePerlin(&sn->octmain.octaves[i],
            cfxMaintainPrecision(mainX * frequency),
            cfxMaintainPrecision(mainY * frequency),
            cfxMaintainPrecision(mainZ * frequency),
            mainSmearY * frequency,
            mainY * frequency) / frequency;
        frequency *= 0.5;
    }

    const double blend = (interpolation / 10.0 + 1.0) * 0.5;
    double lower = 0.0;
    double upper = 0.0;
    frequency = 1.0;
    for (i = 0; i < 16; i++)
    {
        const double sx = cfxMaintainPrecision(dx * frequency);
        const double sy = cfxMaintainPrecision(dy * frequency);
        const double sz = cfxMaintainPrecision(dz * frequency);
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
#define CFX_OVERVIEW_ITERATIONS 2

static int cfxModernOverviewHeight(const CfxContext *ctx, int x4, int z4)
{
    const BiomeNoise *bn = &ctx->g.bn;
    float values[4];
    cfxTerrainParameters(bn, x4, z4, values);
    const double offset = -0.50375F + getSpline(bn->sp, values);
    const double factor = getSpline(bn->factorSp, values);
    const double jaggedness = getSpline(bn->jaggedSp, values);
    double jagged = sampleDoublePerlin(&bn->jagged, x4 * 4 * 1500.0, 0.0, z4 * 4 * 1500.0);
    if (jagged < 0.0)
        jagged *= 0.5;
    jagged *= jaggedness;

    /* Zero-crossing of the initial density, i.e. the old NP_DEPTH answer. */
    const double base = 1.0 + offset + jagged;
    double y = 128.0 * base;
    if (factor <= 1e-9)
        return (int) floor(y);

    int i;
    for (i = 0; i < CFX_OVERVIEW_ITERATIONS; i++)
    {
        const double noise = cfxModernTerrainNoise(
            &bn->terrain, x4 * 4, (int) floor(y), z4 * 4
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

static int cfxLegacyOverviewHeight(
    const CfxContext *ctx, int biome, int blockX, int blockZ
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

JNIEXPORT jint JNICALL Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxOverviewHeights(
    JNIEnv *env, jclass clazz, jlong handle,
    jint blockX, jint blockZ, jint w, jint h, jint stride,
    jintArray outTerrainY
) {
    (void) clazz;
    CfxContext *ctx = cfxHandle(handle);
    if (ctx == NULL)
        return CFX_ERR_BAD_HANDLE;
    if (ctx->dim != DIM_OVERWORLD)
        return CFX_ERR_WRONG_DIM;
    if (!cfxValidCells(w, h) || stride <= 0)
        return CFX_ERR_BAD_SIZE;
    const int64_t lastX = (int64_t)blockX + (int64_t)(w-1)*stride;
    const int64_t lastZ = (int64_t)blockZ + (int64_t)(h-1)*stride;
    if (lastX < INT32_MIN || lastX > INT32_MAX
        || lastZ < INT32_MIN || lastZ > INT32_MAX)
        return CFX_ERR_BAD_ARGS;
    const int cells = w*h;
    if ((*env)->GetArrayLength(env, outTerrainY) < cells)
        return CFX_ERR_BAD_SIZE;

    int *heights = malloc(sizeof(int) * (size_t)cells);
    if (heights == NULL)
        return CFX_ERR_ALLOC;

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
                return CFX_ERR_ALLOC;
            }
            int j, i;
            for (j = 0; j < gridH; j++)
                for (i = 0; i < gridW; i++)
                    macro[j*gridW+i] = cfxModernOverviewHeight(
                        ctx, minX4+i, minZ4+j
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
                    heights[j*w+i] = cfxModernOverviewHeight(
                        ctx, floordiv(sampleX, 4), floordiv(sampleZ, 4)
                    );
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
            return CFX_ERR_ALLOC;
        }
        const int err = cfxGenerateOverviewBiomes(
            ctx, blockX, blockZ, w, h, stride, biomes
        );
        if (err != CFX_OK)
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
                heights[j*w+i] = cfxLegacyOverviewHeight(
                    ctx, biomes[j*w+i], sampleX, sampleZ
                );
            }
        }
        free(biomes);
    }

    (*env)->SetIntArrayRegion(env, outTerrainY, 0, cells, heights);
    free(heights);
    return CFX_OK;
}

JNIEXPORT jint JNICALL Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxSurfaceBiomes(
    JNIEnv *env, jclass clazz, jlong handle,
    jint blockX, jint blockZ, jint w, jint h, jint stride,
    jintArray terrainYArray, jintArray outBiomeIds
) {
    (void) clazz;
    CfxContext *ctx = cfxHandle(handle);
    if (ctx == NULL)
        return CFX_ERR_BAD_HANDLE;
    if (ctx->dim != DIM_OVERWORLD)
        return CFX_ERR_WRONG_DIM;
    if (!cfxValidCells(w, h) || stride <= 0)
        return CFX_ERR_BAD_SIZE;
    const int64_t lastX = (int64_t) blockX + (int64_t) (w - 1) * stride;
    const int64_t lastZ = (int64_t) blockZ + (int64_t) (h - 1) * stride;
    if (lastX < INT32_MIN || lastX > INT32_MAX || lastZ < INT32_MIN || lastZ > INT32_MAX)
        return CFX_ERR_BAD_ARGS;
    const int cells = w * h;
    if ((*env)->GetArrayLength(env, terrainYArray) < cells
        || (*env)->GetArrayLength(env, outBiomeIds) < cells)
        return CFX_ERR_BAD_SIZE;

    int *terrainY = malloc(sizeof(int) * (size_t) cells);
    int *biomes = malloc(sizeof(int) * (size_t) cells);
    if (terrainY == NULL || biomes == NULL)
    {
        free(terrainY);
        free(biomes);
        return CFX_ERR_ALLOC;
    }
    if (ctx->mc < MC_1_18)
    {
        const int err = cfxGenerateOverviewBiomes(
            ctx, blockX, blockZ, w, h, stride, biomes
        );
        if (err == CFX_OK)
            (*env)->SetIntArrayRegion(env, outBiomeIds, 0, cells, biomes);
        free(terrainY);
        free(biomes);
        return err;
    }

    (*env)->GetIntArrayRegion(env, terrainYArray, 0, cells, terrainY);
    if ((*env)->ExceptionCheck(env))
    {
        free(terrainY);
        free(biomes);
        return CFX_ERR_BAD_SIZE;
    }

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
            biomes[index] = sampleBiomeNoise(&ctx->g.bn, NULL, x4, y4, z4, NULL, 0);
        }
    }

    (*env)->SetIntArrayRegion(env, outBiomeIds, 0, cells, biomes);
    free(terrainY);
    free(biomes);
    return CFX_OK;
}

JNIEXPORT jint JNICALL Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxHeightsStrided(
    JNIEnv *env, jclass clazz, jlong handle, jint x4, jint z4, jint w, jint h, jint stride,
    jintArray outY, jintArray outIds
) {
    (void) clazz;
    CfxContext *ctx = cfxHandle(handle);
    if (ctx == NULL) {
        return CFX_ERR_BAD_HANDLE;
    }
    if (ctx->dim != DIM_OVERWORLD) {
        return CFX_ERR_WRONG_DIM;
    }
    if (!cfxValidCells(w, h) || stride <= 0) {
        return CFX_ERR_BAD_SIZE;
    }
    const int64_t rawWidth64 = (int64_t) (w - 1) * stride + 1;
    if (rawWidth64 <= 0 || rawWidth64 > CFX_MAX_CELLS
        || (*env)->GetArrayLength(env, outY) < w * h || (*env)->GetArrayLength(env, outIds) < w * h) {
        return CFX_ERR_BAD_SIZE;
    }
    const int rawWidth = (int) rawWidth64;
    float *rowY = malloc(sizeof(float) * (size_t) rawWidth);
    int *rowIds = malloc(sizeof(int) * (size_t) rawWidth);
    int *sampledY = malloc(sizeof(int) * (size_t) w * (size_t) h);
    int *sampledIds = malloc(sizeof(int) * (size_t) w * (size_t) h);
    if (rowY == NULL || rowIds == NULL || sampledY == NULL || sampledIds == NULL) {
        free(rowY);
        free(rowIds);
        free(sampledY);
        free(sampledIds);
        return CFX_ERR_ALLOC;
    }

    for (int j = 0; j < h; j++) {
        const int err = mapApproxHeight(rowY, rowIds, &ctx->g, &ctx->sn, x4, z4 + j * stride, rawWidth, 1);
        if (err != 0) {
            free(rowY);
            free(rowIds);
            free(sampledY);
            free(sampledIds);
            return CFX_ERR_GENERATION;
        }
        for (int i = 0; i < w; i++) {
            const int source = i * stride;
            sampledY[j * w + i] = (int) floorf(rowY[source]);
            sampledIds[j * w + i] = rowIds[source];
        }
    }

    (*env)->SetIntArrayRegion(env, outY, 0, w * h, sampledY);
    (*env)->SetIntArrayRegion(env, outIds, 0, w * h, sampledIds);
    free(rowY);
    free(rowIds);
    free(sampledY);
    free(sampledIds);
    return CFX_OK;
}

JNIEXPORT jint JNICALL Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxEndHeights(
    JNIEnv *env, jclass clazz, jlong handle, jint x4, jint z4, jint w, jint h, jintArray outY
) {
    (void) clazz;
    CfxContext *ctx = cfxHandle(handle);
    if (ctx == NULL) {
        return CFX_ERR_BAD_HANDLE;
    }
    if (ctx->dim != DIM_END) {
        return CFX_ERR_WRONG_DIM;
    }
    if (!cfxValidCells(w, h)) {
        return CFX_ERR_BAD_SIZE;
    }
    if ((*env)->GetArrayLength(env, outY) < w * h) {
        return CFX_ERR_BAD_SIZE;
    }

    float *y = malloc(sizeof(float) * (size_t) w * (size_t) h);
    if (y == NULL) {
        return CFX_ERR_ALLOC;
    }

    /* Same (x,z,w,h,scale=4,ymin=0) convention mapApproxHeight itself uses when
     * dispatching to the End for MC <= 1.17 generators - see generator.c's
     * mapApproxHeight, DIM_END branch. Kept as its own entry point (rather than
     * routing End queries through mapApproxHeight) so the Java side never has
     * to special-case which cubiomes function a given dimension needs. */
    const int err = mapEndSurfaceHeight(y, &ctx->g.en, &ctx->sn, x4, z4, w, h, 4, 0);
    if (err != 0) {
        free(y);
        return CFX_ERR_GENERATION;
    }

    int *iy = malloc(sizeof(int) * (size_t) w * (size_t) h);
    if (iy == NULL) {
        free(y);
        return CFX_ERR_ALLOC;
    }
    for (int i = 0; i < w * h; i++) {
        iy[i] = (int) floorf(y[i]);
    }

    (*env)->SetIntArrayRegion(env, outY, 0, w * h, iy);

    free(y);
    free(iy);
    return CFX_OK;
}

JNIEXPORT jint JNICALL Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxEndHeightsStrided(
    JNIEnv *env, jclass clazz, jlong handle, jint x4, jint z4, jint w, jint h, jint stride, jintArray outY
) {
    (void) clazz;
    CfxContext *ctx = cfxHandle(handle);
    if (ctx == NULL) {
        return CFX_ERR_BAD_HANDLE;
    }
    if (ctx->dim != DIM_END) {
        return CFX_ERR_WRONG_DIM;
    }
    if (!cfxValidCells(w, h) || stride <= 0) {
        return CFX_ERR_BAD_SIZE;
    }
    const int64_t rawWidth64 = (int64_t) (w - 1) * stride + 1;
    if (rawWidth64 <= 0 || rawWidth64 > CFX_MAX_CELLS || (*env)->GetArrayLength(env, outY) < w * h) {
        return CFX_ERR_BAD_SIZE;
    }
    const int rawWidth = (int) rawWidth64;
    float *rowY = malloc(sizeof(float) * (size_t) rawWidth);
    int *sampledY = malloc(sizeof(int) * (size_t) w * (size_t) h);
    if (rowY == NULL || sampledY == NULL) {
        free(rowY);
        free(sampledY);
        return CFX_ERR_ALLOC;
    }

    for (int j = 0; j < h; j++) {
        const int err = mapEndSurfaceHeight(rowY, &ctx->g.en, &ctx->sn, x4, z4 + j * stride, rawWidth, 1, 4, 0);
        if (err != 0) {
            free(rowY);
            free(sampledY);
            return CFX_ERR_GENERATION;
        }
        for (int i = 0; i < w; i++) {
            sampledY[j * w + i] = (int) floorf(rowY[i * stride]);
        }
    }

    (*env)->SetIntArrayRegion(env, outY, 0, w * h, sampledY);
    free(rowY);
    free(sampledY);
    return CFX_OK;
}

JNIEXPORT jint JNICALL Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxTreeCandidates(
    JNIEnv *env, jclass clazz, jlong handle, jint chunkX, jint chunkZ,
    jintArray outX, jintArray outY, jintArray outZ, jintArray outType,
    jintArray outBiome, jintArray outFlags, jintArray outCount, jint cap
) {
    (void) clazz;
    CfxContext *ctx = cfxHandle(handle);
    if (ctx == NULL) {
        return CFX_ERR_BAD_HANDLE;
    }
    if (ctx->dim != DIM_OVERWORLD) {
        return CFX_ERR_WRONG_DIM;
    }
    if (cap < 0 || cap > 256) {
        return CFX_ERR_BAD_SIZE;
    }
    if ((*env)->GetArrayLength(env, outX) < cap
        || (*env)->GetArrayLength(env, outY) < cap
        || (*env)->GetArrayLength(env, outZ) < cap
        || (*env)->GetArrayLength(env, outType) < cap
        || (*env)->GetArrayLength(env, outBiome) < cap
        || (*env)->GetArrayLength(env, outFlags) < cap
        || (*env)->GetArrayLength(env, outCount) < 1) {
        return CFX_ERR_BAD_SIZE;
    }

    size_t required = 0;
    const int countStatus = getChunkNaturalTreeCandidates(
        &ctx->g, chunkX, chunkZ, NULL, 0, &required
    );
    if (countStatus == FEATURE_PARTIAL) {
        return CFX_ERR_FEATURE_PARTIAL;
    }
    if (countStatus != FEATURE_OK || required > (size_t) cap) {
        return countStatus == FEATURE_OK ? CFX_ERR_BAD_SIZE : CFX_ERR_GENERATION;
    }
    if (required == 0) {
        const jint count = 0;
        (*env)->SetIntArrayRegion(env, outCount, 0, 1, &count);
        return CFX_OK;
    }

    NaturalTreeCandidate *records = malloc(sizeof(NaturalTreeCandidate) * required);
    if (records == NULL) {
        return CFX_ERR_ALLOC;
    }
    size_t actual = 0;
    const int fillStatus = getChunkNaturalTreeCandidates(
        &ctx->g, chunkX, chunkZ, records, required, &actual
    );
    if (fillStatus != FEATURE_OK || actual > required) {
        free(records);
        return fillStatus == FEATURE_PARTIAL ? CFX_ERR_FEATURE_PARTIAL : CFX_ERR_GENERATION;
    }

    jint *xs = malloc(sizeof(jint) * actual);
    jint *ys = malloc(sizeof(jint) * actual);
    jint *zs = malloc(sizeof(jint) * actual);
    jint *types = malloc(sizeof(jint) * actual);
    jint *biomes = malloc(sizeof(jint) * actual);
    jint *flags = malloc(sizeof(jint) * actual);
    if (xs == NULL || ys == NULL || zs == NULL || types == NULL || biomes == NULL || flags == NULL) {
        free(records);
        free(xs);
        free(ys);
        free(zs);
        free(types);
        free(biomes);
        free(flags);
        return CFX_ERR_ALLOC;
    }
    for (size_t i = 0; i < actual; i++) {
        xs[i] = records[i].pos.x;
        ys[i] = records[i].pos.y;
        zs[i] = records[i].pos.z;
        types[i] = records[i].type;
        biomes[i] = records[i].biome;
        flags[i] = (jint) records[i].flags;
    }
    (*env)->SetIntArrayRegion(env, outX, 0, (jsize) actual, xs);
    (*env)->SetIntArrayRegion(env, outY, 0, (jsize) actual, ys);
    (*env)->SetIntArrayRegion(env, outZ, 0, (jsize) actual, zs);
    (*env)->SetIntArrayRegion(env, outType, 0, (jsize) actual, types);
    (*env)->SetIntArrayRegion(env, outBiome, 0, (jsize) actual, biomes);
    (*env)->SetIntArrayRegion(env, outFlags, 0, (jsize) actual, flags);
    const jint count = (jint) actual;
    (*env)->SetIntArrayRegion(env, outCount, 0, 1, &count);
    free(records);
    free(xs);
    free(ys);
    free(zs);
    free(types);
    free(biomes);
    free(flags);
    return CFX_OK;
}

JNIEXPORT jint JNICALL Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxStructures(
    JNIEnv *env, jclass clazz, jlong handle, jint structType, jint regX0, jint regZ0, jint regX1, jint regZ1,
    jlongArray out, jint cap
) {
    (void) clazz;
    CfxContext *ctx = cfxHandle(handle);
    if (ctx == NULL) {
        return CFX_ERR_BAD_HANDLE;
    }
    if (structType < 0 || structType >= FEATURE_NUM) {
        return CFX_ERR_BAD_ARGS;
    }
    if (regX1 < regX0 || regZ1 < regZ0 || cap < 0) {
        return CFX_ERR_BAD_SIZE;
    }
    const int64_t regionsX = (int64_t) regX1 - (int64_t) regX0 + 1;
    const int64_t regionsZ = (int64_t) regZ1 - (int64_t) regZ0 + 1;
    if (regionsX * regionsZ > CFX_MAX_CELLS) {
        return CFX_ERR_BAD_SIZE;
    }

    jint effectiveCap = cap;
    const jsize outLen = (*env)->GetArrayLength(env, out);
    if (effectiveCap > outLen) {
        effectiveCap = outLen;
    }
    if (effectiveCap <= 0) {
        return 0;
    }

    jlong *packed = malloc(sizeof(jlong) * (size_t) effectiveCap);
    if (packed == NULL) {
        return CFX_ERR_ALLOC;
    }

    int found = 0;
    for (int regZ = regZ0; regZ <= regZ1 && found < effectiveCap; regZ++) {
        for (int regX = regX0; regX <= regX1 && found < effectiveCap; regX++) {
            Pos pos;
            const int ok = getStructurePos(structType, ctx->mc, ctx->g.seed, regX, regZ, &pos);
            if (ok) {
                packed[found] = ((jlong) pos.x << 32) | ((jlong) pos.z & 0xffffffffL);
                found++;
            }
        }
    }

    (*env)->SetLongArrayRegion(env, out, 0, found, packed);
    free(packed);
    return found;
}

JNIEXPORT jint JNICALL Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxStructureViable(
    JNIEnv *env, jclass clazz, jlong handle, jint structType, jint blockX, jint blockZ
) {
    (void) env;
    (void) clazz;
    CfxContext *ctx = cfxHandle(handle);
    if (ctx == NULL) {
        return 0;
    }
    if (structType < 0 || structType >= FEATURE_NUM) {
        return 0;
    }
    return isViableStructurePos(structType, &ctx->g, blockX, blockZ, 0) != 0 ? 1 : 0;
}
