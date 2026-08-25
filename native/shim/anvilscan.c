/*
 * Selective Anvil chunk-NBT parser used by coarse server correction scans.
 *
 * The Java side has already read and decompressed one chunk. This parser deliberately keeps no
 * generic NBT tree: it retains only status/revision, two heightmaps, section palettes/packed data,
 * biomes and block light, then emits the centered columns required by the requested LOD. Entity,
 * structure and tick payloads are skipped in-place.
 */

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define CFX_SCAN_OK 0
#define CFX_SCAN_BAD_ARGS 3
#define CFX_SCAN_ALLOC 4
#define CFX_SCAN_MALFORMED 8

#define NBT_END 0
#define NBT_BYTE 1
#define NBT_SHORT 2
#define NBT_INT 3
#define NBT_LONG 4
#define NBT_FLOAT 5
#define NBT_DOUBLE 6
#define NBT_BYTE_ARRAY 7
#define NBT_STRING 8
#define NBT_LIST 9
#define NBT_COMPOUND 10
#define NBT_INT_ARRAY 11
#define NBT_LONG_ARRAY 12

#define CFX_MAX_NBT_DEPTH 64
#define CFX_MAX_ARRAY_ITEMS (1 << 22)
#define CFX_MAX_SECTIONS 64
#define CFX_MAX_PALETTE 4096

typedef struct {
    const uint8_t *data;
    size_t size;
    size_t pos;
    int failed;
} CfxNbtReader;

typedef struct {
    const uint8_t *data;
    uint16_t size;
} CfxSlice;

typedef struct {
    uint64_t *values;
    int count;
} CfxLongArray;

typedef struct {
    int32_t *values;
    int count;
} CfxIntArray;

typedef struct {
    uint8_t *values;
    int count;
} CfxByteArray;

typedef struct {
    char *name;
    int fluid;
} CfxBlockEntry;

typedef struct {
    int y;
    int has_y;
    CfxBlockEntry *blocks;
    int block_count;
    CfxLongArray block_states;
    char **biomes;
    int biome_count;
    CfxLongArray biome_data;
    CfxByteArray block_light;
} CfxSection;

typedef struct {
    int full;
    int64_t revision;
    int bottom_y;
    CfxLongArray motion_blocking;
    CfxLongArray ocean_floor;
    CfxIntArray legacy_biomes;
    CfxSection *sections;
    int section_count;
} CfxChunkNbt;

typedef struct {
    int biome_id;
    const char *biome_name;
    int surface_y;
    int fluid;
    int fluid_depth;
    int block_light;
    const char *surface_name;
    const char *floor_name;
} CfxSample;

static const CfxBlockEntry CFX_AIR = { "minecraft:air", 0 };

static int cfxNbtNeed(CfxNbtReader *reader, size_t count) {
    if (reader->failed || count > reader->size - reader->pos) {
        reader->failed = 1;
        return 0;
    }
    return 1;
}

static uint8_t cfxNbtU8(CfxNbtReader *reader) {
    if (!cfxNbtNeed(reader, 1))
        return 0;
    return reader->data[reader->pos++];
}

static uint16_t cfxNbtU16(CfxNbtReader *reader) {
    if (!cfxNbtNeed(reader, 2))
        return 0;
    const uint16_t value = ((uint16_t) reader->data[reader->pos] << 8)
        | (uint16_t) reader->data[reader->pos + 1];
    reader->pos += 2;
    return value;
}

static uint32_t cfxNbtU32(CfxNbtReader *reader) {
    if (!cfxNbtNeed(reader, 4))
        return 0;
    const uint32_t value = ((uint32_t) reader->data[reader->pos] << 24)
        | ((uint32_t) reader->data[reader->pos + 1] << 16)
        | ((uint32_t) reader->data[reader->pos + 2] << 8)
        | (uint32_t) reader->data[reader->pos + 3];
    reader->pos += 4;
    return value;
}

static uint64_t cfxNbtU64(CfxNbtReader *reader) {
    const uint64_t high = cfxNbtU32(reader);
    const uint64_t low = cfxNbtU32(reader);
    return (high << 32) | low;
}

static int cfxNbtCount(CfxNbtReader *reader) {
    const int32_t count = (int32_t) cfxNbtU32(reader);
    if (reader->failed || count < 0 || count > CFX_MAX_ARRAY_ITEMS) {
        reader->failed = 1;
        return 0;
    }
    return count;
}

static CfxSlice cfxNbtString(CfxNbtReader *reader) {
    CfxSlice value = { NULL, 0 };
    const uint16_t size = cfxNbtU16(reader);
    if (!cfxNbtNeed(reader, size))
        return value;
    value.data = reader->data + reader->pos;
    value.size = size;
    reader->pos += size;
    return value;
}

