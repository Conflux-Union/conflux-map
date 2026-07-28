package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.core.predict.CubiomesBiomeIds;
import cn.net.rms.confluxmap.nativepredict.NativeChunkNbtScanner;
import net.minecraft.nbt.NbtCompound;

/** Converts a narrow column source into a cheap surface-only chunk summary. */
public final class ChunkSummarizer {
    /**
     * Resolves a block id string (e.g. {@code minecraft:oak_planks}) to its vanilla map-colour
     * id, or a negative value when unknown. Kept as a seam so the registry-backed resolver
     * (which needs a bootstrapped Minecraft) stays out of this NBT-only class and its tests.
     */
    @FunctionalInterface
    public interface MapColorResolver {
        int mapColorId(String blockName);
    }

    private static final MapColorResolver UNRESOLVED = name -> -1;

    private final MapColorResolver mapColors;

    public ChunkSummarizer() {
        this(UNRESOLVED);
    }

    public ChunkSummarizer(final MapColorResolver mapColors) {
        this.mapColors = mapColors == null ? UNRESOLVED : mapColors;
    }

    public SummaryCodec.Chunk summarize(final NbtCompound root) {
        return summarize(new NbtChunkColumnSource(root));
    }

    public SummaryCodec.Chunk summarize(final ChunkColumnSource source) {
        if (source == null || !source.generated()) {
            return SummaryCodec.Chunk.empty();
        }
        final SummaryCodec.Column[] columns = new SummaryCodec.Column[SummaryCodec.COLUMNS];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                columns[z * 16 + x] = summarizeColumn(source, x, z);
            }
        }
        return new SummaryCodec.Chunk(true, source.revision(), columns);
    }

    SummaryCodec.SampledChunk summarizeForLod(final NbtCompound root, final int lod) {
        return summarizeForLod(new NbtChunkColumnSource(root), lod);
    }

    SummaryCodec.SampledChunk summarizeForLod(final ChunkColumnSource source, final int lod) {
        if (lod < 0 || lod > 4) {
            throw new IllegalArgumentException("unsupported summary LOD " + lod);
        }
        final int sampleStride = 1 << lod;
        final int samplesPerSide = 16 / sampleStride;
        if (source == null || !source.generated()) {
            return SummaryCodec.SampledChunk.empty(sampleStride);
        }
        final SummaryCodec.Column[] columns = new SummaryCodec.Column[samplesPerSide * samplesPerSide];
        int sampleIndex = 0;
        for (int sampleZ = 0; sampleZ < samplesPerSide; sampleZ++) {
            final int z = sampleZ * sampleStride + (sampleStride >>> 1);
            for (int sampleX = 0; sampleX < samplesPerSide; sampleX++) {
                final int x = sampleX * sampleStride + (sampleStride >>> 1);
                columns[sampleIndex++] = summarizeColumn(source, x, z);
            }
        }
        return new SummaryCodec.SampledChunk(true, source.revision(), sampleStride, columns);
    }

    SummaryCodec.SampledChunk summarizeNative(final NativeChunkNbtScanner.Chunk chunk) {
        if (chunk == null || !chunk.generated()) {
            return SummaryCodec.SampledChunk.empty(chunk == null ? 16 : chunk.sampleStride());
        }
        final SummaryCodec.Column[] columns = new SummaryCodec.Column[chunk.samples().length];
        for (int i = 0; i < columns.length; i++) {
            final NativeChunkNbtScanner.Sample sample = chunk.samples()[i];
            final int biome = sample.biomeId() >= 0
                ? sample.biomeId()
                : biomeId(sample.biomeName());
            final BlockInfo surface;
            if (sample.fluidKind() == 1) {
                surface = new BlockInfo(SurfaceKind.WATER, 12);
            } else if (sample.fluidKind() == 2) {
                surface = new BlockInfo(SurfaceKind.LAVA, 4);
            } else {
                surface = classify(sample.surfaceBlock(), mapColors);
            }
            final int floorMapColorId = sample.fluidDepth() == 0
                ? ProtoColor.NONE
                : classify(sample.floorBlock(), mapColors).mapColorId;
            columns[i] = new SummaryCodec.Column(
                biome & 255,
                clampShort(sample.surfaceY()),
                surface.kind.ordinal(),
                surface.mapColorId,
                clamp(sample.fluidDepth()),
                floorMapColorId
            );
        }
        return new SummaryCodec.SampledChunk(
            true, chunk.revision(), chunk.sampleStride(), columns
        );
    }

    private SummaryCodec.Column summarizeColumn(
        final ChunkColumnSource source,
        final int x,
        final int z
    ) {
        final int top = source.motionBlockingHeight(x, z);
        final int groundY = top - 1;
        final BlockInfo ground = blockAt(source, x, groundY, z);
        int surfaceY = groundY;
        BlockInfo block = ground;
        int fluidSurfaceY = groundY;
        BlockInfo fluidSurface = ground;
        boolean promotedFluidCover = false;
        // MOTION_BLOCKING excludes collision-less snow layers, so a snow-covered column would
        // otherwise summarize as the grass beneath it and correct predicted snowy terrain to
        // plain green. Some saved 1.17 heightmaps likewise stop below collision-less aquatic
        // plants even though their block state still carries water. Promote either kind of cover
        // from the actual block palette instead of turning the submerged floor into dry land.
        final String cover = source.blockNameAt(x, groundY + 1, z);
        final SurfaceKind coverFluid = source.fluidKindAt(x, groundY + 1, z);
        if (coverFluid == SurfaceKind.WATER || coverFluid == SurfaceKind.LAVA) {
            surfaceY = groundY + 1;
            block = blockAt(source, x, surfaceY, z);
            fluidSurfaceY = surfaceY;
            fluidSurface = block;
            promotedFluidCover = true;
        } else if (isSnowCover(cover)) {
            surfaceY = groundY + 1;
            block = classify(cover, mapColors);
        }
        final int biome = source.biomeIdAt(x, surfaceY, z);
        // Fluid depth follows the ground under any snow cover: snow settled on ocean ice must
        // keep its water column so it stays bucket-equivalent to the fluid baseline.
        final boolean hasFluidSurface = fluidSurface.kind == SurfaceKind.WATER
            || fluidSurface.kind == SurfaceKind.ICE;
        final int fluidDepth;
        final int floorMapColorId;
        if (!hasFluidSurface) {
            fluidDepth = 0;
            floorMapColorId = ProtoColor.NONE;
        } else {
            final int oceanFloorHeight = fluidSurface.kind == SurfaceKind.WATER && !promotedFluidCover
                ? source.oceanFloorHeight(x, z)
                : ChunkColumnSource.NO_HEIGHT;
            final int floorY = oceanFloorHeight != ChunkColumnSource.NO_HEIGHT
                && oceanFloorHeight <= fluidSurfaceY
                ? oceanFloorHeight - 1
                : scanFluidFloorY(source, x, fluidSurfaceY, z);
            final int scannedDepth = clamp(fluidSurfaceY - floorY);
            // Ice resting directly on solid ground (spikes, glaciers) has no fluid column; the
            // scan's minimum depth of 1 would otherwise fabricate an underwater floor.
            fluidDepth = fluidSurface.kind == SurfaceKind.ICE && scannedDepth <= 1 ? 0 : scannedDepth;
            floorMapColorId = fluidDepth == 0
                ? ProtoColor.NONE
                : classify(source.blockNameAt(x, floorY, z), mapColors).mapColorId;
        }
        return new SummaryCodec.Column(
            biome & 255,
            clampShort(surfaceY),
            block.kind.ordinal(),
            block.mapColorId,
            fluidDepth,
            floorMapColorId
        );
    }

    private BlockInfo blockAt(final ChunkColumnSource source, final int x, final int y, final int z) {
        final SurfaceKind fluid = source.fluidKindAt(x, y, z);
        if (fluid == SurfaceKind.WATER) {
            return new BlockInfo(SurfaceKind.WATER, 12);
        }
        if (fluid == SurfaceKind.LAVA) {
            return new BlockInfo(SurfaceKind.LAVA, 4);
        }
        return classify(source.blockNameAt(x, y, z), mapColors);
    }

    private static int scanFluidFloorY(final ChunkColumnSource source, final int x, final int surfaceY, final int z) {
        int floorY = surfaceY - 1;
        while (floorY >= source.bottomY() && isUnderwaterColumnBlock(source, x, floorY, z)) {
            floorY--;
        }
        return floorY;
    }

    private static boolean isUnderwaterColumnBlock(
        final ChunkColumnSource source,
        final int x,
        final int y,
        final int z
    ) {
        if (source.fluidKindAt(x, y, z) == SurfaceKind.WATER) {
            return true;
        }
        final String name = source.blockNameAt(x, y, z);
        return name.contains("bubble_column") || isKelp(name) || name.contains("seagrass")
            || name.contains("sea_pickle") || name.contains("coral_fan");
    }

    private static boolean isKelp(final String name) {
        return "minecraft:kelp".equals(name) || "minecraft:kelp_plant".equals(name);
    }

    /** Non-motion-blocking snow cover that visually replaces the block it rests on. */
    private static boolean isSnowCover(final String name) {
        return "minecraft:snow".equals(name) || "minecraft:powder_snow".equals(name);
    }

    /** Classifies one block id string into its surface kind and map colour; also used by {@code FlatWorldBaseline}. */
    public static BlockInfo classify(final String value, final MapColorResolver mapColors) {
        final String name = value == null ? "minecraft:air" : value;
        final MapColorResolver resolver = mapColors == null ? UNRESOLVED : mapColors;
        final SurfaceKind kind;
        final int color;
        if (name.contains("water") || isKelp(name)) {
            kind = SurfaceKind.WATER;
            color = 12;
        } else if (name.contains("lava")) {
            kind = SurfaceKind.LAVA;
            color = 4;
        } else if (name.contains("leaves") || name.contains("vine")) {
            kind = SurfaceKind.FOLIAGE;
            color = resolveOr(resolver, name, 7);
        } else if (name.contains("snow") || name.contains("powder_snow")) {
            kind = SurfaceKind.SNOW;
            color = resolveOr(resolver, name, 3);
        } else if (name.contains("ice")) {
            kind = SurfaceKind.ICE;
            color = resolveOr(resolver, name, 12);
        } else if (name.endsWith("sand") || name.contains("sandstone")) {
            kind = SurfaceKind.SAND;
            color = resolveOr(resolver, name, 2);
        } else if (name.contains("bedrock")) {
            kind = SurfaceKind.BEDROCK_CEILING;
            color = resolveOr(resolver, name, 11);
        } else if (name.endsWith("air") || name.endsWith("cave_air") || name.endsWith("void_air")) {
            kind = SurfaceKind.UNKNOWN;
            color = ProtoColor.NONE;
        } else {
            kind = SurfaceKind.LAND;
            // Heuristic fallback for when no registry resolver is wired (tests) or the name is
            // unknown to it: enough to flag non-natural block names through the same wire.
            color = resolveOr(resolver, name, name.contains("stone") || name.contains("brick") || name.contains("concrete") ? 11 : 1);
        }
        return new BlockInfo(kind, color);
    }

    /**
     * The registry colour when available, else the heuristic fallback. Id 0 (CLEAR, e.g. glass)
     * also falls back: the client renders corrected pixels straight from the map-colour table,
     * and a transparent pixel would punch a hole where a block demonstrably exists.
     */
    private static int resolveOr(final MapColorResolver mapColors, final String name, final int fallback) {
        final int resolved = mapColors.mapColorId(name);
        return resolved > 0 ? resolved : fallback;
    }

    private static int clamp(final int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static short clampShort(final int value) {
        return (short) Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, value));
    }

    private static int biomeId(final String name) {
        if (name == null) {
            return 1;
        }
        final int separator = name.indexOf(':');
        final String path = separator >= 0 ? name.substring(separator + 1) : name;
        return CubiomesBiomeIds.idForName(path).orElse(1);
    }

    /** One classified block: its map surface kind and vanilla map colour id. */
    public record BlockInfo(SurfaceKind kind, int mapColorId) {
    }

    private static final class ProtoColor {
        private static final int NONE = 255;
    }
}
