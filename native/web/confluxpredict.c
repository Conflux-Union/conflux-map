/* Browser-only cubiomes wrapper. A dedicated Web Worker owns this generator. */
#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "generator.h"

#define CFX_WEB_OK 0
#define CFX_WEB_BAD_STATE 1
#define CFX_WEB_BAD_ARGS 3
#define CFX_WEB_ALLOC 4
#define CFX_WEB_GENERATION 5
#define CFX_WEB_WRONG_DIM 6
#define CFX_WEB_NETHER_ROOF_Y 127
#define CFX_WEB_TILE_SIZE 256
#define CFX_WEB_MARGIN 1
#define CFX_WEB_GRID_SIZE (CFX_WEB_TILE_SIZE + 2 * CFX_WEB_MARGIN)
#define CFX_WEB_GRID_CELLS (CFX_WEB_GRID_SIZE * CFX_WEB_GRID_SIZE)
#define CFX_WEB_WATER_LEVEL 62
#define CFX_WEB_SURFACE_WATER 1
#define CFX_WEB_SURFACE_VOID 2
#define CFX_WEB_OVERVIEW_ITERATIONS 2
#define CFX_WEB_CANOPY_SALT UINT64_C(0x27220A5FA9A9A797)
#define CFX_WEB_CLEARING_SALT UINT64_C(0x6A09E667F3BCC909)
#define CFX_WEB_HEIGHT_SALT UINT64_C(0xBB67AE8584CAA73B)
#define CFX_WEB_MIX_X UINT64_C(0x9E3779B185EBCA87)
#define CFX_WEB_MIX_Z UINT64_C(0xC2B2AE3D27D4EB4F)

static Generator generator;
static SurfaceNoise surfaceNoise;
static int mc;
static int dimension;
static int biomeData[CFX_WEB_GRID_CELLS];
static int heightData[CFX_WEB_GRID_CELLS];
static int surfaceData[CFX_WEB_GRID_CELLS];
static int canopyData[CFX_WEB_GRID_CELLS];
static int subBiomeData[CFX_WEB_GRID_CELLS * 4];
static int subSurfaceData[CFX_WEB_GRID_CELLS * 4];
static int subCanopyData[CFX_WEB_GRID_CELLS * 4];
static uint64_t worldSeed;
static int ready;

static int cfxWebGenerateBiomesStrided(
    int scale, int x, int z, int w, int h, int stride, int *sampled
);
static int cfxWebIsWaterBiome(int biome);

static uint64_t cfxWebSplitmix64(uint64_t input)
{
    uint64_t x = input + UINT64_C(0x9E3779B97F4A7C15);
    x = (x ^ (x >> 30)) * UINT64_C(0xBF58476D1CE4E5B9);
    x = (x ^ (x >> 27)) * UINT64_C(0x94D049BB133111EB);
    return x ^ (x >> 31);
}

static uint64_t cfxWebCanopyHash(uint64_t seed, int blockX, int blockZ)
{
    uint64_t hash = cfxWebSplitmix64(seed ^ CFX_WEB_CANOPY_SALT);
    hash = cfxWebSplitmix64(
        hash ^ (uint64_t) (int64_t) blockX * CFX_WEB_MIX_X
    );
    return cfxWebSplitmix64(
        hash ^ (uint64_t) (int64_t) blockZ * CFX_WEB_MIX_Z
    );
}

static int cfxWebBernoulli(uint64_t hash, double probability)
{
    const uint64_t threshold = (uint64_t) (probability * 4294967296.0);
    return (hash & UINT64_C(0xFFFFFFFF)) < threshold;
}

static int cfxWebHeightValue(uint64_t seed, int cellX, int cellZ)
{
    return (int) ((cfxWebCanopyHash(
        seed ^ CFX_WEB_HEIGHT_SALT, cellX, cellZ
    ) >> 40) & 3);
}

static int cfxWebLerpCanopyHeight(int from, int to, int offset)
{
    return from + floordiv((to - from) * offset, 4);
}

static int cfxWebSmoothCanopyHeight(uint64_t seed, int blockX, int blockZ)
{
    const int cellX = floordiv(blockX, 4);
    const int cellZ = floordiv(blockZ, 4);
    const int fx = blockX - cellX * 4;
    const int fz = blockZ - cellZ * 4;
    const int top = cfxWebLerpCanopyHeight(
        cfxWebHeightValue(seed, cellX, cellZ),
        cfxWebHeightValue(seed, cellX + 1, cellZ), fx
    );
    const int bottom = cfxWebLerpCanopyHeight(
        cfxWebHeightValue(seed, cellX, cellZ + 1),
        cfxWebHeightValue(seed, cellX + 1, cellZ + 1), fx
    );
    return cfxWebLerpCanopyHeight(top, bottom, fz);
}

