package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
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
                final int index = z * 16 + x;
                final int top = source.motionBlockingHeight(x, z);
                final int groundY = top - 1;
                final BlockInfo ground = blockAt(source, x, groundY, z);
                int surfaceY = groundY;
                BlockInfo block = ground;
                // MOTION_BLOCKING excludes collision-less snow layers, so a snow-covered column
                // would otherwise summarize as the grass beneath it and correct predicted snowy
                // terrain to plain green. Promote the cover to the surface, mirroring the client
                // capture's snow-layer promotion.
                final String cover = source.blockNameAt(x, groundY + 1, z);
                if (isSnowCover(cover)) {
                    surfaceY = groundY + 1;
                    block = classify(cover, mapColors);
                }
                final int biome = source.biomeIdAt(x, surfaceY, z);
                // Fluid depth follows the ground under any snow cover: snow settled on ocean ice
                // must keep its water column so it stays bucket-equivalent to the fluid baseline.
                final boolean fluidSurface = ground.kind == SurfaceKind.WATER || ground.kind == SurfaceKind.ICE;
                final int fluidDepth;
                if (!fluidSurface) {
                    fluidDepth = 0;
                } else {
                    final int oceanFloorHeight = ground.kind == SurfaceKind.WATER
                        ? source.oceanFloorHeight(x, z)
                        : ChunkColumnSource.NO_HEIGHT;
                    if (oceanFloorHeight != ChunkColumnSource.NO_HEIGHT) {
                        fluidDepth = clamp(top - oceanFloorHeight);
                    } else {
                        final int scanned = scanFluidDepth(source, x, groundY, z);
                        // Ice resting directly on solid ground (spikes, glaciers) has no fluid column;
                        // the scan's minimum depth of 1 would bucket-mismatch land baselines and
                        // fabricate corrections there.
                        fluidDepth = ground.kind == SurfaceKind.ICE && scanned <= 1 ? 0 : scanned;
                    }
                }
                columns[index] = new SummaryCodec.Column(
                    biome & 255, clampShort(surfaceY), block.kind.ordinal(), block.mapColorId, fluidDepth
                );
            }
        }
        return new SummaryCodec.Chunk(true, source.revision(), columns);
    }

    private BlockInfo blockAt(final ChunkColumnSource source, final int x, final int y, final int z) {
        return classify(source.blockNameAt(x, y, z), mapColors);
    }

    private static int scanFluidDepth(final ChunkColumnSource source, final int x, final int surfaceY, final int z) {
        int floorY = surfaceY - 1;
        while (floorY >= source.bottomY() && isUnderwaterColumnBlock(source.blockNameAt(x, floorY, z))) {
            floorY--;
        }
        return clamp(surfaceY - floorY);
    }

    private static boolean isUnderwaterColumnBlock(final String name) {
        return name.contains("water") || name.contains("bubble_column") || isKelp(name)
            || name.contains("seagrass") || name.contains("sea_pickle") || name.contains("coral_fan");
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

    /** One classified block: its map surface kind and vanilla map colour id. */
    public record BlockInfo(SurfaceKind kind, int mapColorId) {
    }

    private static final class ProtoColor {
        private static final int NONE = 255;
    }
}