static int cfxSliceEquals(CfxSlice value, const char *literal) {
    const size_t size = strlen(literal);
    return size == value.size && memcmp(value.data, literal, size) == 0;
}

static char *cfxSliceCopy(CfxSlice value) {
    char *copy = malloc((size_t) value.size + 1);
    if (copy == NULL)
        return NULL;
    memcpy(copy, value.data, value.size);
    copy[value.size] = '\0';
    return copy;
}

static int cfxNbtSkip(CfxNbtReader *reader, int type, int depth);

static int cfxNbtSkipCompound(CfxNbtReader *reader, int depth) {
    if (depth > CFX_MAX_NBT_DEPTH) {
        reader->failed = 1;
        return 0;
    }
    while (!reader->failed) {
        const int type = cfxNbtU8(reader);
        if (type == NBT_END)
            return 1;
        (void) cfxNbtString(reader);
        if (!cfxNbtSkip(reader, type, depth + 1))
            return 0;
    }
    return 0;
}

static int cfxNbtSkip(CfxNbtReader *reader, int type, int depth) {
    if (depth > CFX_MAX_NBT_DEPTH) {
        reader->failed = 1;
        return 0;
    }
    size_t bytes = 0;
    int count;
    switch (type) {
        case NBT_BYTE: bytes = 1; break;
        case NBT_SHORT: bytes = 2; break;
        case NBT_INT:
        case NBT_FLOAT: bytes = 4; break;
        case NBT_LONG:
        case NBT_DOUBLE: bytes = 8; break;
        case NBT_STRING:
            (void) cfxNbtString(reader);
            return !reader->failed;
        case NBT_BYTE_ARRAY:
            count = cfxNbtCount(reader);
            bytes = (size_t) count;
            break;
        case NBT_INT_ARRAY:
            count = cfxNbtCount(reader);
            bytes = (size_t) count * 4;
            break;
        case NBT_LONG_ARRAY:
            count = cfxNbtCount(reader);
            bytes = (size_t) count * 8;
            break;
        case NBT_LIST: {
            const int element_type = cfxNbtU8(reader);
            count = cfxNbtCount(reader);
            for (int i = 0; i < count && !reader->failed; i++)
                if (!cfxNbtSkip(reader, element_type, depth + 1))
                    return 0;
            return !reader->failed;
        }
        case NBT_COMPOUND:
            return cfxNbtSkipCompound(reader, depth + 1);
        default:
            reader->failed = 1;
            return 0;
    }
    if (!cfxNbtNeed(reader, bytes))
        return 0;
    reader->pos += bytes;
    return 1;
}

static void cfxFreeLongArray(CfxLongArray *array) {
    free(array->values);
    array->values = NULL;
    array->count = 0;
}

static void cfxFreeIntArray(CfxIntArray *array) {
    free(array->values);
    array->values = NULL;
    array->count = 0;
}

static void cfxFreeByteArray(CfxByteArray *array) {
    free(array->values);
    array->values = NULL;
    array->count = 0;
}

static void cfxFreeBlocks(CfxBlockEntry *blocks, int count) {
    if (blocks == NULL)
        return;
    for (int i = 0; i < count; i++)
        free(blocks[i].name);
    free(blocks);
}

static void cfxFreeBiomes(char **biomes, int count) {
    if (biomes == NULL)
        return;
    for (int i = 0; i < count; i++)
        free(biomes[i]);
    free(biomes);
}

static void cfxFreeSection(CfxSection *section) {
    cfxFreeBlocks(section->blocks, section->block_count);
    cfxFreeLongArray(&section->block_states);
    cfxFreeBiomes(section->biomes, section->biome_count);
    cfxFreeLongArray(&section->biome_data);
    cfxFreeByteArray(&section->block_light);
    memset(section, 0, sizeof(*section));
}

static void cfxFreeChunk(CfxChunkNbt *chunk) {
    cfxFreeLongArray(&chunk->motion_blocking);
    cfxFreeLongArray(&chunk->ocean_floor);
    cfxFreeIntArray(&chunk->legacy_biomes);
    if (chunk->sections != NULL) {
        for (int i = 0; i < chunk->section_count; i++)
            cfxFreeSection(&chunk->sections[i]);
    }
    free(chunk->sections);
    memset(chunk, 0, sizeof(*chunk));
}