static int cfxWebInsideCanopyBlob(
    uint64_t seed, int blockX, int blockZ, double coverage
)
{
    if (coverage <= 0.0)
        return 0;
    const int cellX = floordiv(blockX, 4);
    const int cellZ = floordiv(blockZ, 4);
    double probability = coverage * 16.0 / 9.0;
    if (probability > 1.0)
        probability = 1.0;
    int dz, dx;
    for (dz = -1; dz <= 1; dz++)
        for (dx = -1; dx <= 1; dx++)
        {
            const int originX = (cellX + dx) * 4;
            const int originZ = (cellZ + dz) * 4;
            const uint64_t hash = cfxWebCanopyHash(seed, originX, originZ);
            if (!cfxWebBernoulli(hash, probability))
                continue;
            const int radius = 1 + (int) ((hash >> 40) & 1);
            const int centerX = originX + (int) ((hash >> 41) & 3);
            const int centerZ = originZ + (int) ((hash >> 43) & 3);
            const int ddx = blockX - centerX;
            const int ddz = blockZ - centerZ;
            if (ddx * ddx + ddz * ddz <= radius * radius)
                return 1;
        }
    return 0;
}

static int cfxWebCanopyBump(
    uint64_t seed, int blockX, int blockZ, int lod, double treeCover
)
{
    if (lod >= 2)
        return cfxWebBernoulli(
            cfxWebCanopyHash(seed, blockX, blockZ), treeCover
        ) ? 2 : 0;
    const int dense = treeCover >= 0.5;
    const int foliage = dense
        ? !cfxWebInsideCanopyBlob(
            seed ^ CFX_WEB_CLEARING_SALT, blockX, blockZ, 1.0 - treeCover
        )
        : cfxWebInsideCanopyBlob(seed, blockX, blockZ, treeCover);
    if (!foliage)
        return 0;
    return (treeCover >= 0.75 ? 7 : 3)
        + cfxWebSmoothCanopyHeight(seed, blockX, blockZ);
}

static double cfxWebTreeCover(int biome)
{
    switch (biome)
    {
        case 1: case 129: case 177: return 0.02;
        case 3: case 35: case 36: case 131: case 162: case 163: case 164:
            return 0.05;
        case 175: return 0.12;
        case 6: case 38: case 134: case 166: return 0.15;
        case 5: case 19: case 30: case 31: case 133: case 158: return 0.2;
        case 178: return 0.22;
        case 34: return 0.25;
        case 23: case 151: case 185: return 0.3;
        case 4: case 18: case 27: case 28: case 132: case 155: case 156:
            return 0.35;
        case 32: case 33: case 160: case 161: return 0.4;
        case 184: case 186: return 0.45;
        case 29: case 157: return 0.5;
        case 21: case 22: case 149: case 168: case 169: return 0.9;
        default: return 0.0;
    }
}

static void cfxWebGenerateCanopy(int blockX, int blockZ, int lod)
{
    const int blockStride = 1 << lod;
    const int originX = blockX - blockStride;
    const int originZ = blockZ - blockStride;
    int z, x;
    memset(canopyData, 0, sizeof(canopyData));
    for (z = 0; z < CFX_WEB_GRID_SIZE; z++)
        for (x = 0; x < CFX_WEB_GRID_SIZE; x++)
        {
            const int index = z*CFX_WEB_GRID_SIZE+x;
            const int biome = biomeData[index];
            const double treeCover = cfxWebTreeCover(biome);
            if ((surfaceData[index] & (CFX_WEB_SURFACE_WATER | CFX_WEB_SURFACE_VOID))
                || biome == 10 || biome == 11 || biome == 50 || treeCover <= 0.0)
                continue;
            canopyData[index] = cfxWebCanopyBump(
                worldSeed, originX + x*blockStride, originZ + z*blockStride,
                lod, treeCover
            );
        }
}

