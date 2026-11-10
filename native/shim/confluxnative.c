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
#include <string.h>
#include <time.h>

#include "finders.h"
#include "features/end_city.h"
#include "terrain_features.h"

#ifdef CUBIOMES_ENABLE_GPU
#include "gpu.h"

/* Metrics slot 13 (GPU_METRICS_ARRAY_COPY_NANOS, see gpu.h) is shim-owned:
 * the two batch entries below time their JNI array-marshal phase (argument
 * copy-out plus critical-region pinning) and accumulate the wall here, a
 * host-side cost the engine snapshot alone never sees. The lock guards only
 * the accumulate - never a dispatch. */
#if defined(_WIN32)
#include <windows.h>
static SRWLOCK cfxBatchMarshalLock = SRWLOCK_INIT;
#else
#include <pthread.h>
static pthread_mutex_t cfxBatchMarshalLock = PTHREAD_MUTEX_INITIALIZER;
#endif
static uint64_t cfxBatchMarshalNanos;

/* Monotonic host clock for the marshal span; failure leaves zero (metrics
 * only, never a dispatch error). */
static uint64_t cfxNanos(void)
{
#if defined(_WIN32)
    LARGE_INTEGER freq, ctr;
    if (!QueryPerformanceFrequency(&freq) || freq.QuadPart <= 0
        || !QueryPerformanceCounter(&ctr))
        return 0;
    const uint64_t secs = (uint64_t) ctr.QuadPart / (uint64_t) freq.QuadPart;
    const uint64_t rem = (uint64_t) ctr.QuadPart % (uint64_t) freq.QuadPart;
    return secs * 1000000000ULL
        + rem * 1000000000ULL / (uint64_t) freq.QuadPart;
#else
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0)
        return 0;
    return (uint64_t) ts.tv_sec * 1000000000ULL + (uint64_t) ts.tv_nsec;
#endif
}

static void cfxAddBatchMarshalNanos(uint64_t span)
{
#if defined(_WIN32)
    AcquireSRWLockExclusive(&cfxBatchMarshalLock);
    cfxBatchMarshalNanos += span;
    ReleaseSRWLockExclusive(&cfxBatchMarshalLock);
#else
    pthread_mutex_lock(&cfxBatchMarshalLock);
    cfxBatchMarshalNanos += span;
    pthread_mutex_unlock(&cfxBatchMarshalLock);
#endif
}

static uint64_t cfxGetBatchMarshalNanos(void)
{
#if defined(_WIN32)
    AcquireSRWLockExclusive(&cfxBatchMarshalLock);
    const uint64_t total = cfxBatchMarshalNanos;
    ReleaseSRWLockExclusive(&cfxBatchMarshalLock);
#else
    pthread_mutex_lock(&cfxBatchMarshalLock);
    const uint64_t total = cfxBatchMarshalNanos;
    pthread_mutex_unlock(&cfxBatchMarshalLock);
#endif
    return total;
}
#endif /* CUBIOMES_ENABLE_GPU */

#define CFX_ABI 17
#define CFX_NETHER_ROOF_Y 127

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

#ifndef CUBIOMES_ENABLE_GPU
/* Snapshot shape of the no-engine cfxGetGpuMetrics below; keep in sync with
 * gpu.h, whose slot constants the GPU builds use instead. */
#define GPU_METRICS_SLOTS 68
#endif