static int cfxReadLongArray(CfxNbtReader *reader, CfxLongArray *target) {
    const int count = cfxNbtCount(reader);
    if (reader->failed)
        return 0;
    uint64_t *values = count == 0 ? NULL : malloc(sizeof(uint64_t) * (size_t) count);
    if (count != 0 && values == NULL) {
        reader->failed = 1;
        return 0;
    }
    for (int i = 0; i < count; i++)
        values[i] = cfxNbtU64(reader);
    if (reader->failed) {
        free(values);
        return 0;
    }
    cfxFreeLongArray(target);
    target->values = values;
    target->count = count;
    return 1;
}

static int cfxReadIntArray(CfxNbtReader *reader, CfxIntArray *target) {
    const int count = cfxNbtCount(reader);
    if (reader->failed)
        return 0;
    int32_t *values = count == 0 ? NULL : malloc(sizeof(int32_t) * (size_t) count);
    if (count != 0 && values == NULL) {
        reader->failed = 1;
        return 0;
    }
    for (int i = 0; i < count; i++)
        values[i] = (int32_t) cfxNbtU32(reader);
    if (reader->failed) {
        free(values);
        return 0;
    }
    cfxFreeIntArray(target);
    target->values = values;
    target->count = count;
    return 1;
}

static int cfxReadByteArray(CfxNbtReader *reader, CfxByteArray *target) {
    const int count = cfxNbtCount(reader);
    if (reader->failed || !cfxNbtNeed(reader, (size_t) count))
        return 0;
    uint8_t *values = count == 0 ? NULL : malloc((size_t) count);
    if (count != 0 && values == NULL) {
        reader->failed = 1;
        return 0;
    }
    if (count != 0)
        memcpy(values, reader->data + reader->pos, (size_t) count);
    reader->pos += (size_t) count;
    cfxFreeByteArray(target);
    target->values = values;
    target->count = count;
    return 1;
}

static int cfxNameHasWater(const char *name) {
    return name != NULL && (strstr(name, "water") != NULL
        || strcmp(name, "minecraft:kelp") == 0
        || strcmp(name, "minecraft:kelp_plant") == 0
        || strcmp(name, "minecraft:seagrass") == 0
        || strcmp(name, "minecraft:tall_seagrass") == 0
        || strcmp(name, "minecraft:bubble_column") == 0
        || strcmp(name, "minecraft:sea_pickle") == 0);
}

static int cfxFluidForName(const char *name, int waterlogged) {
    if (waterlogged || cfxNameHasWater(name))
        return 1;
    return name != NULL && strstr(name, "lava") != NULL ? 2 : 0;
}

static int cfxParseProperties(CfxNbtReader *reader) {
    int waterlogged = 0;
    while (!reader->failed) {
        const int type = cfxNbtU8(reader);
        if (type == NBT_END)
            return waterlogged;
        const CfxSlice name = cfxNbtString(reader);
        if (type == NBT_STRING && cfxSliceEquals(name, "waterlogged")) {
            const CfxSlice value = cfxNbtString(reader);
            if (cfxSliceEquals(value, "true"))
                waterlogged = 1;
        } else {
            cfxNbtSkip(reader, type, 1);
        }
    }
    return waterlogged;
}

static int cfxParseBlockEntry(CfxNbtReader *reader, CfxBlockEntry *entry) {
    int waterlogged = 0;
    while (!reader->failed) {
        const int type = cfxNbtU8(reader);
        if (type == NBT_END)
            break;
        const CfxSlice name = cfxNbtString(reader);
        if (type == NBT_STRING && cfxSliceEquals(name, "Name")) {
            const CfxSlice value = cfxNbtString(reader);
            free(entry->name);
            entry->name = cfxSliceCopy(value);
            if (entry->name == NULL)
                reader->failed = 1;
        } else if (type == NBT_COMPOUND && cfxSliceEquals(name, "Properties")) {
            waterlogged = cfxParseProperties(reader);
        } else {
            cfxNbtSkip(reader, type, 1);
        }
    }
    if (entry->name == NULL && !reader->failed) {
        entry->name = cfxSliceCopy((CfxSlice) { (const uint8_t *) "minecraft:air", 13 });
        if (entry->name == NULL)
            reader->failed = 1;
    }
    entry->fluid = cfxFluidForName(entry->name, waterlogged);
    return !reader->failed;
}