static int cfxWebGenerateSubSamples(int blockX, int blockZ, int lod)
{
    memset(subBiomeData, 0, sizeof(subBiomeData));
    memset(subSurfaceData, 0, sizeof(subSurfaceData));
    memset(subCanopyData, 0, sizeof(subCanopyData));
    if (lod < 3 || dimension == DIM_END)
        return CFX_WEB_OK;
    const int blockStride = 1 << lod;
    const int subStride = blockStride / 2;
    const int originX = blockX - blockStride;
    const int originZ = blockZ - blockStride;

    if (dimension == DIM_OVERWORLD && mc >= MC_1_18)
    {
        int z, x, subZ, subX;
        for (z = 0; z < CFX_WEB_GRID_SIZE; z++)
            for (x = 0; x < CFX_WEB_GRID_SIZE; x++)
            {
                const int index = z*CFX_WEB_GRID_SIZE+x;
                const int y = heightData[index] < CFX_WEB_WATER_LEVEL
                    ? CFX_WEB_WATER_LEVEL : heightData[index];
                for (subZ = 0; subZ < 2; subZ++)
                    for (subX = 0; subX < 2; subX++)
                    {
                        int x4, y4, z4;
                        const int sampleX = originX + x*blockStride + subX*subStride;
                        const int sampleZ = originZ + z*blockStride + subZ*subStride;
                        voronoiAccess3D(
                            generator.sha, sampleX, y, sampleZ, &x4, &y4, &z4
                        );
                        subBiomeData[index*4+subZ*2+subX] = sampleBiomeNoise(
                            &generator.bn, NULL, x4, y4, z4, NULL, 0
                        );
                    }
            }
    }
    else
    {
        const int width = CFX_WEB_GRID_SIZE * 2;
        const int scale = subStride < 4 ? 1 : 4;
        int *raw = malloc(sizeof(int) * (size_t) width * width);
        if (raw == NULL)
            return CFX_WEB_ALLOC;
        const int err = cfxWebGenerateBiomesStrided(
            scale, floordiv(originX, scale), floordiv(originZ, scale),
            width, width, subStride / scale, raw
        );
        if (err != CFX_WEB_OK)
        {
            free(raw);
            return err;
        }
        int z, x, subZ, subX;
        for (z = 0; z < CFX_WEB_GRID_SIZE; z++)
            for (x = 0; x < CFX_WEB_GRID_SIZE; x++)
            {
                const int index = z*CFX_WEB_GRID_SIZE+x;
                for (subZ = 0; subZ < 2; subZ++)
                    for (subX = 0; subX < 2; subX++)
                        subBiomeData[index*4+subZ*2+subX] =
                            raw[(z*2+subZ)*width+x*2+subX];
            }
        free(raw);
    }

    int index, sample;
    for (index = 0; index < CFX_WEB_GRID_CELLS; index++)
        for (sample = 0; sample < 4; sample++)
        {
            const int output = index*4+sample;
            const int biome = subBiomeData[output];
            const int water = dimension == DIM_OVERWORLD
                && heightData[index] < CFX_WEB_WATER_LEVEL
                && (heightData[index] < CFX_WEB_WATER_LEVEL - 1
                    || cfxWebIsWaterBiome(biome));
            int depth = water ? CFX_WEB_WATER_LEVEL - heightData[index] : 0;
            if (depth < 0) depth = 0;
            if (depth > 255) depth = 255;
            subSurfaceData[output] = (water ? CFX_WEB_SURFACE_WATER : 0)
                | (depth << 8);
            const double treeCover = cfxWebTreeCover(biome);
            if (water || biome == 10 || biome == 11 || biome == 50
                || treeCover <= 0.0)
                continue;
            const int pixelX = index % CFX_WEB_GRID_SIZE;
            const int pixelZ = index / CFX_WEB_GRID_SIZE;
            const int subX = sample & 1;
            const int subZ = sample >> 1;
            subCanopyData[output] = cfxWebCanopyBump(
                worldSeed,
                originX + pixelX*blockStride + subX*subStride,
                originZ + pixelZ*blockStride + subZ*subStride,
                lod, treeCover
            );
        }
    return CFX_WEB_OK;
}

static int cfxWebGenerateBiomesStrided(
    int scale, int x, int z, int w, int h, int stride, int *sampled
)
{
    const int64_t rawWidth64 = (int64_t) (w - 1) * stride + 1;
    const int64_t rawHeight64 = (int64_t) (h - 1) * stride + 1;
    if (rawWidth64 <= 0 || rawWidth64 > (1 << 20)
        || rawHeight64 <= 0 || rawHeight64 > (1 << 20))
        return CFX_WEB_BAD_ARGS;
    const int rawWidth = (int) rawWidth64;

    if (scale == 1)
    {
        if (rawWidth64 * rawHeight64 > (1 << 20))
            return CFX_WEB_BAD_ARGS;
        const int rawHeight = (int) rawHeight64;
        Range range = {scale, x, z, rawWidth, rawHeight, 0, 1};
        int *dense = allocCache(&generator, range);
        if (dense == NULL)
            return CFX_WEB_ALLOC;
        if (dimension == DIM_NETHER)
            range.y = CFX_WEB_NETHER_ROOF_Y;
        const int err = genBiomes(&generator, dense, range);
        if (err != 0)
        {
            free(dense);
            return CFX_WEB_GENERATION;
        }
        int j, i;
        for (j = 0; j < h; j++)
            for (i = 0; i < w; i++)
                sampled[j*w+i] = dense[(j*stride)*rawWidth + i*stride];
        free(dense);
        return CFX_WEB_OK;
    }

    int j;
    for (j = 0; j < h; j++)
    {
        Range range = {scale, x, z + j*stride, rawWidth, 1, 0, 1};
        if (dimension == DIM_NETHER)
            range.y = CFX_WEB_NETHER_ROOF_Y / 4;
        int *row = allocCache(&generator, range);
        if (row == NULL)
            return CFX_WEB_ALLOC;
        const int err = genBiomes(&generator, row, range);
        if (err != 0)
        {
            free(row);
            return CFX_WEB_GENERATION;
        }
        int i;
        for (i = 0; i < w; i++)
            sampled[j*w+i] = row[i*stride];
        free(row);
    }
    return CFX_WEB_OK;
}

static double cfxWebMaintainPrecision(double value)
{
    return value - floor(value / 33554432.0 + 0.5) * 33554432.0;
}

static float cfxWebPeaksAndValleys(float weirdness)
{
    return -(fabsf(fabsf(weirdness) - 0.6666667F) - 0.33333334F) * 3.0F;
}