typedef struct {
    Generator g;
    SurfaceNoise sn; /* Overworld terrain columns and End surface heights. */
    BlendedNoise netherNoise;
    int netherNoiseReady;
    int mc;
    int dim;
    /* Stage accounting for the debug backend annotation (see cfxStageStats):
     * tracked overview/biome-stage calls whose shape the GPU path would
     * attempt, and how many of those the GPU actually served. Per context
     * (one per thread via CubiomesContexts), single-threaded by contract;
     * uint32 wraparound is harmless because Java only reads deltas. */
    uint32_t stageCalls;
    uint32_t stageGpuCalls;
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

static int cfxGenerateBiomes(CfxContext *ctx, int *out, Range r, int netherY) {
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

static int cfxGenerateBiomesStrided(
    CfxContext *ctx, int scale, int x, int z, int w, int h, int stride,
    int netherY, int *sampled
) {
    const int64_t rawWidth64 = (int64_t) (w - 1) * stride + 1;
    const int64_t rawHeight64 = (int64_t) (h - 1) * stride + 1;
    if (rawWidth64 <= 0 || rawWidth64 > CFX_MAX_CELLS
        || rawHeight64 <= 0 || rawHeight64 > CFX_MAX_CELLS)
        return CFX_ERR_BAD_SIZE;
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
        if (denseWidth64 * denseHeight64 > CFX_MAX_CELLS)
            return CFX_ERR_BAD_SIZE;
        const int denseWidth = (int) denseWidth64;
        const int denseHeight = (int) denseHeight64;
        const Range r = { scale, x - margin, z - margin, denseWidth, denseHeight, 0, 1 };
        int *dense = allocCache(&ctx->g, r);
        if (dense == NULL)
            return CFX_ERR_ALLOC;
        const int err = cfxGenerateBiomes(ctx, dense, r, netherY);
        if (err != 0)
        {
            free(dense);
            return CFX_ERR_GENERATION;
        }
        int j, i;
        for (j = 0; j < h; j++)
            for (i = 0; i < w; i++)
                sampled[j*w+i] =
                    dense[(j*stride + margin)*denseWidth + (i*stride + margin)];
        free(dense);
        return CFX_OK;
    }

#ifdef CUBIOMES_ENABLE_GPU
    /* The 1.18+ Overworld surface path streams scale-4 rows at a fixed z
     * stride; the batched GPU entry folds the whole row set into one
     * dispatch instead of one per row. Rows are generated at the full
     * subsampled width and then narrowed exactly like the CPU loop below,
     * so the output point set (and layout) is unchanged. Any refusal -
     * unsupported size, engine off/dead, no OpenCL loader, GPU error -
     * falls through to the per-row path, which re-enters the same engine
     * row by row through mapOverworldSurfaceBiome's transparent hook and
     * from there the CPU. The shape gate below is what the stage
     * accounting counts as a "GPU-attempted shape", same convention as
     * the overview/biome hotspots. */
    if (ctx->dim == DIM_OVERWORLD && ctx->mc >= MC_1_18 && scale == 4
        && (int64_t) h * rawWidth64 <= CFX_MAX_CELLS)
    {
        ctx->stageCalls += 1;
        int *rows = malloc(sizeof(int) * (size_t) h * (size_t) rawWidth64);
        if (rows != NULL)
        {
            if (gpuMapOverworldSurfaceBiomeRows(
                    rows, &ctx->g.bn, ctx->g.sha, scale, x, z,
                    (int) rawWidth64, h, stride
                ) == 0)
            {
                ctx->stageGpuCalls += 1;
                int j, i;
                for (j = 0; j < h; j++)
                    for (i = 0; i < w; i++)
                        sampled[j*w+i] = rows[(size_t) j * rawWidth64 + i*stride];
                free(rows);
                return CFX_OK;
            }
            free(rows);
        }
    }
#endif

    int j;
    for (j = 0; j < h; j++)
    {
        const Range r = { scale, x, z + j*stride, rawWidth, 1, 0, 1 };
        int *row = allocCache(&ctx->g, r);
        if (row == NULL)
            return CFX_ERR_ALLOC;
        const int err = cfxGenerateBiomes(ctx, row, r, netherY);
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
            ctx, 1, blockX, blockZ, w, h, blockStride, CFX_NETHER_ROOF_Y, out
        );
    if (blockStride % 4 != 0)
        return CFX_ERR_BAD_ARGS;
    return cfxGenerateBiomesStrided(
        ctx, 4, floordiv(blockX, 4), floordiv(blockZ, 4),
        w, h, blockStride / 4, CFX_NETHER_ROOF_Y, out
    );
}

JNIEXPORT jint JNICALL Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxAbi(
    JNIEnv *env, jclass clazz
) {
    (void) env;
    (void) clazz;
    return CFX_ABI;
}

JNIEXPORT void JNICALL Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxSetGpuMode(
    JNIEnv *env, jclass clazz, jint mode
) {
    (void) env;
    (void) clazz;
#ifdef CUBIOMES_ENABLE_GPU
    /* Invalid values are ignored inside the engine, so the Java side can
     * forward an unmapped enum ordinal without a pre-check. */
    gpuSetMode((int) mode);
#else
    (void) mode; /* GPU engine not compiled in: nothing to switch. */
#endif
}

JNIEXPORT jstring JNICALL Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxGetGpuStats(
    JNIEnv *env, jclass clazz, jlongArray out
) {
    (void) clazz;
    if (out == NULL || (*env)->GetArrayLength(env, out) < 3)
        return NULL;
    jlong stats[3];
#ifdef CUBIOMES_ENABLE_GPU
    stats[0] = (jlong) gpuGetMode();
    stats[1] = (jlong) gpuDispatchCount();
    stats[2] = (jlong) gpuHeightDispatchCount();
#else
    stats[0] = 0; /* engine not compiled in: nothing runs (GPU_MODE_OFF) */
    stats[1] = 0;
    stats[2] = 0;
#endif
    (*env)->SetLongArrayRegion(env, out, 0, 3, stats);
    return (*env)->NewStringUTF(env,
#ifdef CUBIOMES_ENABLE_GPU
        gpuDeviceName()
#else
        ""
#endif
    );
}

JNIEXPORT void JNICALL Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxGetGpuMetrics(
    JNIEnv *env, jclass clazz, jlongArray out
) {
    (void) clazz;
#ifdef CUBIOMES_ENABLE_GPU
    if (out == NULL || (*env)->GetArrayLength(env, out) < GPU_METRICS_SLOTS)
        return; /* shorter caller array: silently produce nothing */
    uint64_t snap[GPU_METRICS_SLOTS];
    gpuQueryMetrics(snap);
    /* Slot 13 is shim-owned (see gpu.h): the engine cannot measure the JNI
     * array marshal of the batch entries, this module does. */
    snap[GPU_METRICS_ARRAY_COPY_NANOS] = cfxGetBatchMarshalNanos();
    jlong metrics[GPU_METRICS_SLOTS];
    for (int i = 0; i < GPU_METRICS_SLOTS; i++)
        metrics[i] = (jlong) snap[i];
    (*env)->SetLongArrayRegion(env, out, 0, GPU_METRICS_SLOTS, metrics);
#else
    /* Engine not compiled in: every slot stays zero (the Java reader treats
     * the whole snapshot as no data through its availability latch). */
    if (out == NULL || (*env)->GetArrayLength(env, out) < GPU_METRICS_SLOTS)
        return;
    jlong metrics[GPU_METRICS_SLOTS] = {0};
    (*env)->SetLongArrayRegion(env, out, 0, GPU_METRICS_SLOTS, metrics);
#endif
}

JNIEXPORT jlong JNICALL Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxStageStats(
    JNIEnv *env, jclass clazz, jlong handle
) {
    (void) env;
    (void) clazz;
    CfxContext *ctx = cfxHandle(handle);
    if (ctx == NULL)
        return 0;
    /* Packed (gpu-served << 32) | tracked stage calls; both counters are
     * per-context and only ever read as deltas on the Java side. */
    return ((jlong) ctx->stageGpuCalls << 32) | (jlong) ctx->stageCalls;
}

JNIEXPORT jlong JNICALL Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxCreate(
    JNIEnv *env, jclass clazz, jint mcVersion, jlong seed, jint dim, jint flags
) {
    (void) env;
    (void) clazz;
    if (mcVersion <= MC_UNDEF || mcVersion > MC_NEWEST) {
        return 0;
    }
    if (dim != DIM_OVERWORLD && dim != DIM_NETHER && dim != DIM_END) {
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
    const int err = cfxGenerateBiomes(ctx, cache, r, CFX_NETHER_ROOF_Y);
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
        ctx, scale, x, z, w, h, stride, CFX_NETHER_ROOF_Y, sampled
    );
    if (err != CFX_OK) {
        free(sampled);
        return err;
    }

    (*env)->SetIntArrayRegion(env, out, 0, w * h, sampled);
    free(sampled);
    return CFX_OK;
}

JNIEXPORT jint JNICALL Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxBiomesAtYStrided(
    JNIEnv *env, jclass clazz, jlong handle, jint blockY, jint scale,
    jint x, jint z, jint w, jint h, jint stride, jintArray out
) {
    (void) clazz;
    CfxContext *ctx = cfxHandle(handle);
    if (ctx == NULL)
        return CFX_ERR_BAD_HANDLE;
    if (ctx->dim != DIM_NETHER)
        return CFX_ERR_WRONG_DIM;
    if (!cfxValidCells(w, h) || stride <= 0)
        return CFX_ERR_BAD_SIZE;
    if (!cfxValidScale(scale))
        return CFX_ERR_BAD_ARGS;
    const int64_t rawWidth64 = (int64_t) (w - 1) * stride + 1;
    const int64_t rawHeight64 = (int64_t) (h - 1) * stride + 1;
    const int64_t lastX = (int64_t) x + (int64_t) (w - 1) * stride;
    const int64_t lastZ = (int64_t) z + (int64_t) (h - 1) * stride;
    if (rawWidth64 <= 0 || rawWidth64 > CFX_MAX_CELLS
        || rawHeight64 <= 0 || rawHeight64 > CFX_MAX_CELLS
        || (*env)->GetArrayLength(env, out) < w * h)
        return CFX_ERR_BAD_SIZE;
    if (lastX < INT32_MIN || lastX > INT32_MAX
        || lastZ < INT32_MIN || lastZ > INT32_MAX)
        return CFX_ERR_BAD_ARGS;

    int *sampled = malloc(sizeof(int) * (size_t) w * (size_t) h);
    if (sampled == NULL)
        return CFX_ERR_ALLOC;
    int j, i;
    for (j = 0; j < h; j++) {
        const int sampleZ = z + j * stride;
        for (i = 0; i < w; i++) {
            const int sampleX = x + i * stride;
            if (scale == 1) {
                int x4, y4, z4;
                voronoiAccess3D(
                    ctx->g.sha, sampleX, blockY, sampleZ, &x4, &y4, &z4
                );
                sampled[j*w+i] = getNetherBiome(&ctx->g.nn, x4, y4, z4, NULL);
            } else {
                sampled[j*w+i] = getNetherBiome(
                    &ctx->g.nn, sampleX, floordiv(blockY, 4), sampleZ, NULL
                );
            }
        }
    }
    (*env)->SetIntArrayRegion(env, out, 0, w * h, sampled);
    free(sampled);
    return CFX_OK;
}

/*
 * Sparse coarse-biome sampling for wide-area search. cfxBiomesStrided materializes
 * dense source rows and discards (stride-1)/stride of that work, which dominates any
 * search grid whose stride exceeds the biome cell; here every output cell is exactly
 * one independent biome-source query. Supported combinations only - Overworld
 * post-1.18 at scale 16 (climate query, no terrain solve), Nether at scale 4, End at
 * scale 4/16. Overworld scale 4 embeds the exact terrain surface solve (hundreds of
 * microseconds per cell) and pre-1.18 Overworld biome stacks are layer-based, so both
 * stay on the dense path; anything unsupported returns CFX_ERR_BAD_ARGS and the caller
 * falls back.
 */
JNIEXPORT jint JNICALL Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxBiomesSparseStrided(
    JNIEnv *env, jclass clazz, jlong handle,
    jint scale, jint x, jint z, jint w, jint h, jint stride, jintArray out
)
{
    (void) clazz;
    CfxContext *ctx = cfxHandle(handle);
    if (ctx == NULL)
        return CFX_ERR_BAD_HANDLE;
    if (!cfxValidCells(w, h) || stride <= 0)
        return CFX_ERR_BAD_SIZE;
    if (!cfxValidScale(scale))
        return CFX_ERR_BAD_ARGS;
    const int supported =
        (ctx->dim == DIM_OVERWORLD && ctx->mc >= MC_1_18 && scale == 16) ||
        (ctx->dim == DIM_NETHER && scale == 4) ||
        (ctx->dim == DIM_END && (scale == 4 || scale == 16));
    if (!supported)
        return CFX_ERR_BAD_ARGS;
    const int64_t lastX = (int64_t) x + (int64_t) (w - 1) * stride;
    const int64_t lastZ = (int64_t) z + (int64_t) (h - 1) * stride;
    if (lastX < INT32_MIN || lastX > INT32_MAX
        || lastZ < INT32_MIN || lastZ > INT32_MAX)
        return CFX_ERR_BAD_ARGS;
    if ((*env)->GetArrayLength(env, out) < w * h)
        return CFX_ERR_BAD_SIZE;

    int *sampled = malloc(sizeof(int) * (size_t) w * (size_t) h);
    if (sampled == NULL)
        return CFX_ERR_ALLOC;
    int j, i;
    for (j = 0; j < h; j++)
    {
        for (i = 0; i < w; i++)
        {
            const Range r = { scale, x + i * stride, z + j * stride, 1, 1, 0, 1 };
            if (cfxGenerateBiomes(ctx, &sampled[j * w + i], r, CFX_NETHER_ROOF_Y) != CFX_OK)
            {
                free(sampled);
                return CFX_ERR_GENERATION;
            }
        }
    }
    (*env)->SetIntArrayRegion(env, out, 0, w * h, sampled);
    free(sampled);
    return CFX_OK;
}

/* The scale-16 grid samples at quart y=0, where a column under tall terrain resolves
 * to an underground biome (a stony-peaks summit with surface y=181 reads deep-dark),
 * so surface-only biomes are invisible to the scan at any stride. Re-test the climate
 * on the surface slice: depth 0 is where the caller's surface refinement samples, and
 * the parameter pre-filter has already validated every horizontal parameter. */
static int cfxSurfaceSliceIsTarget(const CfxContext *ctx, int cx, int cz, int target)
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
 * Search-targeted variant of cfxBiomesSparseStrided: only cells that could still be
 * the target biome pay the full biome query. Overworld post-1.18 at scale 16 consults
 * the target's climate-parameter envelope (getBiomeParaLimits). The scale-16 full
 * query samples its climate parameters at quart coordinates (x + i*stride)*4 + 2 with
 * SAMPLE_NO_SHIFT, so the prefilter samples the same DoublePerlin noise at the same
 * coordinates: a parameter outside the envelope proves the cell cannot be the target.
 * Parameter quantization mirrors sampleBiomeNoise exactly
 * ((int64_t)(10000.0F * (float) v)) so a cell sitting on an envelope edge cannot be
 * wrongly rejected. Rejected cells are reported as -1; every other cell carries its
 * y=0 biome id unless the surface-slice re-test (cfxSurfaceSliceIsTarget) promotes it
 * to the target. Combinations without a parameter table (Nether scale 4, End scale
 * 4/16, unlisted Overworld ids) fall back to the plain per-cell query.
 */
JNIEXPORT jint JNICALL Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxBiomesScanStrided(
    JNIEnv *env, jclass clazz, jlong handle,
    jint scale, jint x, jint z, jint w, jint h, jint stride, jint target, jintArray out
)
{
    (void) clazz;
    CfxContext *ctx = cfxHandle(handle);
    if (ctx == NULL)
        return CFX_ERR_BAD_HANDLE;
    if (!cfxValidCells(w, h) || stride <= 0)
        return CFX_ERR_BAD_SIZE;
    if (!cfxValidScale(scale))
        return CFX_ERR_BAD_ARGS;
    const int supported =
        (ctx->dim == DIM_OVERWORLD && ctx->mc >= MC_1_18 && scale == 16) ||
        (ctx->dim == DIM_NETHER && scale == 4) ||
        (ctx->dim == DIM_END && (scale == 4 || scale == 16));
    if (!supported)
        return CFX_ERR_BAD_ARGS;
    const int64_t lastX = (int64_t) x + (int64_t) (w - 1) * stride;
    const int64_t lastZ = (int64_t) z + (int64_t) (h - 1) * stride;
    if (lastX < INT32_MIN || lastX > INT32_MAX
        || lastZ < INT32_MIN || lastZ > INT32_MAX)
        return CFX_ERR_BAD_ARGS;
    if ((*env)->GetArrayLength(env, out) < w * h)
        return CFX_ERR_BAD_SIZE;

    const int *lim = (ctx->dim == DIM_OVERWORLD && ctx->mc >= MC_1_18 && scale == 16)
        ? getBiomeParaLimits(ctx->mc, target) : NULL;
    static const int para[] = {
        NP_TEMPERATURE, NP_HUMIDITY, NP_EROSION, NP_CONTINENTALNESS, NP_WEIRDNESS,
    };

    int *sampled = malloc(sizeof(int) * (size_t) w * (size_t) h);
    if (sampled == NULL)
        return CFX_ERR_ALLOC;
    int j, i, k;
    for (j = 0; j < h; j++)
    {
        for (i = 0; i < w; i++)
        {
            const int cx = x + i * stride;
            const int cz = z + j * stride;
            if (lim != NULL)
            {
                const double qx = (double) (cx * 4 + 2);
                const double qz = (double) (cz * 4 + 2);
                for (k = 0; k < 5; k++)
                {
                    const int *plim = lim + 2 * para[k];
                    if (plim[0] == INT_MIN && plim[1] == INT_MAX)
                        continue;
                    const float v = (float) sampleDoublePerlin(
                        &ctx->g.bn.climate[para[k]], qx, 0, qz);
                    const int64_t q = (int64_t) (10000.0F * v);
                    if (q < plim[0] || q > plim[1])
                        break;
                }
                if (k < 5)
                {
                    sampled[j * w + i] = -1;
                    continue;
                }
            }
            const Range r = { scale, cx, cz, 1, 1, 0, 1 };
            if (cfxGenerateBiomes(ctx, &sampled[j * w + i], r, CFX_NETHER_ROOF_Y) != CFX_OK)
            {
                free(sampled);
                return CFX_ERR_GENERATION;
            }
            if (lim != NULL && sampled[j * w + i] != target
                && cfxSurfaceSliceIsTarget(ctx, cx, cz, target))
            {
                sampled[j * w + i] = target;
            }
        }
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

#if 0 /* parked: the surface-columns GPU attempt after the seeded e2e A/B came out net-wall negative (first tile +76%); restore = flip this guard and the fork's parked guards, rebuild, then the env gate drives serving as before */
/*
 * Gate for the exact-columns GPU attempt inside cfxSurfaceColumns: an anchor batch at or
 * above this count hands its whole grid to the fork's float point kernel in one dispatch.
 * The default matches the overview heights gate (dispatch overhead against the ~150us
 * per-anchor CPU solve); env CFX_GPU_COLUMN_MIN_ANCHORS overrides it once at first use (the
 * CUBIOMES_GPU precedent) and 0 disables the attempt entirely - the serving switch for
 * composed A/B measurement, not a product setting. Malformed values keep the default.
 */
#define CFX_GPU_COLUMN_MIN_ANCHORS 256

static int cfxGpuColumnMinAnchors(void)
{
    static int cached;
    static int parsed;
    if (!parsed)
    {
        parsed = 1;
        cached = CFX_GPU_COLUMN_MIN_ANCHORS;
        const char *env = getenv("CFX_GPU_COLUMN_MIN_ANCHORS");
        if (env != NULL && *env != '\0')
        {
            /* Strict non-negative decimal, hand-parsed: strtol would drag a
             * glibc-2.38-only symbol into the committed library. */
            long value = 0;
            int any = 0, bad = 0;
            const char *p;
            for (p = env; *p != '\0'; p++)
            {
                if (*p < '0' || *p > '9')
                {
                    bad = 1;
                    break;
                }
                value = value * 10 + (*p - '0');
                if (value > INT_MAX)
                {
                    bad = 1;
                    break;
                }
                any = 1;
            }
            if (any && !bad)
                cached = (int) value;
        }
    }
    return cached;
}
#endif /* parked surface-columns GPU attempt */

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
#if 0 /* parked: the surface-columns GPU attempt after the seeded e2e A/B came out net-wall negative (first tile +76%); restore = flip this guard and the fork's parked guards, rebuild, then the env gate drives serving as before */
    /*
     * GPU attempt for the exact-columns point seam, inside the version/dimension guards the
     * CPU path already applies (MC 1.18+ Overworld only - the kernel refuses the rest the
     * same way, so the gate just keeps refused shapes off the runtime). Both request shapes
     * marshal to the same anchor arrays: the dense rect's lattice coordinates expand into
     * exactly the (x, z) lists the per-point loop visits, in the same j*w+i order, and the
     * kernel fills the same four planes - sentinels and the fluid flag post-pass included -
     * so a served batch returns the layout the CPU loops would have written. The seam has no
     * Java-side repair (a false return drops the caller's tile), so a whole-or-nothing
     * refusal must only ever fall through to those CPU loops untouched. The float kernel
     * approximates the CPU reference within the sparse seam's drift budget. No mutex is
     * held across the dispatch, and the stage counters count attempts/serves the way the
     * sparse entries do.
     */
    const int columnGate = cfxGpuColumnMinAnchors();
    const int gpuEligible = columnGate > 0 && ctx->mc >= MC_1_18 && cells >= columnGate;
    ctx->stageCalls += gpuEligible;
#ifdef CUBIOMES_ENABLE_GPU
    int gpuServed = 0;
    if (gpuEligible)
    {
        int *anchors = malloc(sizeof(int) * (size_t) cells * 2);
        if (anchors != NULL)
        {
            int j, i;
            for (j = 0; j < h; j++)
            {
                const int sampleZ = (int) ((int64_t) blockZ + (int64_t) j * stride);
                for (i = 0; i < w; i++)
                {
                    const int index = j * w + i;
                    anchors[index] = (int) ((int64_t) blockX + (int64_t) i * stride);
                    anchors[cells + index] = sampleZ;
                }
            }
            gpuServed = gpuCfxSurfaceColumnsPointsFloat(
                solidY, fluidY, surfaceY, flags, &ctx->g.bn,
                anchors, anchors + cells, cells
            ) == 0;
            free(anchors);
        }
        ctx->stageGpuCalls += gpuServed;
    }
    if (gpuServed)
    {
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
#endif
#endif /* parked surface-columns GPU attempt */
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
} CfxNoiseDetail;

static const CfxNoiseDetail CFX_DETAIL_FULL = {0, 0, 0};

#define CFX_DETAIL_MIN_STRIDE 8
#define CFX_JAGGED_KEEP_OCTAVES 4

static CfxNoiseDetail cfxNoiseDetailForStride(const SurfaceNoise *sn, int stride)
{
    CfxNoiseDetail d = CFX_DETAIL_FULL;
    if (stride < CFX_DETAIL_MIN_STRIDE)
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
static double cfxSampleDoublePerlinPrefix(
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

static double cfxModernTerrainNoise(
    const SurfaceNoise *sn, int x, int y, int z, const CfxNoiseDetail *detail
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
    frequency = ldexp(1.0, -detail->minMaxFirst);
    for (i = detail->minMaxFirst; i < 16; i++)
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

/*
 * GPU dispatch thresholds for the two hot stages (the fp32 kernels behind
 * gpuCfxOverviewHeights/gpuCfxSurfaceBiomes and their sparse entries).
 * Below these the kernel launch overhead outweighs the CPU loops, so the
 * lattice-refine 2x2 cells and other tiny queries stay CPU-resident.
 */
#define CFX_GPU_HEIGHT_MIN_CELLS 256
#define CFX_GPU_BIOME_MIN_CELLS 1024

/*
 * Bilinear quart-macro reconstruction: the same floordiv ladder the CPU
 * macro path runs, applied to whichever lattice (GPU or CPU) produced the
 * macro heights.
 */
static void cfxInterpolateMacro(
    int *heights, const int *macro,
    int blockX, int blockZ, int w, int h, int stride,
    int minX4, int minZ4, int gridW
) {
    int j, i;
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
}

static int cfxModernOverviewHeight(
    const CfxContext *ctx, int x4, int z4, const CfxNoiseDetail *detail
)
{
    const BiomeNoise *bn = &ctx->g.bn;
    float values[4];
    cfxTerrainParameters(bn, x4, z4, values);
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
        jagged = cfxSampleDoublePerlinPrefix(
            &bn->jagged, x4 * 4 * 1500.0, 0.0, z4 * 4 * 1500.0,
            CFX_JAGGED_KEEP_OCTAVES
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
    for (i = 0; i < CFX_OVERVIEW_ITERATIONS; i++)
    {
        const double noise = cfxModernTerrainNoise(
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

/*
 * One climate-model overview column: the per-cell computation the stride >= 4
 * rect loop of cfxOverviewHeightsImpl resolves for (blockX, blockZ), bound in
 * one place so the rect loop and the sparse refinement entry cannot drift.
 */
static int cfxOverviewHeightAt(
    const CfxContext *ctx, int blockX, int blockZ, const CfxNoiseDetail *detail
) {
    return cfxModernOverviewHeight(ctx, floordiv(blockX, 4), floordiv(blockZ, 4), detail);
}

static jint cfxOverviewHeightsImpl(
    JNIEnv *env, CfxContext *ctx,
    jint blockX, jint blockZ, jint w, jint h, jint stride,
    jintArray outTerrainY, const CfxNoiseDetail *detail
) {
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
        /*
         * Shape data and the GPU-attempt gate shared by the accounting, the
         * GPU fast path and the CPU loops below. A call counts as a tracked
         * stage when its shape is one the GPU path would attempt; whether
         * the attempt then succeeds decides the gpu counter (the fp32
         * kernels - see gpuCfxOverviewHeights). Any unsupported shape or
         * engine problem falls through to the CPU loops; CUBIOMES_GPU=off
         * never reaches the runtime at all.
         */
        const int minX4 = floordiv(blockX, 4);
        const int minZ4 = floordiv(blockZ, 4);
        const int maxX4 = floordiv((int)lastX, 4) + 1;
        const int maxZ4 = floordiv((int)lastZ, 4) + 1;
        const int gridW = maxX4 - minX4 + 1;
        const int gridH = maxZ4 - minZ4 + 1;
        const int gpuEligible = (stride < 4 && gridW * gridH >= CFX_GPU_HEIGHT_MIN_CELLS)
            || (stride % 4 == 0 && w * h >= CFX_GPU_HEIGHT_MIN_CELLS);
        ctx->stageCalls += gpuEligible;
#ifdef CUBIOMES_ENABLE_GPU
        int gpuServed = 0;
        if (gpuEligible)
        {
            if (stride < 4)
            {
                int *macro = malloc(sizeof(int) * (size_t)gridW * gridH);
                if (macro != NULL
                    && gpuCfxOverviewHeights(macro, &ctx->g.bn,
                        minX4, minZ4, gridW, gridH, 1, 1,
                        detail->mainFirst, detail->minMaxFirst,
                        detail->truncated) == 0)
                {
                    cfxInterpolateMacro(heights, macro,
                        blockX, blockZ, w, h, stride, minX4, minZ4, gridW);
                    gpuServed = 1;
                }
                free(macro);
            }
            else
            {
                gpuServed = gpuCfxOverviewHeights(heights, &ctx->g.bn,
                    minX4, minZ4, w, h, stride / 4, stride / 4,
                    detail->mainFirst, detail->minMaxFirst,
                    detail->truncated) == 0;
            }
            ctx->stageGpuCalls += gpuServed;
        }
        if (gpuServed)
        {
            (*env)->SetIntArrayRegion(env, outTerrainY, 0, cells, heights);
            free(heights);
            return CFX_OK;
        }
#endif
        if (stride < 4)
        {
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
                        ctx, minX4+i, minZ4+j, detail
                    );
            cfxInterpolateMacro(heights, macro,
                blockX, blockZ, w, h, stride, minX4, minZ4, gridW);
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
                    heights[j*w+i] = cfxOverviewHeightAt(ctx, sampleX, sampleZ, detail);
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

JNIEXPORT jint JNICALL Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxOverviewHeights(
    JNIEnv *env, jclass clazz, jlong handle,
    jint blockX, jint blockZ, jint w, jint h, jint stride,
    jintArray outTerrainY
) {
    (void) clazz;
    CfxContext *ctx = cfxHandle(handle);
    if (ctx == NULL)
        return CFX_ERR_BAD_HANDLE;
    return cfxOverviewHeightsImpl(
        env, ctx, blockX, blockZ, w, h, stride, outTerrainY, &CFX_DETAIL_FULL
    );
}

/*
 * Coarse-lattice variant of cfxOverviewHeights for the LOD2-4 anchor sweeps:
 * same geometry and validation, but terrain-noise octaves shorter than the
 * sampling stride are skipped (see CfxNoiseDetail). Waterline-band and
 * exact-residual callers must keep the full-detail entry above.
 */
JNIEXPORT jint JNICALL Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxOverviewHeightsCoarse(
    JNIEnv *env, jclass clazz, jlong handle,
    jint blockX, jint blockZ, jint w, jint h, jint stride,
    jintArray outTerrainY
) {
    (void) clazz;
    CfxContext *ctx = cfxHandle(handle);
    if (ctx == NULL)
        return CFX_ERR_BAD_HANDLE;
    const CfxNoiseDetail detail = cfxNoiseDetailForStride(&ctx->g.bn.terrain, stride);
    return cfxOverviewHeightsImpl(
        env, ctx, blockX, blockZ, w, h, stride, outTerrainY, &detail
    );
}

/*
 * One surface-biome voronoi sample: the inner-cell computation of
 * cfxSurfaceBiomes' plain loop, bound in one place so the rect loop and the
 * sparse refinement entry cannot drift.
 */
static int cfxSurfaceBiomeAt(const CfxContext *ctx, int sampleX, int sampleZ, int terrainY)
{
    const int surface = terrainY < 62 ? 62 : terrainY;
    int x4, y4, z4;
    voronoiAccess3D(ctx->g.sha, sampleX, surface, sampleZ, &x4, &y4, &z4);
    return sampleBiomeNoise(&ctx->g.bn, NULL, x4, y4, z4, NULL, 0);
}

/*
 * Per-call memo for cfxSurfaceBiomes: the biome of a voronoi nucleus is a pure
 * function of (nucleus, generator state), and fine sampling strides revisit the
 * same nucleus constantly - measured 10.6x unique-sample reuse at block stride,
 * 2.9x at stride 2, 1.16-1.17x at the coarse strides. Each call gets one open-
 * addressing table sized to twice its cells (load factor <= 0.5); entries store
 * biome+1 so 0 marks an empty slot and biome id 0 (ocean) stays representable.
 * A hit returns exactly what sampleBiomeNoise returned for the same nucleus,
 * so results are bit-identical to the unmemoized loop; allocation failure or a
 * table beyond CFX_NUCLEUS_MEMO_MAX_ENTRIES simply declines and the caller runs
 * its plain loop. Lives in its own function so the plain sweep below keeps its
 * exact original shape; strides past CFX_NUCLEUS_MEMO_MAX_STRIDE never reach it
 * because their nucleus reuse cannot pay for the table.
 */
typedef struct {
    int32_t x4, y4, z4;
    int32_t biomePlusOne;
} CfxNucleusEntry;

#define CFX_NUCLEUS_MEMO_MIN_CELLS 64
#define CFX_NUCLEUS_MEMO_MAX_ENTRIES (1u << 20)
#define CFX_NUCLEUS_MEMO_MAX_STRIDE 2

static uint32_t cfxNucleusHash(int32_t x4, int32_t y4, int32_t z4)
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
static int cfxSurfaceBiomesMemoized(
    const CfxContext *ctx, int blockX, int blockZ, int w, int h, int stride,
    const int *terrainY, int *biomes, int cells)
{
    if (stride > CFX_NUCLEUS_MEMO_MAX_STRIDE || cells < CFX_NUCLEUS_MEMO_MIN_CELLS)
        return 0;
    uint32_t size = 64;
    while (size < (uint32_t) cells * 2u && size < CFX_NUCLEUS_MEMO_MAX_ENTRIES)
        size <<= 1;
    if (size < (uint32_t) cells * 2u)
        return 0;
    CfxNucleusEntry *memo = calloc(size, sizeof(CfxNucleusEntry));
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
            uint32_t slot = cfxNucleusHash(
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

    /*
     * Bit-exact GPU fast path for the voronoi + sampleBiomeNoise loop (and
     * the stride<=2 nucleus memo below it, which returns exactly what the
     * plain loop returns). Dispatch overhead dominates tiny batches, so the
     * lattice-refine 2x2 cells stay CPU-resident: the min-cell gate is what
     * the stage accounting below counts as "GPU-attempted shape".
     */
    const int gpuEligible = cells >= CFX_GPU_BIOME_MIN_CELLS;
    ctx->stageCalls += gpuEligible;
#ifdef CUBIOMES_ENABLE_GPU
    if (gpuEligible
        && gpuCfxSurfaceBiomes(biomes, &ctx->g.bn, ctx->g.sha,
            blockX, blockZ, w, h, stride, terrainY) == 0)
    {
        ctx->stageGpuCalls++;
        (*env)->SetIntArrayRegion(env, outBiomeIds, 0, cells, biomes);
        free(terrainY);
        free(biomes);
        return CFX_OK;
    }
#endif

    if (cfxSurfaceBiomesMemoized(
            ctx, blockX, blockZ, w, h, stride, terrainY, biomes, cells))
    {
        (*env)->SetIntArrayRegion(env, outBiomeIds, 0, cells, biomes);
        free(terrainY);
        free(biomes);
        return CFX_OK;
    }

    int j, i;
    for (j = 0; j < h; j++)
    {
        const int sampleZ = (int) ((int64_t) blockZ + (int64_t) j * stride);
        for (i = 0; i < w; i++)
        {
            const int index = j * w + i;
            const int sampleX = (int) ((int64_t) blockX + (int64_t) i * stride);
            biomes[index] = cfxSurfaceBiomeAt(ctx, sampleX, sampleZ, terrainY[index]);
        }
    }

    (*env)->SetIntArrayRegion(env, outBiomeIds, 0, cells, biomes);
    free(terrainY);
    free(biomes);
    return CFX_OK;
}

/*
 * Sparse overview refinement for the collected waterline bands: one call per
 * bounded chunk of independent columns instead of one JNI entry per column.
 * Every point resolves the same column a 1x1 full-detail cfxOverviewHeights
 * call produces - the climate model through the shared cfxOverviewHeightAt,
 * the pre-1.18 model through the impl's own 1x1 shape (one scale-4 biome
 * query, then cfxLegacyOverviewHeight). All-or-nothing: any per-point failure
 * returns its status and leaves the output untouched, so the caller repairs
 * through the single entry. Plain region copies, no critical sections: on
 * 1.18+ an above-gate chunk is handed to the float sparse GPU kernel first
 * (drift-budgeted approximation of the CPU reference, whole-or-nothing
 * refusal falls back into the same CPU loops); everything else stays pure
 * CPU.
 */
JNIEXPORT jint JNICALL Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxOverviewHeightsSparse(
    JNIEnv *env, jclass clazz, jlong handle,
    jintArray blockXArray, jintArray blockZArray, jint n, jintArray outTerrainYArray
) {
    (void) clazz;
    CfxContext *ctx = cfxHandle(handle);
    if (ctx == NULL)
        return CFX_ERR_BAD_HANDLE;
    if (ctx->dim != DIM_OVERWORLD)
        return CFX_ERR_WRONG_DIM;
    if (n < 0 || n > CFX_MAX_CELLS
        || (*env)->GetArrayLength(env, blockXArray) < n
        || (*env)->GetArrayLength(env, blockZArray) < n
        || (*env)->GetArrayLength(env, outTerrainYArray) < n)
        return CFX_ERR_BAD_SIZE;
    if (n == 0)
        return CFX_OK;

    int *in = malloc(sizeof(int) * (size_t) n * 2);
    int *heights = malloc(sizeof(int) * (size_t) n);
    if (in == NULL || heights == NULL)
    {
        free(in);
        free(heights);
        return CFX_ERR_ALLOC;
    }
    int *blockX = in, *blockZ = in + n;
    (*env)->GetIntArrayRegion(env, blockXArray, 0, n, blockX);
    (*env)->GetIntArrayRegion(env, blockZArray, 0, n, blockZ);
    if ((*env)->ExceptionCheck(env))
    {
        free(in);
        free(heights);
        return CFX_ERR_BAD_SIZE;
    }

    int k;
    if (ctx->mc >= MC_1_18)
    {
        const int gpuEligible = n >= CFX_GPU_HEIGHT_MIN_CELLS;
        ctx->stageCalls += gpuEligible;
#ifdef CUBIOMES_ENABLE_GPU
        int gpuServed = 0;
        if (gpuEligible)
        {
            int *quart = malloc(sizeof(int) * (size_t) n * 2);
            if (quart != NULL)
            {
                int *x4 = quart, *z4 = quart + n;
                for (k = 0; k < n; k++)
                {
                    x4[k] = floordiv(blockX[k], 4);
                    z4[k] = floordiv(blockZ[k], 4);
                }
                gpuServed = gpuCfxOverviewHeightsSparse(
                    heights, &ctx->g.bn, x4, z4, n, 0, 0, 0
                ) == 0;
                free(quart);
            }
            ctx->stageGpuCalls += gpuServed;
        }
        if (gpuServed)
        {
            (*env)->SetIntArrayRegion(env, outTerrainYArray, 0, n, heights);
            free(in);
            free(heights);
            return CFX_OK;
        }
#endif
        for (k = 0; k < n; k++)
            heights[k] = cfxOverviewHeightAt(ctx, blockX[k], blockZ[k], &CFX_DETAIL_FULL);
    }
    else
    {
        for (k = 0; k < n; k++)
        {
            int biome;
            const int err = cfxGenerateOverviewBiomes(ctx, blockX[k], blockZ[k], 1, 1, 4, &biome);
            if (err != CFX_OK)
            {
                free(in);
                free(heights);
                return err;
            }
            heights[k] = cfxLegacyOverviewHeight(ctx, biome, blockX[k], blockZ[k]);
        }
    }

    (*env)->SetIntArrayRegion(env, outTerrainYArray, 0, n, heights);
    free(in);
    free(heights);
    return CFX_OK;
}

/*
 * Sparse surface-biome refinement for the collected lattice-refine cells: one
 * call per bounded chunk of uniformly-shaped cells. Cell i covers an edge x
 * edge point grid spaced stride blocks apart with heights terrainYPacked
 * [i*edge*edge..]; post-1.18 each point is one shared cfxSurfaceBiomeAt
 * sample, pre-1.18 each cell is the single entry's own dense edge x edge
 * layer-stack query (the stack is query-shape dependent, so the cell stays a
 * cell). All-or-nothing and no critical sections, like the heights entry.
 */
JNIEXPORT jint JNICALL Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxSurfaceBiomesSparse(
    JNIEnv *env, jclass clazz, jlong handle,
    jintArray cellXArray, jintArray cellZArray, jint edge, jint stride,
    jintArray terrainYPackedArray, jint n, jintArray outBiomeIdsPackedArray
) {
    (void) clazz;
    CfxContext *ctx = cfxHandle(handle);
    if (ctx == NULL)
        return CFX_ERR_BAD_HANDLE;
    if (ctx->dim != DIM_OVERWORLD)
        return CFX_ERR_WRONG_DIM;
    const int64_t points = (int64_t) edge * (int64_t) edge;
    if (edge <= 0 || stride <= 0 || n < 0
        || points > CFX_MAX_CELLS || n > CFX_MAX_CELLS
        || points * (int64_t) n > CFX_MAX_CELLS
        || (*env)->GetArrayLength(env, cellXArray) < n
        || (*env)->GetArrayLength(env, cellZArray) < n
        || (*env)->GetArrayLength(env, terrainYPackedArray) < points * (int64_t) n
        || (*env)->GetArrayLength(env, outBiomeIdsPackedArray) < points * (int64_t) n)
        return CFX_ERR_BAD_SIZE;
    if (n == 0)
        return CFX_OK;

    const int cells = (int) (points * (int64_t) n);
    int *in = malloc(sizeof(int) * ((size_t) n * 2 + (size_t) cells));
    int *out = malloc(sizeof(int) * (size_t) cells);
    if (in == NULL || out == NULL)
    {
        free(in);
        free(out);
        return CFX_ERR_ALLOC;
    }
    int *cellX = in, *cellZ = in + n;
    int *terrainY = in + n * 2;
    (*env)->GetIntArrayRegion(env, cellXArray, 0, n, cellX);
    (*env)->GetIntArrayRegion(env, cellZArray, 0, n, cellZ);
    (*env)->GetIntArrayRegion(env, terrainYPackedArray, 0, cells, terrainY);
    if ((*env)->ExceptionCheck(env))
    {
        free(in);
        free(out);
        return CFX_ERR_BAD_SIZE;
    }

    int k;
    if (ctx->mc >= MC_1_18)
    {
        const int gpuEligible = n >= CFX_GPU_BIOME_MIN_CELLS;
        ctx->stageCalls += gpuEligible;
#ifdef CUBIOMES_ENABLE_GPU
        int gpuServed = 0;
        if (gpuEligible)
        {
            gpuServed = gpuCfxSurfaceBiomesSparse(
                out, &ctx->g.bn, ctx->g.sha, cellX, cellZ, terrainY, edge, stride, n
            ) == 0;
            ctx->stageGpuCalls += gpuServed;
        }
        if (gpuServed)
        {
            (*env)->SetIntArrayRegion(env, outBiomeIdsPackedArray, 0, cells, out);
            free(in);
            free(out);
            return CFX_OK;
        }
#endif
        for (k = 0; k < n; k++)
        {
            const int base = k * (int) points;
            int p;
            for (p = 0; p < points; p++)
            {
                out[base + p] = cfxSurfaceBiomeAt(
                    ctx,
                    cellX[k] + (p % edge) * stride,
                    cellZ[k] + (p / edge) * stride,
                    terrainY[base + p]
                );
            }
        }
    }
    else
    {
        for (k = 0; k < n; k++)
        {
            const int err = cfxGenerateOverviewBiomes(
                ctx, cellX[k], cellZ[k], edge, edge, stride, out + k * (int) points
            );
            if (err != CFX_OK)
            {
                free(in);
                free(out);
                return err;
            }
        }
    }

    (*env)->SetIntArrayRegion(env, outBiomeIdsPackedArray, 0, cells, out);
    free(in);
    free(out);
    return CFX_OK;
}

/*
 * Batch variants of the two entries above (gpuCfxOverviewHeightsBatch /
 * gpuCfxSurfaceBiomesBatch): one native submission per wave of tiles instead
 * of one per tile. Every tile is validated up front against the same gates
 * the single entries apply before their GPU attempt; a tile outside them gets
 * its failMask bit and is skipped, so the caller repairs exactly those tiles
 * through the single entries. The rest is fed to the native batch in chunks
 * bounded by the engine's per-dispatch points cap - and, for heights, by one
 * shared CfxNoiseDetail (part of the chunk key because
 * cfxNoiseDetailForStride varies with the sampling stride) - with the
 * per-chunk failure bitmaps ORed together. Results are written straight into
 * the caller's flat Java arrays through GetPrimitiveArrayCritical: the
 * staging malloc/SetIntArrayRegion round trip of the single entries is
 * exactly the per-call overhead batching removes, and the stride < 4 macro
 * lattices are interpolated out of their slice through one reusable staging
 * buffer. A 0 return means the batch was attempted: failMaskOut[0] carries
 * the per-tile failures and every other slice is filled bit-exactly.
 * Nonzero refuses the whole batch before any output write (GPU compiled out,
 * mode off, dead engine, a chunk refused; reported negative as
 * -CFX_ERR_GENERATION) and the caller dispatches its tiles individually,
 * exactly like today's per-tile path. Caller-argument problems return the
 * same positive CFX_ERR_* codes as the single entries. n is capped at the
 * engine's 32-block batch limit, so only the points cap chunks.
 */

/* The engine's batch caps (gpu.c's GPU_BATCH_MAX / GPU_MAX_POINTS); gpu.h
 * exports no cap constants, so mirror them here and keep them in sync. */
#define CFX_BATCH_MAX_BLOCKS 32
#define CFX_BATCH_MAX_POINTS (1 << 20)

/* Macro-lattice geometry of a stride < 4 tile, mirroring the derivation in
 * cfxOverviewHeightsImpl. */
static void cfxMacroLattice(
    int blockX, int blockZ, int w, int h, int stride,
    int *minX4, int *minZ4, int *gridW, int *gridH
) {
    const int64_t lastX = (int64_t) blockX + (int64_t) (w - 1) * stride;
    const int64_t lastZ = (int64_t) blockZ + (int64_t) (h - 1) * stride;
    *minX4 = floordiv(blockX, 4);
    *minZ4 = floordiv(blockZ, 4);
    *gridW = floordiv((int) lastX, 4) - *minX4 + 2;
    *gridH = floordiv((int) lastZ, 4) - *minZ4 + 2;
}

/* The per-tile GPU shape gate of cfxOverviewHeightsImpl: *cellsOut receives
 * the lattice cost the native batch charges for an eligible tile (grid cells
 * for macro tiles, sample cells otherwise), or -1 when the tile cannot be
 * served by the batch kernels and the caller must repair it per tile. */
static void cfxBatchHeightCells(
    const int blockX, const int blockZ, const int w, const int h,
    const int stride, int *cellsOut
) {
    int cells = -1;
    if (cfxValidCells(w, h) && stride > 0)
    {
        const int64_t lastX = (int64_t) blockX + (int64_t) (w - 1) * stride;
        const int64_t lastZ = (int64_t) blockZ + (int64_t) (h - 1) * stride;
        if (lastX >= INT32_MIN && lastX <= INT32_MAX
            && lastZ >= INT32_MIN && lastZ <= INT32_MAX)
        {
            int minX4, minZ4, gridW, gridH;
            cfxMacroLattice(blockX, blockZ, w, h, stride,
                &minX4, &minZ4, &gridW, &gridH);
            if (stride < 4)
            {
                /* The lattice is solved into the tile's own output slice
                 * before the host-side interpolation overwrites it, so it
                 * must fit there. */
                if ((int64_t) gridW * gridH >= CFX_GPU_HEIGHT_MIN_CELLS
                    && (int64_t) gridW * gridH <= (int64_t) w * h)
                    cells = gridW * gridH;
            }
            else if (stride % 4 == 0
                && (int64_t) w * h >= CFX_GPU_HEIGHT_MIN_CELLS)
            {
                cells = w * h;
            }
        }
    }
    *cellsOut = cells;
}

JNIEXPORT jint JNICALL Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxOverviewHeightsCoarseBatch(
    JNIEnv *env, jclass clazz, jlong handle,
    jintArray blockXArray, jintArray blockZArray, jintArray wArray,
    jintArray hArray, jintArray strideArray, jint n,
    jintArray outTerrainYArray, jintArray failMaskArray
) {
    (void) clazz;
#ifdef CUBIOMES_ENABLE_GPU
    /* Metrics slot 13 opens here: everything up to the critical regions
     * below is the JNI marshal wall the engine never sees. */
    const uint64_t marshalT0 = cfxNanos();
#endif
    CfxContext *ctx = cfxHandle(handle);
    if (ctx == NULL)
        return CFX_ERR_BAD_HANDLE;
    if (ctx->dim != DIM_OVERWORLD)
        return CFX_ERR_WRONG_DIM;
    if (ctx->mc < MC_1_18)
        return CFX_ERR_BAD_ARGS;
    if (n <= 0 || n > CFX_BATCH_MAX_BLOCKS
        || (*env)->GetArrayLength(env, blockXArray) < n
        || (*env)->GetArrayLength(env, blockZArray) < n
        || (*env)->GetArrayLength(env, wArray) < n
        || (*env)->GetArrayLength(env, hArray) < n
        || (*env)->GetArrayLength(env, strideArray) < n
        || (*env)->GetArrayLength(env, failMaskArray) < 1)
        return CFX_ERR_BAD_SIZE;
#ifndef CUBIOMES_ENABLE_GPU
    /* No engine in this build: refuse and let the caller run its per-tile
     * path (the single entries serve CPU there), same as a dead engine. */
    return -CFX_ERR_GENERATION;
#else
    int *in = malloc(sizeof(int) * (size_t) n * 5);
    int *nativeCells = malloc(sizeof(int) * (size_t) n);
    int *detailPack = malloc(sizeof(int) * (size_t) n);
    if (in == NULL || nativeCells == NULL || detailPack == NULL)
    {
        free(in);
        free(nativeCells);
        free(detailPack);
        return CFX_ERR_ALLOC;
    }
    int *bx = in, *bz = in + n, *bw = in + 2 * n, *bh = in + 3 * n, *bs = in + 4 * n;
    (*env)->GetIntArrayRegion(env, blockXArray, 0, n, bx);
    (*env)->GetIntArrayRegion(env, blockZArray, 0, n, bz);
    (*env)->GetIntArrayRegion(env, wArray, 0, n, bw);
    (*env)->GetIntArrayRegion(env, hArray, 0, n, bh);
    (*env)->GetIntArrayRegion(env, strideArray, 0, n, bs);
    if ((*env)->ExceptionCheck(env))
    {
        free(in);
        free(nativeCells);
        free(detailPack);
        return CFX_ERR_BAD_SIZE;
    }

    int64_t totalOut = 0;
    int64_t maxMacroCells = 0;
    int eligibleCount = 0;
    int i;
    for (i = 0; i < n; i++)
    {
        int cells = -1;
        cfxBatchHeightCells(bx[i], bz[i], bw[i], bh[i], bs[i], &cells);
        if (cells > 0)
        {
            const CfxNoiseDetail detail =
                cfxNoiseDetailForStride(&ctx->g.bn.terrain, bs[i]);
            if (detail.mainFirst > 7 || detail.minMaxFirst > 15)
                cells = -1; /* outside the kernel's octave-argument range */
            else
                detailPack[i] = (detail.mainFirst << 6)
                    | (detail.minMaxFirst << 1) | detail.truncated;
        }
        nativeCells[i] = cells;
        if (cells > 0)
        {
            eligibleCount++;
            if (bs[i] < 4 && cells > maxMacroCells)
                maxMacroCells = cells;
        }
        totalOut += (int64_t) bw[i] * bh[i];
    }

    if ((int64_t) (*env)->GetArrayLength(env, outTerrainYArray) < totalOut)
    {
        free(in);
        free(nativeCells);
        free(detailPack);
        return CFX_ERR_BAD_SIZE;
    }

    int *macroTmp = NULL;
    if (maxMacroCells > 0)
    {
        macroTmp = malloc(sizeof(int) * (size_t) maxMacroCells);
        if (macroTmp == NULL)
        {
            /* Without staging memory the macro lattices cannot be turned
             * into tile heights; leave those tiles to the caller's repair. */
            eligibleCount = 0;
            for (i = 0; i < n; i++)
            {
                if (nativeCells[i] > 0)
                {
                    if (bs[i] < 4)
                        nativeCells[i] = -1;
                    else
                        eligibleCount++;
                }
            }
        }
    }

    int *outTerrainY = NULL;
    int *failMaskOut = NULL;
    jint status = CFX_OK;
    if (eligibleCount > 0)
        outTerrainY = (*env)->GetPrimitiveArrayCritical(env, outTerrainYArray, NULL);
    failMaskOut = (*env)->GetPrimitiveArrayCritical(env, failMaskArray, NULL);
#ifdef CUBIOMES_ENABLE_GPU
    cfxAddBatchMarshalNanos(cfxNanos() - marshalT0);
#endif
    if ((eligibleCount > 0 && outTerrainY == NULL) || failMaskOut == NULL)
    {
        status = CFX_ERR_ALLOC;
    }
    else
    {
        jint failMask = 0;
        int64_t outOff = 0;
        int j = 0;
        while (j < n)
        {
            if (nativeCells[j] <= 0)
            {
                failMask |= (jint) (1u << j);
                outOff += (int64_t) bw[j] * bh[j];
                j++;
                continue;
            }
            /* Greedy chunk bounded by the points cap and one shared detail. */
            int cx4[CFX_BATCH_MAX_BLOCKS], cz4[CFX_BATCH_MAX_BLOCKS];
            int cgw[CFX_BATCH_MAX_BLOCKS], cgh[CFX_BATCH_MAX_BLOCKS];
            int csx[CFX_BATCH_MAX_BLOCKS], csz[CFX_BATCH_MAX_BLOCKS];
            long cflat[CFX_BATCH_MAX_BLOCKS];
            int cmember[CFX_BATCH_MAX_BLOCKS];
            const int chunkDetail = detailPack[j];
            int count = 0;
            int64_t points = 0;
            while (j < n)
            {
                const int64_t sliceCells = (int64_t) bw[j] * bh[j];
                if (nativeCells[j] > 0)
                {
                    if (count == CFX_BATCH_MAX_BLOCKS
                        || points + nativeCells[j] > CFX_BATCH_MAX_POINTS
                        || detailPack[j] != chunkDetail)
                        break;
                    if (bs[j] < 4)
                    {
                        cfxMacroLattice(bx[j], bz[j], bw[j], bh[j], bs[j],
                            &cx4[count], &cz4[count], &cgw[count], &cgh[count]);
                        csx[count] = 1;
                        csz[count] = 1;
                    }
                    else
                    {
                        cx4[count] = floordiv(bx[j], 4);
                        cz4[count] = floordiv(bz[j], 4);
                        cgw[count] = bw[j];
                        cgh[count] = bh[j];
                        csx[count] = bs[j] / 4;
                        csz[count] = bs[j] / 4;
                    }
                    cflat[count] = (long) outOff;
                    cmember[count] = j;
                    count++;
                    points += nativeCells[j];
                }
                else
                {
                    failMask |= (jint) (1u << j);
                }
                outOff += sliceCells;
                j++;
            }
            int chunkFail = 0;
            if (gpuCfxOverviewHeightsBatch(&chunkFail, outTerrainY, cflat,
                    &ctx->g.bn, cx4, cz4, cgw, cgh, csx, csz, count,
                    chunkDetail >> 6, (chunkDetail >> 1) & 0xF,
                    chunkDetail & 1) != 0)
            {
                status = -CFX_ERR_GENERATION;
                break;
            }
            failMask |= (jint) chunkFail;
            ctx->stageCalls += count;
            int k;
            for (k = 0; k < count; k++)
            {
                const int t = cmember[k];
                if (chunkFail & (int) (1u << k))
                    continue;
                ctx->stageGpuCalls++;
                if (bs[t] < 4)
                {
                    int minX4, minZ4, gridW, gridH;
                    cfxMacroLattice(bx[t], bz[t], bw[t], bh[t], bs[t],
                        &minX4, &minZ4, &gridW, &gridH);
                    int *slice = outTerrainY + cflat[k];
                    memcpy(macroTmp, slice,
                        sizeof(int) * (size_t) gridW * (size_t) gridH);
                    cfxInterpolateMacro(slice, macroTmp,
                        bx[t], bz[t], bw[t], bh[t], bs[t], minX4, minZ4, gridW);
                }
            }
        }
        if (status == CFX_OK)
            *failMaskOut = failMask;
    }
    if (outTerrainY != NULL)
        (*env)->ReleasePrimitiveArrayCritical(env, outTerrainYArray, outTerrainY, 0);
    if (failMaskOut != NULL)
        (*env)->ReleasePrimitiveArrayCritical(env, failMaskArray, failMaskOut, 0);
    free(macroTmp);
    free(detailPack);
    free(nativeCells);
    free(in);
    return status;
#endif
}

/* The per-tile GPU gate of cfxSurfaceBiomes: *cellsOut receives the flat
 * slice size of an eligible tile, or -1 when it cannot be served by the
 * batch kernels and the caller must repair it per tile. */
static void cfxBatchBiomeCells(
    const int blockX, const int blockZ, const int w, const int h,
    const int stride, int *cellsOut
) {
    int cells = -1;
    if (cfxValidCells(w, h) && stride > 0
        && (int64_t) w * h >= CFX_GPU_BIOME_MIN_CELLS)
    {
        const int64_t lastX = (int64_t) blockX + (int64_t) (w - 1) * stride;
        const int64_t lastZ = (int64_t) blockZ + (int64_t) (h - 1) * stride;
        if (lastX >= INT32_MIN && lastX <= INT32_MAX
            && lastZ >= INT32_MIN && lastZ <= INT32_MAX)
            cells = w * h;
    }
    *cellsOut = cells;
}

JNIEXPORT jint JNICALL Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxSurfaceBiomesBatch(
    JNIEnv *env, jclass clazz, jlong handle, jlong sha,
    jintArray blockXArray, jintArray blockZArray, jintArray wArray,
    jintArray hArray, jintArray strideArray, jintArray terrainYArray, jint n,
    jintArray outBiomeIdsArray, jintArray failMaskArray
) {
    (void) clazz;
#ifdef CUBIOMES_ENABLE_GPU
    /* Metrics slot 13 opens here, same phase as the heights batch. */
    const uint64_t marshalT0 = cfxNanos();
#endif
    CfxContext *ctx = cfxHandle(handle);
    if (ctx == NULL)
        return CFX_ERR_BAD_HANDLE;
    if (ctx->dim != DIM_OVERWORLD)
        return CFX_ERR_WRONG_DIM;
    if (ctx->mc < MC_1_18)
        return CFX_ERR_BAD_ARGS;
    /* The voronoi salt is baked into the packed engine state, so a caller
     * probing a different generator must not borrow this handle's. */
    if ((uint64_t) sha != ctx->g.sha)
        return CFX_ERR_BAD_ARGS;
    if (n <= 0 || n > CFX_BATCH_MAX_BLOCKS
        || (*env)->GetArrayLength(env, blockXArray) < n
        || (*env)->GetArrayLength(env, blockZArray) < n
        || (*env)->GetArrayLength(env, wArray) < n
        || (*env)->GetArrayLength(env, hArray) < n
        || (*env)->GetArrayLength(env, strideArray) < n
        || (*env)->GetArrayLength(env, failMaskArray) < 1)
        return CFX_ERR_BAD_SIZE;
#ifndef CUBIOMES_ENABLE_GPU
    return -CFX_ERR_GENERATION;
#else
    int *in = malloc(sizeof(int) * (size_t) n * 5);
    int *nativeCells = malloc(sizeof(int) * (size_t) n);
    if (in == NULL || nativeCells == NULL)
    {
        free(in);
        free(nativeCells);
        return CFX_ERR_ALLOC;
    }
    int *bx = in, *bz = in + n, *bw = in + 2 * n, *bh = in + 3 * n, *bs = in + 4 * n;
    (*env)->GetIntArrayRegion(env, blockXArray, 0, n, bx);
    (*env)->GetIntArrayRegion(env, blockZArray, 0, n, bz);
    (*env)->GetIntArrayRegion(env, wArray, 0, n, bw);
    (*env)->GetIntArrayRegion(env, hArray, 0, n, bh);
    (*env)->GetIntArrayRegion(env, strideArray, 0, n, bs);
    if ((*env)->ExceptionCheck(env))
    {
        free(in);
        free(nativeCells);
        return CFX_ERR_BAD_SIZE;
    }

    int64_t totalOut = 0;
    int eligibleCount = 0;
    int i;
    for (i = 0; i < n; i++)
    {
        int cells = -1;
        cfxBatchBiomeCells(bx[i], bz[i], bw[i], bh[i], bs[i], &cells);
        nativeCells[i] = cells;
        if (cells > 0)
            eligibleCount++;
        totalOut += (int64_t) bw[i] * bh[i];
    }

    if ((int64_t) (*env)->GetArrayLength(env, terrainYArray) < totalOut
        || (int64_t) (*env)->GetArrayLength(env, outBiomeIdsArray) < totalOut)
    {
        free(in);
        free(nativeCells);
        return CFX_ERR_BAD_SIZE;
    }

    int *terrainY = NULL;
    int *outBiomeIds = NULL;
    int *failMaskOut = NULL;
    jint status = CFX_OK;
    if (eligibleCount > 0)
    {
        /* The flat terrainY planes upload through one EnqueueWriteBuffer per
         * native chunk, so they pin for the whole dispatch like the output. */
        terrainY = (*env)->GetPrimitiveArrayCritical(env, terrainYArray, NULL);
        outBiomeIds = (*env)->GetPrimitiveArrayCritical(env, outBiomeIdsArray, NULL);
    }
    failMaskOut = (*env)->GetPrimitiveArrayCritical(env, failMaskArray, NULL);
#ifdef CUBIOMES_ENABLE_GPU
    cfxAddBatchMarshalNanos(cfxNanos() - marshalT0);
#endif
    if ((eligibleCount > 0 && (terrainY == NULL || outBiomeIds == NULL))
        || failMaskOut == NULL)
    {
        status = CFX_ERR_ALLOC;
    }
    else
    {
        jint failMask = 0;
        int64_t outOff = 0;
        int j = 0;
        while (j < n)
        {
            if (nativeCells[j] <= 0)
            {
                failMask |= (jint) (1u << j);
                outOff += (int64_t) bw[j] * bh[j];
                j++;
                continue;
            }
            /* Greedy chunk bounded by the points cap. */
            int cbx[CFX_BATCH_MAX_BLOCKS], cbz[CFX_BATCH_MAX_BLOCKS];
            int cw[CFX_BATCH_MAX_BLOCKS], crows[CFX_BATCH_MAX_BLOCKS];
            int cst[CFX_BATCH_MAX_BLOCKS];
            long cflat[CFX_BATCH_MAX_BLOCKS];
            int count = 0;
            int64_t points = 0;
            while (j < n)
            {
                const int64_t sliceCells = (int64_t) bw[j] * bh[j];
                if (nativeCells[j] > 0)
                {
                    if (count == CFX_BATCH_MAX_BLOCKS
                        || points + nativeCells[j] > CFX_BATCH_MAX_POINTS)
                        break;
                    cbx[count] = bx[j];
                    cbz[count] = bz[j];
                    cw[count] = bw[j];
                    crows[count] = bh[j];
                    cst[count] = bs[j];
                    cflat[count] = (long) outOff;
                    count++;
                    points += nativeCells[j];
                }
                else
                {
                    failMask |= (jint) (1u << j);
                }
                outOff += sliceCells;
                j++;
            }
            int chunkFail = 0;
            if (gpuCfxSurfaceBiomesBatch(&chunkFail, outBiomeIds, cflat,
                    &ctx->g.bn, (uint64_t) sha, cbx, cbz, cw, crows, cst,
                    terrainY, count) != 0)
            {
                status = -CFX_ERR_GENERATION;
                break;
            }
            failMask |= (jint) chunkFail;
            ctx->stageCalls += count;
            int k;
            for (k = 0; k < count; k++)
            {
                if ((chunkFail & (int) (1u << k)) == 0)
                    ctx->stageGpuCalls++;
            }
        }
        if (status == CFX_OK)
            *failMaskOut = failMask;
    }
    if (terrainY != NULL)
        (*env)->ReleasePrimitiveArrayCritical(env, terrainYArray, terrainY, JNI_ABORT);
    if (outBiomeIds != NULL)
        (*env)->ReleasePrimitiveArrayCritical(env, outBiomeIdsArray, outBiomeIds, 0);
    if (failMaskOut != NULL)
        (*env)->ReleasePrimitiveArrayCritical(env, failMaskArray, failMaskOut, 0);
    free(nativeCells);
    free(in);
    return status;
#endif
}

/* The voronoi salt the batch biome entry validates its caller probe against,
 * read straight out of the context's generator - no recomputation here. The
 * Java wave leader probes its own context once and passes the value with the
 * batch, so a stale probe from another generator is refused, never borrowed. */
JNIEXPORT jlong JNICALL Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxVoronoiSHA(
    JNIEnv *env, jclass clazz, jlong handle
) {
    (void) env;
    (void) clazz;
    CfxContext *ctx = cfxHandle(handle);
    if (ctx == NULL)
        return 0;
    return (jlong) ctx->g.sha;
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

static int cfxIsViableStructure(
    CfxContext *ctx,
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
    CFX_VARIANT_END_CITY_SHIP = 1,
    CFX_VARIANT_IGLOO_BASEMENT = 1,
    CFX_VARIANT_SHIPWRECK_BEACHED = 1,
    CFX_VARIANT_VILLAGE_ZOMBIE = 1 << 3,
    CFX_VARIANT_RUINED_PORTAL_GIANT = 1
};

static int cfxStructureBiome(CfxContext *ctx, int structType, int blockX, int blockZ) {
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

static int cfxStructureVariant(CfxContext *ctx, int structType, int blockX, int blockZ) {
    if (structType == End_City) {
        Piece pieces[END_CITY_PIECES_MAX];
        const int count = getEndCityPieces(
            pieces, ctx->g.seed, blockX >> 4, blockZ >> 4
        );
        for (int index = 0; index < count; index++) {
            if (pieces[index].type == END_SHIP) {
                return CFX_VARIANT_END_CITY_SHIP;
            }
        }
        return 0;
    }

    int biome = -1;
    if (structType == Village || structType == Shipwreck
        || structType == Ruined_Portal || structType == Ruined_Portal_N) {
        biome = cfxStructureBiome(ctx, structType, blockX, blockZ);
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
        return type | (variant.abandoned ? CFX_VARIANT_VILLAGE_ZOMBIE : 0);
    }
    case Igloo:
        return variant.basement ? CFX_VARIANT_IGLOO_BASEMENT : 0;
    case Shipwreck:
        return isOceanic(variant.biome) ? 0 : CFX_VARIANT_SHIPWRECK_BEACHED;
    case Bastion:
        return variant.start & 3;
    case Ruined_Portal:
    case Ruined_Portal_N:
        return variant.giant ? CFX_VARIANT_RUINED_PORTAL_GIANT : 0;
    default:
        return 0;
    }
}

JNIEXPORT jint JNICALL Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxViableStructures(
    JNIEnv *env, jclass clazz, jlong handle, jint structType, jint regX0, jint regZ0, jint regX1, jint regZ1,
    jlongArray out, jintArray outVariants, jint cap
) {
    (void) clazz;
    CfxContext *ctx = cfxHandle(handle);
    if (ctx == NULL) {
        return CFX_ERR_BAD_HANDLE;
    }
    if (structType < 0 || structType >= FEATURE_NUM || structType == Stronghold) {
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
    const jsize variantLen = (*env)->GetArrayLength(env, outVariants);
    if (effectiveCap > outLen) {
        effectiveCap = outLen;
    }
    if (effectiveCap > variantLen) {
        effectiveCap = variantLen;
    }
    if (effectiveCap <= 0) {
        return 0;
    }

    jlong *packed = malloc(sizeof(jlong) * (size_t) effectiveCap);
    jint *variants = malloc(sizeof(jint) * (size_t) effectiveCap);
    if (packed == NULL || variants == NULL) {
        free(packed);
        free(variants);
        return CFX_ERR_ALLOC;
    }

    int found = 0;
    for (int regZ = regZ0; regZ <= regZ1 && found < effectiveCap; regZ++) {
        for (int regX = regX0; regX <= regX1 && found < effectiveCap; regX++) {
            Pos pos;
            if (getStructurePos(structType, ctx->mc, ctx->g.seed, regX, regZ, &pos)
                && cfxIsViableStructure(ctx, structType, pos.x, pos.z)) {
                packed[found] = ((jlong) pos.x << 32) | ((jlong) pos.z & 0xffffffffL);
                variants[found] = cfxStructureVariant(ctx, structType, pos.x, pos.z);
                found++;
            }
        }
    }

    (*env)->SetLongArrayRegion(env, out, 0, found, packed);
    (*env)->SetIntArrayRegion(env, outVariants, 0, found, variants);
    free(packed);
    free(variants);
    return found;
}

JNIEXPORT jint JNICALL Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxStrongholds(
    JNIEnv *env, jclass clazz, jlong handle, jlongArray out, jint cap
) {
    (void) clazz;
    CfxContext *ctx = cfxHandle(handle);
    if (ctx == NULL) {
        return CFX_ERR_BAD_HANDLE;
    }
    if (ctx->dim != DIM_OVERWORLD || cap < 0) {
        return CFX_ERR_BAD_ARGS;
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
    StrongholdIter iterator;
    initFirstStronghold(&iterator, ctx->mc, ctx->g.seed);
    int found = 0;
    int remaining;
    do {
        remaining = nextStronghold(&iterator, &ctx->g);
        packed[found++] = ((jlong) iterator.pos.x << 32) | ((jlong) iterator.pos.z & 0xffffffffL);
    } while (remaining > 0 && found < effectiveCap);

    (*env)->SetLongArrayRegion(env, out, 0, found, packed);
    free(packed);
    return found;
}

static void cfxTryNearestStructure(
    CfxContext *ctx,
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
        || !cfxIsViableStructure(ctx, structType, pos.x, pos.z)) {
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

JNIEXPORT jint JNICALL Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxNearestStructure(
    JNIEnv *env, jclass clazz, jlong handle, jint structType, jint blockX, jint blockZ,
    jint maxRadius, jlongArray out, jintArray outVariant
) {
    (void) clazz;
    CfxContext *ctx = cfxHandle(handle);
    if (ctx == NULL) {
        return CFX_ERR_BAD_HANDLE;
    }
    if (structType < 0 || structType >= FEATURE_NUM || maxRadius <= 0
        || (*env)->GetArrayLength(env, out) < 1
        || (*env)->GetArrayLength(env, outVariant) < 1) {
        return CFX_ERR_BAD_ARGS;
    }

    const int64_t maxDistanceSquared = (int64_t) maxRadius * maxRadius;
    int64_t bestDistanceSquared = 0;
    Pos best = {0, 0};
    int found = 0;

    if (structType == Stronghold) {
        if (ctx->dim != DIM_OVERWORLD) {
            return 0;
        }
        StrongholdIter iterator;
        initFirstStronghold(&iterator, ctx->mc, ctx->g.seed);
        int remaining;
        do {
            remaining = nextStronghold(&iterator, &ctx->g);
            const int64_t dx = (int64_t) iterator.pos.x - blockX;
            const int64_t dz = (int64_t) iterator.pos.z - blockZ;
            const int64_t distanceSquared = dx * dx + dz * dz;
            if (distanceSquared <= maxDistanceSquared
                && (!found || distanceSquared < bestDistanceSquared)) {
                bestDistanceSquared = distanceSquared;
                best = iterator.pos;
                found = 1;
            }
        } while (remaining > 0);
    } else {
        StructureConfig config;
        if (!getStructureConfig(structType, ctx->mc, &config) || config.regionSize <= 0) {
            return 0;
        }
        const int regionBlocks = config.regionSize << 4;
        const int centerRegX = floordiv(blockX, regionBlocks);
        const int centerRegZ = floordiv(blockZ, regionBlocks);
        const int maxRing = maxRadius / regionBlocks + 2;
        for (int ring = 0; ring <= maxRing; ring++) {
            if (ring == 0) {
                cfxTryNearestStructure(
                    ctx, structType, centerRegX, centerRegZ, blockX, blockZ,
                    maxDistanceSquared, &bestDistanceSquared, &best, &found
                );
            } else {
                for (int offset = -ring; offset <= ring; offset++) {
                    cfxTryNearestStructure(
                        ctx, structType, centerRegX + offset, centerRegZ - ring, blockX, blockZ,
                        maxDistanceSquared, &bestDistanceSquared, &best, &found
                    );
                    cfxTryNearestStructure(
                        ctx, structType, centerRegX + offset, centerRegZ + ring, blockX, blockZ,
                        maxDistanceSquared, &bestDistanceSquared, &best, &found
                    );
                }
                for (int offset = -ring + 1; offset < ring; offset++) {
                    cfxTryNearestStructure(
                        ctx, structType, centerRegX - ring, centerRegZ + offset, blockX, blockZ,
                        maxDistanceSquared, &bestDistanceSquared, &best, &found
                    );
                    cfxTryNearestStructure(
                        ctx, structType, centerRegX + ring, centerRegZ + offset, blockX, blockZ,
                        maxDistanceSquared, &bestDistanceSquared, &best, &found
                    );
                }
            }
            const int64_t unscannedDistance = (int64_t) ring * regionBlocks;
            if (found && unscannedDistance * unscannedDistance > bestDistanceSquared) {
                break;
            }
        }
    }

    if (!found) {
        return 0;
    }
    const jlong packed = ((jlong) best.x << 32) | ((jlong) best.z & 0xffffffffL);
    const jint variant = cfxStructureVariant(ctx, structType, best.x, best.z);
    (*env)->SetLongArrayRegion(env, out, 0, 1, &packed);
    (*env)->SetIntArrayRegion(env, outVariant, 0, 1, &variant);
    return 1;
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
    return cfxIsViableStructure(ctx, structType, blockX, blockZ) != 0 ? 1 : 0;
}

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

static int cfxWindowsEfficiencyClasses(JNIEnv *env, jintArray out) {
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

JNIEXPORT jint JNICALL
Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxCpuEfficiencyClasses(
    JNIEnv *env, jclass clazz, jintArray outClasses
) {
    (void) clazz;
    return cfxWindowsEfficiencyClasses(env, outClasses);
}

#else /* !_WIN32 */

JNIEXPORT jint JNICALL
Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxCpuEfficiencyClasses(
    JNIEnv *env, jclass clazz, jintArray outClasses
) {
    (void) env;
    (void) clazz;
    (void) outClasses;
    return 0;
}

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

static void cfxCpuid(unsigned int leaf, unsigned int subleaf, unsigned int out[4]) {
    __asm__ volatile ("cpuid"
        : "=a"(out[0]), "=b"(out[1]), "=c"(out[2]), "=d"(out[3])
        : "a"(leaf), "c"(subleaf));
}

static int cfxLinuxHybridCoreTypes(JNIEnv *env, jintArray out) {
    unsigned int info[4];
    cfxCpuid(0, 0, info);
    if (info[0] < 0x1A) {
        return 0;
    }
    cfxCpuid(7, 0, info);
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
        cfxCpuid(0x1A, 0, info);
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

JNIEXPORT jint JNICALL
Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxCpuHybridCoreTypes(
    JNIEnv *env, jclass clazz, jintArray outTypes
) {
    (void) clazz;
    return cfxLinuxHybridCoreTypes(env, outTypes);
}

#else /* !(__linux__ && __x86_64__) */

JNIEXPORT jint JNICALL
Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxCpuHybridCoreTypes(
    JNIEnv *env, jclass clazz, jintArray outTypes
) {
    (void) env;
    (void) clazz;
    (void) outTypes;
    return 0;
}

#endif /* __linux__ && __x86_64__ */