static int cfxParseBlockPalette(CfxNbtReader *reader, CfxSection *section) {
    const int element_type = cfxNbtU8(reader);
    const int count = cfxNbtCount(reader);
    if (reader->failed)
        return 0;
    if (element_type != NBT_COMPOUND || count > CFX_MAX_PALETTE) {
        for (int i = 0; i < count && !reader->failed; i++)
            cfxNbtSkip(reader, element_type, 1);
        return !reader->failed;
    }
    CfxBlockEntry *blocks = count == 0 ? NULL : calloc((size_t) count, sizeof(CfxBlockEntry));
    if (count != 0 && blocks == NULL) {
        reader->failed = 1;
        return 0;
    }
    for (int i = 0; i < count && !reader->failed; i++)
        cfxParseBlockEntry(reader, &blocks[i]);
    if (reader->failed) {
        cfxFreeBlocks(blocks, count);
        return 0;
    }
    cfxFreeBlocks(section->blocks, section->block_count);
    section->blocks = blocks;
    section->block_count = count;
    return 1;
}

static int cfxParseBiomePalette(CfxNbtReader *reader, CfxSection *section) {
    const int element_type = cfxNbtU8(reader);
    const int count = cfxNbtCount(reader);
    if (reader->failed)
        return 0;
    if (element_type != NBT_STRING || count > CFX_MAX_PALETTE) {
        for (int i = 0; i < count && !reader->failed; i++)
            cfxNbtSkip(reader, element_type, 1);
        return !reader->failed;
    }
    char **biomes = count == 0 ? NULL : calloc((size_t) count, sizeof(char *));
    if (count != 0 && biomes == NULL) {
        reader->failed = 1;
        return 0;
    }
    for (int i = 0; i < count && !reader->failed; i++) {
        biomes[i] = cfxSliceCopy(cfxNbtString(reader));
        if (biomes[i] == NULL)
            reader->failed = 1;
    }
    if (reader->failed) {
        cfxFreeBiomes(biomes, count);
        return 0;
    }
    cfxFreeBiomes(section->biomes, section->biome_count);
    section->biomes = biomes;
    section->biome_count = count;
    return 1;
}

static int cfxParseBlockStates(CfxNbtReader *reader, CfxSection *section) {
    while (!reader->failed) {
        const int type = cfxNbtU8(reader);
        if (type == NBT_END)
            return 1;
        const CfxSlice name = cfxNbtString(reader);
        if (type == NBT_LIST && cfxSliceEquals(name, "palette"))
            cfxParseBlockPalette(reader, section);
        else if (type == NBT_LONG_ARRAY && cfxSliceEquals(name, "data"))
            cfxReadLongArray(reader, &section->block_states);
        else
            cfxNbtSkip(reader, type, 1);
    }
    return 0;
}

static int cfxParseBiomes(CfxNbtReader *reader, CfxSection *section) {
    while (!reader->failed) {
        const int type = cfxNbtU8(reader);
        if (type == NBT_END)
            return 1;
        const CfxSlice name = cfxNbtString(reader);
        if (type == NBT_LIST && cfxSliceEquals(name, "palette"))
            cfxParseBiomePalette(reader, section);
        else if (type == NBT_LONG_ARRAY && cfxSliceEquals(name, "data"))
            cfxReadLongArray(reader, &section->biome_data);
        else
            cfxNbtSkip(reader, type, 1);
    }
    return 0;
}

static int cfxParseSection(CfxNbtReader *reader, CfxSection *section) {
    while (!reader->failed) {
        const int type = cfxNbtU8(reader);
        if (type == NBT_END)
            return 1;
        const CfxSlice name = cfxNbtString(reader);
        if (type == NBT_BYTE && cfxSliceEquals(name, "Y")) {
            section->y = (int8_t) cfxNbtU8(reader);
            section->has_y = 1;
        } else if (type == NBT_LIST && cfxSliceEquals(name, "Palette")) {
            cfxParseBlockPalette(reader, section);
        } else if (type == NBT_LONG_ARRAY && cfxSliceEquals(name, "BlockStates")) {
            cfxReadLongArray(reader, &section->block_states);
        } else if (type == NBT_COMPOUND && cfxSliceEquals(name, "block_states")) {
            cfxParseBlockStates(reader, section);
        } else if (type == NBT_COMPOUND && cfxSliceEquals(name, "biomes")) {
            cfxParseBiomes(reader, section);
        } else if (type == NBT_BYTE_ARRAY && cfxSliceEquals(name, "BlockLight")) {
            cfxReadByteArray(reader, &section->block_light);
        } else {
            cfxNbtSkip(reader, type, 1);
        }
    }
    return 0;
}

