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

#define CFX_ABI 3

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
    SurfaceNoise sn; /* Overworld: feeds mapApproxHeight. End: feeds mapEndSurfaceHeight. */
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
    const int rawWidth = (int) rawWidth64;
    int *sampled = malloc(sizeof(int) * (size_t) w * (size_t) h);
    if (sampled == NULL) {
        return CFX_ERR_ALLOC;
    }

    if (scale == 1) {
        /* The final Voronoi layer is not query-shape invariant for one-row
         * rectangles: splitting a 2D area into h independent rows can choose
         * different parent cells and produces horizontal biome stripes. Both
         * production scale-1 requests fit under the batch cap (258x258 for
         * LOD0, 515x515 before selecting stride 2 for LOD1), so generate their
         * full 2D source rectangle before taking the strided cells. */
        if (rawWidth64 * rawHeight64 > CFX_MAX_CELLS) {
            free(sampled);
            return CFX_ERR_BAD_SIZE;
        }
        const int rawHeight = (int) rawHeight64;
        const Range r = { scale, x, z, rawWidth, rawHeight, 0, 1 };
        int *dense = allocCache(&ctx->g, r);
        if (dense == NULL) {
            free(sampled);
            return CFX_ERR_ALLOC;
        }
        const int err = cfxGenerateBiomes(ctx, dense, r);
        if (err != 0) {
            free(dense);
            free(sampled);
            return CFX_ERR_GENERATION;
        }
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                sampled[j * w + i] = dense[(j * stride) * rawWidth + i * stride];
            }
        }
        free(dense);
    } else {
        for (int j = 0; j < h; j++) {
            const Range r = { scale, x, z + j * stride, rawWidth, 1, 0, 1 };
            int *row = allocCache(&ctx->g, r);
            if (row == NULL) {
                free(sampled);
                return CFX_ERR_ALLOC;
            }
            const int err = cfxGenerateBiomes(ctx, row, r);
            if (err != 0) {
                free(row);
                free(sampled);
                return CFX_ERR_GENERATION;
            }
            for (int i = 0; i < w; i++) {
                sampled[j * w + i] = row[i * stride];
            }
            free(row);
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