static void cfxWebTerrainParameters(
    const BiomeNoise *noise, int cellX, int cellZ, float values[4]
)
{
    double px = cellX;
    double pz = cellZ;
    px += sampleDoublePerlin(&noise->climate[NP_SHIFT], cellX, 0, cellZ) * 4.0;
    pz += sampleDoublePerlin(&noise->climate[NP_SHIFT], cellZ, cellX, 0) * 4.0;
    const float weirdness = sampleDoublePerlin(
        &noise->climate[NP_WEIRDNESS], px, 0, pz
    );
    values[SP_CONTINENTALNESS] = sampleDoublePerlin(
        &noise->climate[NP_CONTINENTALNESS], px, 0, pz
    );
    values[SP_EROSION] = sampleDoublePerlin(
        &noise->climate[NP_EROSION], px, 0, pz
    );
    values[SP_RIDGES] = cfxWebPeaksAndValleys(weirdness);
    values[SP_WEIRDNESS] = weirdness;
}

static double cfxWebModernTerrainNoise(
    const SurfaceNoise *noise, int x, int y, int z
)
{
    const double scaledXz = 684.412 * noise->xzScale;
    const double scaledY = 684.412 * noise->yScale;
    const double dx = x * scaledXz;
    const double dy = y * scaledY;
    const double dz = z * scaledXz;
    const double mainX = dx / noise->xzFactor;
    const double mainY = dy / noise->yFactor;
    const double mainZ = dz / noise->xzFactor;
    const double smearY = scaledY * 8.0;
    const double mainSmearY = smearY / noise->yFactor;
    double interpolation = 0.0;
    double frequency = 1.0;
    int i;

    for (i = 0; i < 8; i++)
    {
        interpolation += samplePerlin(&noise->octmain.octaves[i],
            cfxWebMaintainPrecision(mainX * frequency),
            cfxWebMaintainPrecision(mainY * frequency),
            cfxWebMaintainPrecision(mainZ * frequency),
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
        const double sx = cfxWebMaintainPrecision(dx * frequency);
        const double sy = cfxWebMaintainPrecision(dy * frequency);
        const double sz = cfxWebMaintainPrecision(dz * frequency);
        const double yScale = smearY * frequency;
        if (blend < 1.0)
            lower += samplePerlin(&noise->octmin.octaves[i],
                sx, sy, sz, yScale, dy * frequency) / frequency;
        if (blend > 0.0)
            upper += samplePerlin(&noise->octmax.octaves[i],
                sx, sy, sz, yScale, dy * frequency) / frequency;
        frequency *= 0.5;
    }
    return clampedLerp(blend, lower / 512.0, upper / 512.0) / 128.0;
}

static int cfxWebModernOverviewHeight(int x4, int z4)
{
    const BiomeNoise *noise = &generator.bn;
    float values[4];
    cfxWebTerrainParameters(noise, x4, z4, values);
    const double offset = -0.50375F + getSpline(noise->sp, values);
    const double factor = getSpline(noise->factorSp, values);
    const double jaggedness = getSpline(noise->jaggedSp, values);
    double jagged = sampleDoublePerlin(
        &noise->jagged, x4 * 4 * 1500.0, 0.0, z4 * 4 * 1500.0
    );
    if (jagged < 0.0)
        jagged *= 0.5;
    jagged *= jaggedness;

    const double base = 1.0 + offset + jagged;
    double y = 128.0 * base;
    if (factor <= 1e-9)
        return (int) floor(y);
    int i;
    for (i = 0; i < CFX_WEB_OVERVIEW_ITERATIONS; i++)
    {
        const double terrain = cfxWebModernTerrainNoise(
            &noise->terrain, x4 * 4, (int) floor(y), z4 * 4
        );
        const double above = 128.0 * (base + terrain / factor);
        const double below = 128.0 * (base + terrain / (4.0 * factor));
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

static int cfxWebLegacyOverviewHeight(int biome, int blockX, int blockZ)
{
    double biomeDepth, biomeScale;
    getBiomeDepthAndScale(biome, &biomeDepth, &biomeScale, NULL);
    (void) biomeScale;
    const double blendedDepth = ((biomeDepth * 4.0 - 1.0) / 8.0)
        * (17.0 / 64.0);
    const double quartX = blockX / 4.0;
    const double quartZ = blockZ / 4.0;
    double offset = sampleOctaveAmp(
        &surfaceNoise.octdepth, quartX * 200.0, 10.0, quartZ * 200.0,
        1.0, 0.0, 1.0
    );
    offset *= 65535.0 / 8000.0;
    if (offset < 0.0)
        offset *= -0.3;
    offset = offset * 3.0 - 2.0;
    if (offset > 1.0)
        offset = 1.0;
    offset *= 17.0 / 64.0;
    const double densityOffset = offset < 0.0 ? offset / 28.0 : offset / 40.0;
    return (int) floor(128.0 * (0.53125 + densityOffset + blendedDepth));
}

static int cfxWebOverviewHeights(
    int blockX, int blockZ, int w, int h, int stride, int *out
)
{
    if (mc >= MC_1_18)
    {
        if (stride < 4)
        {
            const int lastX = blockX + (w - 1) * stride;
            const int lastZ = blockZ + (h - 1) * stride;
            const int minX4 = floordiv(blockX, 4);
            const int minZ4 = floordiv(blockZ, 4);
            const int maxX4 = floordiv(lastX, 4) + 1;
            const int maxZ4 = floordiv(lastZ, 4) + 1;
            const int gridW = maxX4 - minX4 + 1;
            const int gridH = maxZ4 - minZ4 + 1;
            int *macro = malloc(sizeof(int) * (size_t) gridW * gridH);
            if (macro == NULL)
                return CFX_WEB_ALLOC;
            int j, i;
            for (j = 0; j < gridH; j++)
                for (i = 0; i < gridW; i++)
                    macro[j*gridW+i] = cfxWebModernOverviewHeight(
                        minX4 + i, minZ4 + j
                    );
            for (j = 0; j < h; j++)
            {
                const int sampleZ = blockZ + j * stride;
                const int z4 = floordiv(sampleZ, 4);
                const int fz = sampleZ - z4 * 4;
                const int iz = z4 - minZ4;
                for (i = 0; i < w; i++)
                {
                    const int sampleX = blockX + i * stride;
                    const int x4 = floordiv(sampleX, 4);
                    const int fx = sampleX - x4 * 4;
                    const int ix = x4 - minX4;
                    const int top = macro[iz*gridW+ix]
                        + floordiv((macro[iz*gridW+ix+1] - macro[iz*gridW+ix])*fx, 4);
                    const int bottom = macro[(iz+1)*gridW+ix]
                        + floordiv((macro[(iz+1)*gridW+ix+1] - macro[(iz+1)*gridW+ix])*fx, 4);
                    out[j*w+i] = top + floordiv((bottom-top)*fz, 4);
                }
            }
            free(macro);
        }
        else
        {
            int j, i;
            for (j = 0; j < h; j++)
                for (i = 0; i < w; i++)
                    out[j*w+i] = cfxWebModernOverviewHeight(
                        floordiv(blockX + i*stride, 4),
                        floordiv(blockZ + j*stride, 4)
                    );
        }
        return CFX_WEB_OK;
    }

    const int scale = stride < 4 ? 1 : 4;
    const int nativeStride = stride < 4 ? stride : stride / 4;
    const int err = cfxWebGenerateBiomesStrided(
        scale, floordiv(blockX, scale), floordiv(blockZ, scale),
        w, h, nativeStride, out
    );
    if (err != CFX_WEB_OK)
        return err;
    int j, i;
    for (j = 0; j < h; j++)
        for (i = 0; i < w; i++)
        {
            const int index = j*w+i;
            out[index] = cfxWebLegacyOverviewHeight(
                out[index], blockX + i*stride, blockZ + j*stride
            );
        }
    return CFX_WEB_OK;
}

static int cfxWebLerp(int from, int to, int offset, int extent)
{
    return from + floordiv((to - from) * offset, extent);
}

static int cfxWebApplyExactResiduals(
    int blockX, int blockZ, int lod, int blockStride, int *fluidConfidence
)
{
    const int correctionStride = lod == 0 ? 8 : 16;
    const int pixelSpan = CFX_WEB_GRID_SIZE - 1;
    const int intervals = (pixelSpan + correctionStride - 1) / correctionStride;
    const int anchorSize = intervals + 1;
    const int cells = anchorSize * anchorSize;
    const int anchorStride = correctionStride * blockStride;
    int *overview = malloc(sizeof(int) * (size_t) cells);
    int *exact = malloc(sizeof(int) * (size_t) cells);
    int *fluid = malloc(sizeof(int) * (size_t) cells);
    int *surface = malloc(sizeof(int) * (size_t) cells);
    int *flags = malloc(sizeof(int) * (size_t) cells);
    if (overview == NULL || exact == NULL || fluid == NULL
        || surface == NULL || flags == NULL)
    {
        free(overview); free(exact); free(fluid); free(surface); free(flags);
        return CFX_WEB_ALLOC;
    }
    int err = cfxWebOverviewHeights(
        blockX, blockZ, anchorSize, anchorSize, anchorStride, overview
    );
    if (err == CFX_WEB_OK)
    {
        err = mapOverworldSurfaceColumns(
            exact, fluid, surface, flags, &generator, &surfaceNoise,
            blockX, blockZ, anchorSize, anchorSize, anchorStride
        ) == 0 ? CFX_WEB_OK : CFX_WEB_GENERATION;
    }
    if (err == CFX_WEB_OK)
    {
        int j, i;
        for (j = 0; j < anchorSize; j++)
            for (i = 0; i < anchorSize; i++)
                overview[j*anchorSize+i] = exact[j*anchorSize+i]
                    - overview[j*anchorSize+i];
        for (j = 0; j < CFX_WEB_GRID_SIZE; j++)
        {
            const int az0 = j / correctionStride;
            const int az1 = az0 + 1;
            const int fz = j % correctionStride;
            for (i = 0; i < CFX_WEB_GRID_SIZE; i++)
            {
                const int ax0 = i / correctionStride;
                const int ax1 = ax0 + 1;
                const int fx = i % correctionStride;
                const int top = cfxWebLerp(
                    overview[az0*anchorSize+ax0], overview[az0*anchorSize+ax1],
                    fx, correctionStride
                );
                const int bottom = cfxWebLerp(
                    overview[az1*anchorSize+ax0], overview[az1*anchorSize+ax1],
                    fx, correctionStride
                );
                const int index = j*CFX_WEB_GRID_SIZE+i;
                if (lod == 0)
                {
                    const int fluidTop = cfxWebLerp(
                        (flags[az0*anchorSize+ax0] & 1) ? 256 : 0,
                        (flags[az0*anchorSize+ax1] & 1) ? 256 : 0,
                        fx, correctionStride
                    );
                    const int fluidBottom = cfxWebLerp(
                        (flags[az1*anchorSize+ax0] & 1) ? 256 : 0,
                        (flags[az1*anchorSize+ax1] & 1) ? 256 : 0,
                        fx, correctionStride
                    );
                    fluidConfidence[index] = cfxWebLerp(
                        fluidTop, fluidBottom, fz, correctionStride
                    );
                }
                if (lod == 0 && fluidConfidence[index] > 128)
                {
                    const int exactTop = cfxWebLerp(
                        exact[az0*anchorSize+ax0], exact[az0*anchorSize+ax1],
                        fx, correctionStride
                    );
                    const int exactBottom = cfxWebLerp(
                        exact[az1*anchorSize+ax0], exact[az1*anchorSize+ax1],
                        fx, correctionStride
                    );
                    heightData[index] = cfxWebLerp(
                        exactTop, exactBottom, fz, correctionStride
                    );
                }
                else
                {
                    heightData[index] += cfxWebLerp(
                        top, bottom, fz, correctionStride
                    );
                }
            }
        }
    }
    free(overview); free(exact); free(fluid); free(surface); free(flags);
    return err;
}

static int cfxWebIsWaterBiome(int biome)
{
    switch (biome)
    {
        case 0: case 7: case 10: case 11: case 24:
        case 44: case 45: case 46: case 48: case 49: case 50:
            return 1;
        default:
            return 0;
    }
}

static int cfxWebGenerateOverworld(int blockX, int blockZ, int lod, int exact)
{
    const int blockStride = 1 << lod;
    const int originX = blockX - blockStride;
    const int originZ = blockZ - blockStride;
    int *fluidConfidence = calloc(CFX_WEB_GRID_CELLS, sizeof(int));
    if (fluidConfidence == NULL)
        return CFX_WEB_ALLOC;
    int err = cfxWebOverviewHeights(
        originX, originZ, CFX_WEB_GRID_SIZE, CFX_WEB_GRID_SIZE,
        blockStride, heightData
    );
    if (err == CFX_WEB_OK && exact && lod <= 1)
        err = cfxWebApplyExactResiduals(
            originX, originZ, lod, blockStride, fluidConfidence
        );
    if (err == CFX_WEB_OK)
    {
        if (mc >= MC_1_18)
        {
            int j, i;
            for (j = 0; j < CFX_WEB_GRID_SIZE; j++)
                for (i = 0; i < CFX_WEB_GRID_SIZE; i++)
                {
                    const int index = j*CFX_WEB_GRID_SIZE+i;
                    const int x = originX + i*blockStride;
                    const int z = originZ + j*blockStride;
                    const int y = heightData[index] < CFX_WEB_WATER_LEVEL
                        ? CFX_WEB_WATER_LEVEL : heightData[index];
                    int x4, y4, z4;
                    voronoiAccess3D(generator.sha, x, y, z, &x4, &y4, &z4);
                    biomeData[index] = sampleBiomeNoise(
                        &generator.bn, NULL, x4, y4, z4, NULL, 0
                    );
                }
        }
        else
        {
            const int scale = blockStride < 4 ? 1 : 4;
            err = cfxWebGenerateBiomesStrided(
                scale, floordiv(originX, scale), floordiv(originZ, scale),
                CFX_WEB_GRID_SIZE, CFX_WEB_GRID_SIZE,
                blockStride / scale, biomeData
            );
        }
    }
    if (err == CFX_WEB_OK)
    {
        int i;
        for (i = 0; i < CFX_WEB_GRID_CELLS; i++)
        {
            const int belowSea = heightData[i] < CFX_WEB_WATER_LEVEL;
            const int fluid = lod == 0
                ? fluidConfidence[i] >= 128
                : heightData[i] < CFX_WEB_WATER_LEVEL - 1;
            const int water = belowSea && (fluid || cfxWebIsWaterBiome(biomeData[i]));
            int depth = water ? CFX_WEB_WATER_LEVEL - heightData[i] : 0;
            if (depth < 0) depth = 0;
            if (depth > 255) depth = 255;
            surfaceData[i] = (water ? CFX_WEB_SURFACE_WATER : 0) | (depth << 8);
        }
    }
    free(fluidConfidence);
    return err;
}

static int cfxWebGenerateFlatDimension(int blockX, int blockZ, int lod)
{
    const int blockStride = 1 << lod;
    const int scale = blockStride < 4 ? 1 : 4;
    const int originX = blockX - blockStride;
    const int originZ = blockZ - blockStride;
    const int err = cfxWebGenerateBiomesStrided(
        scale, floordiv(originX, scale), floordiv(originZ, scale),
        CFX_WEB_GRID_SIZE, CFX_WEB_GRID_SIZE,
        blockStride / scale, biomeData
    );
    if (err != CFX_WEB_OK)
        return err;
    const int height = dimension == DIM_NETHER ? CFX_WEB_NETHER_ROOF_Y : 64;
    int i;
    for (i = 0; i < CFX_WEB_GRID_CELLS; i++)
    {
        heightData[i] = height;
        surfaceData[i] = 0;
    }
    return CFX_WEB_OK;
}

static int cfxWebEndHeightsStrided(
    int x4, int z4, int w, int h, int stride, int *out
)
{
    const int rawWidth = (w - 1) * stride + 1;
    float *row = malloc(sizeof(float) * (size_t) rawWidth);
    if (row == NULL)
        return CFX_WEB_ALLOC;
    int j, i;
    for (j = 0; j < h; j++)
    {
        if (mapEndSurfaceHeight(
            row, &generator.en, &surfaceNoise,
            x4, z4 + j * stride, rawWidth, 1, 4, 0
        ) != 0)
        {
            free(row);
            return CFX_WEB_GENERATION;
        }
        for (i = 0; i < w; i++)
            out[j*w+i] = (int) floorf(row[i*stride]);
    }
    free(row);
    return CFX_WEB_OK;
}

static int cfxWebGenerateEnd(int blockX, int blockZ, int lod)
{
    const int blockStride = 1 << lod;
    int err = CFX_WEB_OK;
    if (lod <= 1)
    {
        const int sMin = floordiv(-blockStride, 4);
        const int sMax = floordiv(256 * blockStride, 4) + 1;
        const int sampleSize = sMax - sMin + 1;
        int *raw = malloc(sizeof(int) * (size_t) sampleSize * sampleSize);
        if (raw == NULL)
            return CFX_WEB_ALLOC;
        err = cfxWebEndHeightsStrided(
            floordiv(blockX, 4) + sMin, floordiv(blockZ, 4) + sMin,
            sampleSize, sampleSize, 1, raw
        );
        if (err == CFX_WEB_OK)
        {
            int pz, px;
            for (pz = -1; pz <= 256; pz++)
            {
                const int offsetZ = pz * blockStride;
                const int baseZ = floordiv(offsetZ, 4);
                const int fz = offsetZ - baseZ * 4;
                const int z0 = baseZ - sMin;
                for (px = -1; px <= 256; px++)
                {
                    const int offsetX = px * blockStride;
                    const int baseX = floordiv(offsetX, 4);
                    const int fx = offsetX - baseX * 4;
                    const int x0 = baseX - sMin;
                    const int a = raw[z0*sampleSize+x0];
                    const int b = raw[z0*sampleSize+x0+1];
                    const int c = raw[(z0+1)*sampleSize+x0];
                    const int d = raw[(z0+1)*sampleSize+x0+1];
                    const int index = (pz+1)*CFX_WEB_GRID_SIZE+px+1;
                    if (a == 0 && b == 0 && c == 0 && d == 0)
                        heightData[index] = INT32_MIN;
                    else
                    {
                        const int top = cfxWebLerp(a, b, fx, 4);
                        const int bottom = cfxWebLerp(c, d, fx, 4);
                        heightData[index] = cfxWebLerp(top, bottom, fz, 4);
                    }
                }
            }
        }
        free(raw);
    }
    else if (lod == 2)
    {
        err = cfxWebEndHeightsStrided(
            floordiv(blockX, 4) - 1, floordiv(blockZ, 4) - 1,
            CFX_WEB_GRID_SIZE, CFX_WEB_GRID_SIZE, 1, heightData
        );
        int i;
        for (i = 0; err == CFX_WEB_OK && i < CFX_WEB_GRID_CELLS; i++)
            if (heightData[i] == 0) heightData[i] = INT32_MIN;
    }
    else if (lod == 3)
    {
        const int sampleSize = CFX_WEB_GRID_SIZE * 2;
        int *raw = malloc(sizeof(int) * (size_t) sampleSize * sampleSize);
        if (raw == NULL)
            return CFX_WEB_ALLOC;
        err = cfxWebEndHeightsStrided(
            floordiv(blockX, 4) - 2, floordiv(blockZ, 4) - 2,
            sampleSize, sampleSize, 1, raw
        );
        if (err == CFX_WEB_OK)
        {
            int z, x;
            for (z = 0; z < CFX_WEB_GRID_SIZE; z++)
                for (x = 0; x < CFX_WEB_GRID_SIZE; x++)
                {
                    const int a = raw[(z*2)*sampleSize+x*2];
                    const int b = raw[(z*2)*sampleSize+x*2+1];
                    const int c = raw[(z*2+1)*sampleSize+x*2];
                    const int d = raw[(z*2+1)*sampleSize+x*2+1];
                    heightData[z*CFX_WEB_GRID_SIZE+x] =
                        a == 0 && b == 0 && c == 0 && d == 0
                            ? INT32_MIN : floordiv(a+b+c+d, 4);
                }
        }
        free(raw);
    }
    else
    {
        const int pixelsPerSample = 2;
        const int sMin = floordiv(-1, pixelsPerSample);
        const int sMax = floordiv(256, pixelsPerSample);
        const int sampleSize = sMax - sMin + 1;
        int *raw = malloc(sizeof(int) * (size_t) sampleSize * sampleSize);
        if (raw == NULL)
            return CFX_WEB_ALLOC;
        err = cfxWebEndHeightsStrided(
            floordiv(blockX, 4) + sMin * 8,
            floordiv(blockZ, 4) + sMin * 8,
            sampleSize, sampleSize, 8, raw
        );
        if (err == CFX_WEB_OK)
        {
            int pz, px;
            for (pz = -1; pz <= 256; pz++)
                for (px = -1; px <= 256; px++)
                {
                    const int sourceZ = floordiv(pz, pixelsPerSample) - sMin;
                    const int sourceX = floordiv(px, pixelsPerSample) - sMin;
                    const int value = raw[sourceZ*sampleSize+sourceX];
                    heightData[(pz+1)*CFX_WEB_GRID_SIZE+px+1] =
                        value == 0 ? INT32_MIN : value;
                }
        }
        free(raw);
    }
    if (err != CFX_WEB_OK)
        return err;

    const int scale = blockStride < 4 ? 1 : 4;
    err = cfxWebGenerateBiomesStrided(
        scale,
        floordiv(blockX - blockStride, scale),
        floordiv(blockZ - blockStride, scale),
        CFX_WEB_GRID_SIZE, CFX_WEB_GRID_SIZE,
        blockStride / scale, biomeData
    );
    if (err != CFX_WEB_OK)
        return err;
    if (lod == 4)
    {
        int *coarse = malloc(sizeof(heightData));
        if (coarse == NULL)
            return CFX_WEB_ALLOC;
        memcpy(coarse, heightData, sizeof(heightData));
        int z, x;
        for (z = 0; z < CFX_WEB_GRID_SIZE; z++)
            for (x = 0; x < CFX_WEB_GRID_SIZE; x++)
            {
                const int index = z*CFX_WEB_GRID_SIZE+x;
                if (coarse[index] != INT32_MIN || biomeData[index] == 40)
                    continue;
                int nearest = INT32_MIN;
                int nearestDistance = 3;
                int dz, dx;
                for (dz = -1; dz <= 1; dz++)
                    for (dx = -1; dx <= 1; dx++)
                    {
                        const int nx = x + dx;
                        const int nz = z + dz;
                        const int distance = dx*dx + dz*dz;
                        if (nx < 0 || nx >= CFX_WEB_GRID_SIZE
                            || nz < 0 || nz >= CFX_WEB_GRID_SIZE
                            || distance >= nearestDistance)
                            continue;
                        const int candidate = coarse[nz*CFX_WEB_GRID_SIZE+nx];
                        if (candidate != INT32_MIN)
                        {
                            nearest = candidate;
                            nearestDistance = distance;
                        }
                    }
                if (nearest != INT32_MIN)
                    heightData[index] = nearest;
            }
        free(coarse);
    }
    int i;
    for (i = 0; i < CFX_WEB_GRID_CELLS; i++)
        surfaceData[i] = heightData[i] == INT32_MIN ? CFX_WEB_SURFACE_VOID : 0;
    return CFX_WEB_OK;
}

__attribute__((visibility("default")))
int cfxWebInit(int mcVersion, int64_t seed, int dim, int flags)
{
    if (mcVersion <= MC_UNDEF || mcVersion > MC_NEWEST)
        return CFX_WEB_BAD_ARGS;
    if (dim != DIM_OVERWORLD && dim != DIM_NETHER && dim != DIM_END)
        return CFX_WEB_WRONG_DIM;
    memset(&generator, 0, sizeof(generator));
    memset(&surfaceNoise, 0, sizeof(surfaceNoise));
    setupGenerator(&generator, mcVersion, (uint32_t) flags);
    applySeed(&generator, dim, (uint64_t) seed);
    initSurfaceNoise(&surfaceNoise, dim, (uint64_t) seed);
    worldSeed = (uint64_t) seed;
    mc = mcVersion;
    dimension = dim;
    ready = 1;
    return CFX_WEB_OK;
}

__attribute__((visibility("default")))
int cfxWebGenerateTile(int blockX, int blockZ, int lod, int exact)
{
    if (!ready)
        return CFX_WEB_BAD_STATE;
    if (lod < 0 || lod > 4)
        return CFX_WEB_BAD_ARGS;
    int result;
    if (dimension == DIM_OVERWORLD)
        result = cfxWebGenerateOverworld(blockX, blockZ, lod, exact != 0);
    else if (dimension == DIM_END)
        result = exact
            ? cfxWebGenerateEnd(blockX, blockZ, lod)
            : cfxWebGenerateFlatDimension(blockX, blockZ, lod);
    else
        result = cfxWebGenerateFlatDimension(blockX, blockZ, lod);
    if (result == CFX_WEB_OK)
        result = cfxWebGenerateSubSamples(blockX, blockZ, lod);
    if (result == CFX_WEB_OK)
        cfxWebGenerateCanopy(blockX, blockZ, lod);
    return result;
}

__attribute__((visibility("default")))
int *cfxWebBiomeData(void)
{
    return biomeData;
}

__attribute__((visibility("default")))
int *cfxWebHeightData(void)
{
    return heightData;
}

__attribute__((visibility("default")))
int *cfxWebSurfaceData(void)
{
    return surfaceData;
}

__attribute__((visibility("default")))
int *cfxWebCanopyData(void)
{
    return canopyData;
}

__attribute__((visibility("default")))
int *cfxWebSubBiomeData(void)
{
    return subBiomeData;
}

__attribute__((visibility("default")))
int *cfxWebSubSurfaceData(void)
{
    return subSurfaceData;
}

__attribute__((visibility("default")))
int *cfxWebSubCanopyData(void)
{
    return subCanopyData;
}