static int cfxParseSections(CfxNbtReader *reader, CfxChunkNbt *chunk) {
    const int element_type = cfxNbtU8(reader);
    const int count = cfxNbtCount(reader);
    if (reader->failed)
        return 0;
    if (element_type != NBT_COMPOUND || count > CFX_MAX_SECTIONS) {
        for (int i = 0; i < count && !reader->failed; i++)
            cfxNbtSkip(reader, element_type, 1);
        return !reader->failed;
    }
    CfxSection *sections = count == 0 ? NULL : calloc((size_t) count, sizeof(CfxSection));
    if (count != 0 && sections == NULL) {
        reader->failed = 1;
        return 0;
    }
    for (int i = 0; i < count && !reader->failed; i++)
        cfxParseSection(reader, &sections[i]);
    if (reader->failed) {
        for (int i = 0; i < count; i++)
            cfxFreeSection(&sections[i]);
        free(sections);
        return 0;
    }
    if (chunk->sections != NULL) {
        for (int i = 0; i < chunk->section_count; i++)
            cfxFreeSection(&chunk->sections[i]);
        free(chunk->sections);
    }
    chunk->sections = sections;
    chunk->section_count = count;
    return 1;
}

static int cfxParseHeightmaps(CfxNbtReader *reader, CfxChunkNbt *chunk) {
    while (!reader->failed) {
        const int type = cfxNbtU8(reader);
        if (type == NBT_END)
            return 1;
        const CfxSlice name = cfxNbtString(reader);
        if (type == NBT_LONG_ARRAY && cfxSliceEquals(name, "MOTION_BLOCKING"))
            cfxReadLongArray(reader, &chunk->motion_blocking);
        else if (type == NBT_LONG_ARRAY && cfxSliceEquals(name, "OCEAN_FLOOR"))
            cfxReadLongArray(reader, &chunk->ocean_floor);
        else
            cfxNbtSkip(reader, type, 1);
    }
    return 0;
}

static int cfxParseLevel(CfxNbtReader *reader, CfxChunkNbt *chunk, int legacy) {
    while (!reader->failed) {
        const int type = cfxNbtU8(reader);
        if (type == NBT_END)
            return 1;
        const CfxSlice name = cfxNbtString(reader);
        if (type == NBT_STRING && cfxSliceEquals(name, "Status")) {
            const CfxSlice status = cfxNbtString(reader);
            chunk->full = cfxSliceEquals(status, "full") || cfxSliceEquals(status, "minecraft:full");
        } else if (type == NBT_LONG && cfxSliceEquals(name, "LastUpdate")) {
            chunk->revision = (int64_t) cfxNbtU64(reader);
        } else if (type == NBT_INT && cfxSliceEquals(name, "yPos")) {
            chunk->bottom_y = (int32_t) cfxNbtU32(reader) * 16;
        } else if (type == NBT_COMPOUND && cfxSliceEquals(name, "Heightmaps")) {
            cfxParseHeightmaps(reader, chunk);
        } else if (type == NBT_INT_ARRAY && cfxSliceEquals(name, "Biomes")) {
            cfxReadIntArray(reader, &chunk->legacy_biomes);
        } else if (type == NBT_LIST
            && (cfxSliceEquals(name, "Sections") || cfxSliceEquals(name, "sections"))) {
            cfxParseSections(reader, chunk);
        } else {
            cfxNbtSkip(reader, type, 1);
        }
    }
    (void) legacy;
    return 0;
}

static int cfxParseRoot(CfxNbtReader *reader, CfxChunkNbt *chunk) {
    const int root_type = cfxNbtU8(reader);
    (void) cfxNbtString(reader);
    if (reader->failed || root_type != NBT_COMPOUND)
        return 0;
    while (!reader->failed) {
        const int type = cfxNbtU8(reader);
        if (type == NBT_END)
            return 1;
        const CfxSlice name = cfxNbtString(reader);
        if (type == NBT_COMPOUND && cfxSliceEquals(name, "Level")) {
            chunk->bottom_y = 0;
            cfxParseLevel(reader, chunk, 1);
        } else if (type == NBT_STRING && cfxSliceEquals(name, "Status")) {
            const CfxSlice status = cfxNbtString(reader);
            chunk->full = cfxSliceEquals(status, "full") || cfxSliceEquals(status, "minecraft:full");
        } else if (type == NBT_LONG && cfxSliceEquals(name, "LastUpdate")) {
            chunk->revision = (int64_t) cfxNbtU64(reader);
        } else if (type == NBT_INT && cfxSliceEquals(name, "yPos")) {
            chunk->bottom_y = (int32_t) cfxNbtU32(reader) * 16;
        } else if (type == NBT_COMPOUND && cfxSliceEquals(name, "Heightmaps")) {
            cfxParseHeightmaps(reader, chunk);
        } else if (type == NBT_INT_ARRAY && cfxSliceEquals(name, "Biomes")) {
            cfxReadIntArray(reader, &chunk->legacy_biomes);
        } else if (type == NBT_LIST
            && (cfxSliceEquals(name, "Sections") || cfxSliceEquals(name, "sections"))) {
            cfxParseSections(reader, chunk);
        } else {
            cfxNbtSkip(reader, type, 1);
        }
    }
    return 0;
}

static int cfxBitsFor(int size) {
    int bits = 0;
    int value = size > 1 ? size - 1 : 1;
    while (value > 0) {
        bits++;
        value >>= 1;
    }
    return bits;
}

static int cfxPacked(const CfxLongArray *array, int bits, int index, int *ok) {
    if (bits <= 0 || bits > 32 || index < 0) {
        *ok = 0;
        return 0;
    }
    const int per_word = 64 / bits;
    const int word = index / per_word;
    if (word < 0 || word >= array->count) {
        *ok = 0;
        return 0;
    }
    const int shift = (index % per_word) * bits;
    const uint64_t mask = ((uint64_t) 1 << bits) - 1;
    return (int) ((array->values[word] >> shift) & mask);
}

static const CfxSection *cfxSectionAt(const CfxChunkNbt *chunk, int y) {
    int section_y = y / 16;
    if (y < 0 && y % 16 != 0)
        section_y--;
    for (int i = 0; i < chunk->section_count; i++)
        if (chunk->sections[i].has_y && chunk->sections[i].y == section_y)
            return &chunk->sections[i];
    return NULL;
}

static const CfxBlockEntry *cfxBlockAt(const CfxChunkNbt *chunk, int x, int y, int z) {
    const CfxSection *section = cfxSectionAt(chunk, y);
    if (section == NULL || section->block_count <= 0)
        return &CFX_AIR;
    int local_y = y % 16;
    if (local_y < 0)
        local_y += 16;
    const int index = (local_y * 16 + z) * 16 + x;
    int palette = 0;
    if (section->block_states.count != 0) {
        int ok = 1;
        int bits = cfxBitsFor(section->block_count);
        if (bits < 4)
            bits = 4;
        palette = cfxPacked(&section->block_states, bits, index, &ok);
        if (!ok)
            return &CFX_AIR;
    }
    if (palette < 0 || palette >= section->block_count)
        palette = section->block_count - 1;
    return &section->blocks[palette];
}

static int cfxBlockLightAt(const CfxChunkNbt *chunk, int x, int y, int z) {
    const CfxSection *section = cfxSectionAt(chunk, y);
    if (section == NULL || section->block_light.count == 0)
        return 0;
    int local_y = y % 16;
    if (local_y < 0)
        local_y += 16;
    const int index = (local_y * 16 + z) * 16 + x;
    const int byte_index = index >> 1;
    if (byte_index < 0 || byte_index >= section->block_light.count)
        return 0;
    return (section->block_light.values[byte_index] >> ((index & 1) * 4)) & 0xF;
}

static int cfxContains(const char *name, const char *needle) {
    return name != NULL && strstr(name, needle) != NULL;
}

static int cfxIsSnow(const char *name) {
    return name != NULL && (strcmp(name, "minecraft:snow") == 0
        || strcmp(name, "minecraft:powder_snow") == 0);
}

static int cfxIsCarpet(const char *name) {
    if (name == NULL)
        return 0;
    const size_t length = strlen(name);
    static const char suffix[] = "_carpet";
    return length >= sizeof(suffix) - 1
        && strcmp(name + length - (sizeof(suffix) - 1), suffix) == 0;
}

static int cfxIsIce(const char *name) {
    return cfxContains(name, "ice");
}

static int cfxIsKelp(const char *name) {
    return name != NULL && (strcmp(name, "minecraft:kelp") == 0
        || strcmp(name, "minecraft:kelp_plant") == 0);
}

static int cfxIsUnderwater(const CfxBlockEntry *block) {
    const char *name = block->name;
    return block->fluid == 1 || cfxContains(name, "bubble_column") || cfxIsKelp(name)
        || cfxContains(name, "seagrass") || cfxContains(name, "sea_pickle")
        || cfxContains(name, "coral_fan");
}

static int cfxBiomeAt(
    const CfxChunkNbt *chunk, int x, int y, int z, const char **name
) {
    *name = NULL;
    if (chunk->legacy_biomes.count != 0) {
        int quart_y = y / 4;
        if (y < 0 && y % 4 != 0)
            quart_y--;
        if (quart_y < 0)
            quart_y = 0;
        if (quart_y > 63)
            quart_y = 63;
        const int index = (x >> 2) + ((z >> 2) * 4) + quart_y * 16;
        return index >= 0 && index < chunk->legacy_biomes.count
            ? chunk->legacy_biomes.values[index] : 1;
    }
    const CfxSection *section = cfxSectionAt(chunk, y);
    if (section == NULL || section->biome_count <= 0)
        return 1;
    int local_y = y % 16;
    if (local_y < 0)
        local_y += 16;
    const int index = (((local_y >> 2) * 4 + (z >> 2)) * 4) + (x >> 2);
    int palette = 0;
    if (section->biome_data.count != 0) {
        int ok = 1;
        int bits = cfxBitsFor(section->biome_count);
        if (bits < 1)
            bits = 1;
        palette = cfxPacked(&section->biome_data, bits, index, &ok);
        if (!ok)
            return 1;
    }
    if (palette < 0 || palette >= section->biome_count)
        palette = section->biome_count - 1;
    *name = section->biomes[palette];
    return -1;
}

static int cfxClampDepth(int depth) {
    if (depth < 0)
        return 0;
    return depth > 255 ? 255 : depth;
}

static int cfxSummarizeSample(
    const CfxChunkNbt *chunk, int x, int z, CfxSample *sample
) {
    int ok = 1;
    const int height = cfxPacked(&chunk->motion_blocking, 9, z * 16 + x, &ok);
    if (!ok)
        return 0;
    const int ground_y = chunk->bottom_y + height - 1;
    const CfxBlockEntry *ground = cfxBlockAt(chunk, x, ground_y, z);
    const CfxBlockEntry *surface = ground;
    const CfxBlockEntry *fluid_surface = ground;
    int surface_y = ground_y;
    int fluid_surface_y = ground_y;
    int promoted_fluid = 0;
    const CfxBlockEntry *cover = cfxBlockAt(chunk, x, ground_y + 1, z);
    if (cover->fluid == 1 || cover->fluid == 2) {
        surface_y = ground_y + 1;
        fluid_surface_y = surface_y;
        surface = cover;
        fluid_surface = cover;
        promoted_fluid = 1;
    } else if (cfxIsSnow(cover->name) || cfxIsCarpet(cover->name)) {
        surface_y = ground_y + 1;
        surface = cover;
    }

    sample->biome_id = cfxBiomeAt(chunk, x, surface_y, z, &sample->biome_name);
    sample->surface_y = surface_y;
    sample->surface_name = surface->name;
    sample->fluid = surface->fluid;
    sample->fluid_depth = 0;
    sample->block_light = cfxBlockLightAt(chunk, x, surface_y + 1, z);
    sample->floor_name = NULL;

    const int has_fluid = fluid_surface->fluid == 1 || cfxIsIce(fluid_surface->name);
    if (!has_fluid)
        return 1;

    int floor_y;
    if (fluid_surface->fluid == 1 && !promoted_fluid && chunk->ocean_floor.count != 0) {
        int floor_ok = 1;
        const int ocean_height = chunk->bottom_y
            + cfxPacked(&chunk->ocean_floor, 9, z * 16 + x, &floor_ok);
        if (floor_ok && ocean_height <= fluid_surface_y) {
            floor_y = ocean_height - 1;
        } else {
            floor_y = fluid_surface_y - 1;
            while (floor_y >= chunk->bottom_y
                && cfxIsUnderwater(cfxBlockAt(chunk, x, floor_y, z)))
                floor_y--;
        }
    } else {
        floor_y = fluid_surface_y - 1;
        while (floor_y >= chunk->bottom_y
            && cfxIsUnderwater(cfxBlockAt(chunk, x, floor_y, z)))
            floor_y--;
    }
    int depth = cfxClampDepth(fluid_surface_y - floor_y);
    if (cfxIsIce(fluid_surface->name) && depth <= 1)
        depth = 0;
    sample->fluid_depth = depth;
    sample->floor_name = depth == 0 ? NULL : cfxBlockAt(chunk, x, floor_y, z)->name;
    return 1;
}

static int cfxSetString(JNIEnv *env, jobjectArray array, int index, const char *value) {
    if (value == NULL)
        return 1;
    jstring string = (*env)->NewStringUTF(env, value);
    if (string == NULL)
        return 0;
    (*env)->SetObjectArrayElement(env, array, index, string);
    (*env)->DeleteLocalRef(env, string);
    return !(*env)->ExceptionCheck(env);
}

JNIEXPORT jint JNICALL Java_cn_net_rms_confluxmap_nativepredict_CubiomesNative_cfxScanChunkNbt(
    JNIEnv *env,
    jclass clazz,
    jbyteArray nbt,
    jint nbt_length,
    jint lod,
    jlongArray out_revision,
    jintArray out_numeric,
    jobjectArray out_strings
) {
    (void) clazz;
    if (nbt == NULL || out_revision == NULL || out_numeric == NULL || out_strings == NULL
        || nbt_length < 0 || lod < 0 || lod > 4
        || (*env)->GetArrayLength(env, out_revision) < 1) {
        return CFX_SCAN_BAD_ARGS;
    }
    const int stride = 1 << lod;
    const int side = 16 / stride;
    const int sample_count = side * side;
    const int base_numeric_count = 1 + sample_count * 4;
    const int numeric_capacity = (*env)->GetArrayLength(env, out_numeric);
    if (numeric_capacity < base_numeric_count
        || (*env)->GetArrayLength(env, out_strings) < sample_count * 3) {
        return CFX_SCAN_BAD_ARGS;
    }

    const jsize nbt_size = (*env)->GetArrayLength(env, nbt);
    if (nbt_length > nbt_size)
        return CFX_SCAN_BAD_ARGS;
    jbyte *nbt_bytes = (*env)->GetByteArrayElements(env, nbt, NULL);
    if (nbt_bytes == NULL)
        return CFX_SCAN_ALLOC;
    CfxNbtReader reader = { (const uint8_t *) nbt_bytes, (size_t) nbt_length, 0, 0 };
    CfxChunkNbt chunk;
    memset(&chunk, 0, sizeof(chunk));
    chunk.bottom_y = 0;
    const int parsed = cfxParseRoot(&reader, &chunk);
    (*env)->ReleaseByteArrayElements(env, nbt, nbt_bytes, JNI_ABORT);
    if (!parsed || reader.failed) {
        cfxFreeChunk(&chunk);
        return CFX_SCAN_MALFORMED;
    }

    jint *numeric = calloc((size_t) (1 + sample_count * 4), sizeof(jint));
    CfxSample *samples = sample_count == 0 ? NULL : calloc((size_t) sample_count, sizeof(CfxSample));
    if (numeric == NULL || samples == NULL) {
        free(numeric);
        free(samples);
        cfxFreeChunk(&chunk);
        return CFX_SCAN_ALLOC;
    }

    const int generated = chunk.full && chunk.motion_blocking.count != 0;
    numeric[0] = generated;
    if (generated) {
        int sample_index = 0;
        for (int sample_z = 0; sample_z < side; sample_z++) {
            const int z = sample_z * stride + (stride >> 1);
            for (int sample_x = 0; sample_x < side; sample_x++) {
                const int x = sample_x * stride + (stride >> 1);
                CfxSample *sample = &samples[sample_index];
                if (!cfxSummarizeSample(&chunk, x, z, sample)) {
                    free(numeric);
                    free(samples);
                    cfxFreeChunk(&chunk);
                    return CFX_SCAN_MALFORMED;
                }
                const int offset = 1 + sample_index * 4;
                numeric[offset] = sample->biome_id;
                numeric[offset + 1] = sample->surface_y;
                numeric[offset + 2] = sample->fluid;
                numeric[offset + 3] = sample->fluid_depth;
                sample_index++;
            }
        }
    }

    const jlong revision = generated ? (jlong) chunk.revision : 0;
    (*env)->SetLongArrayRegion(env, out_revision, 0, 1, &revision);
    (*env)->SetIntArrayRegion(env, out_numeric, 0, 1 + sample_count * 4, numeric);
    if (!(*env)->ExceptionCheck(env)
        && numeric_capacity >= base_numeric_count + sample_count) {
        jint *lights = sample_count == 0 ? NULL : malloc(sizeof(jint) * (size_t) sample_count);
        if (sample_count != 0 && lights == NULL) {
            free(numeric);
            free(samples);
            cfxFreeChunk(&chunk);
            return CFX_SCAN_ALLOC;
        }
        for (int i = 0; i < sample_count; i++)
            lights[i] = samples[i].block_light;
        (*env)->SetIntArrayRegion(
            env, out_numeric, base_numeric_count, sample_count, lights
        );
        free(lights);
    }
    if (!(*env)->ExceptionCheck(env) && generated) {
        for (int i = 0; i < sample_count && !(*env)->ExceptionCheck(env); i++) {
            cfxSetString(env, out_strings, i * 3, samples[i].biome_name);
            cfxSetString(env, out_strings, i * 3 + 1, samples[i].surface_name);
            cfxSetString(env, out_strings, i * 3 + 2, samples[i].floor_name);
        }
    }

    free(numeric);
    free(samples);
    cfxFreeChunk(&chunk);
    return (*env)->ExceptionCheck(env) ? CFX_SCAN_ALLOC : CFX_SCAN_OK;
}
