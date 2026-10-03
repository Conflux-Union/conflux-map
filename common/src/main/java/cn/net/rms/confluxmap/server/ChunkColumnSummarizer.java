package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.net.SummaryCodec;

/** Platform-neutral conversion from block columns to the companion's surface summary. */
public final class ChunkColumnSummarizer {
    @FunctionalInterface
    public interface MapColorResolver {
        int mapColorId(String blockName);
    }

    public record BlockInfo(SurfaceKind kind, int mapColorId) {
    }

    private static final int MAP_COLOR_NONE = 255;
    private static final MapColorResolver UNRESOLVED = name -> -1;
    private final MapColorResolver mapColors;

    public ChunkColumnSummarizer() {
        this(UNRESOLVED);
    }

    public ChunkColumnSummarizer(final MapColorResolver mapColors) {
        this.mapColors = mapColors == null ? UNRESOLVED : mapColors;
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

    public SummaryCodec.SampledChunk summarizeForLod(
        final ChunkColumnSource source,
        final int lod
    ) {
        if (lod < 0 || lod > 4) {
            throw new IllegalArgumentException("unsupported summary LOD " + lod);
        }
        final int stride = 1 << lod;
        final int samplesPerSide = 16 / stride;
        if (source == null || !source.generated()) {
            return SummaryCodec.SampledChunk.empty(stride);
        }
        final SummaryCodec.Column[] columns = new SummaryCodec.Column[
            samplesPerSide * samplesPerSide
        ];
        int index = 0;
        for (int sampleZ = 0; sampleZ < samplesPerSide; sampleZ++) {
            final int z = sampleZ * stride + (stride >>> 1);
            for (int sampleX = 0; sampleX < samplesPerSide; sampleX++) {
                final int x = sampleX * stride + (stride >>> 1);
                columns[index++] = summarizeColumn(source, x, z);
            }
        }
        return new SummaryCodec.SampledChunk(true, source.revision(), stride, columns);
    }

    private SummaryCodec.Column summarizeColumn(
        final ChunkColumnSource source,
        final int x,
        final int z
    ) {
        final int groundY = source.motionBlockingHeight(x, z) - 1;
        int surfaceY = groundY;
        BlockInfo surface = blockAt(source, x, groundY, z);
        String overlayMaterial = "";
        String descentTop = null;
        int descentTopY = groundY;
        while (surfaceY > source.bottomY()
            && isLightPermeableCover(source.blockNameAt(x, surfaceY, z))) {
            if (descentTop == null) {
                descentTop = source.blockNameAt(x, surfaceY, z);
                descentTopY = surfaceY;
            }
            surfaceY--;
            surface = blockAt(source, x, surfaceY, z);
        }
        if (descentTop != null && surface.kind == SurfaceKind.UNKNOWN) {
            // The cover descent stops at the first non-cover block, and air is not one: a glass
            // roof or bridge with an air gap above real ground must keep descending through the
            // air like the client's authoritative scan, or the glass would be promoted over
            // ground the client still renders beneath it.
            while (surfaceY > source.bottomY()
                && isAir(source.blockNameAt(x, surfaceY, z))) {
                surfaceY--;
                surface = blockAt(source, x, surfaceY, z);
            }
        }
        if (descentTop != null && surface.kind == SurfaceKind.UNKNOWN) {
            // The light-permeable cover never reached solid ground (quadra-gen's flat glass
            // quadrant, glass bridges over the void): the cover itself is what a viewer sees.
            // Promote it to the surface so the synced column renders like the client's
            // authoritative capture of the same column instead of an unknown air surface
            // tinted by a glass overlay.
            surface = classify(descentTop, mapColors);
            surfaceY = descentTopY;
            descentTop = null;
        }
        int fluidSurfaceY = surfaceY;
        BlockInfo fluidSurface = surface;
        boolean promotedFluidCover = false;
        if (descentTop != null) {
            overlayMaterial = source.materialIdAt(x, descentTopY, z);
        } else {
            final String coverName = source.blockNameAt(x, groundY + 1, z);
            final SurfaceKind coverFluid = source.fluidKindAt(x, groundY + 1, z);
            if (coverFluid == SurfaceKind.WATER || coverFluid == SurfaceKind.LAVA) {
                surfaceY++;
                surface = blockAt(source, x, surfaceY, z);
                fluidSurfaceY = surfaceY;
                fluidSurface = surface;
                promotedFluidCover = true;
            } else if (isSurfaceCover(coverName)) {
                surfaceY++;
                surface = classify(coverName, mapColors);
            } else if (isOverlayDecoration(source, x, groundY + 1, z, coverName)) {
                overlayMaterial = source.materialIdAt(x, groundY + 1, z);
            }
        }
        final int biome = source.biomeIdAt(x, surfaceY, z);
        final boolean hasFluid = fluidSurface.kind == SurfaceKind.WATER
            || fluidSurface.kind == SurfaceKind.ICE;
        final int fluidDepth;
        final int floorColor;
        final String floorMaterial;
        if (!hasFluid) {
            fluidDepth = 0;
            floorColor = MAP_COLOR_NONE;
            floorMaterial = "";
        } else {
            final int oceanFloor = fluidSurface.kind == SurfaceKind.WATER && !promotedFluidCover
                ? source.oceanFloorHeight(x, z) : ChunkColumnSource.NO_HEIGHT;
            final int floorY = oceanFloor != ChunkColumnSource.NO_HEIGHT
                && oceanFloor <= fluidSurfaceY
                ? oceanFloor - 1 : scanFluidFloorY(source, x, fluidSurfaceY, z);
            final int depth = clamp(fluidSurfaceY - floorY);
            fluidDepth = fluidSurface.kind == SurfaceKind.ICE && depth <= 1 ? 0 : depth;
            floorColor = fluidDepth == 0
                ? MAP_COLOR_NONE
                : classify(source.blockNameAt(x, floorY, z), mapColors).mapColorId;
            floorMaterial = fluidDepth == 0 ? "" : source.materialIdAt(x, floorY, z);
        }
        return new SummaryCodec.Column(
            biome & 255,
            clampShort(surfaceY),
            surface.kind.ordinal(),
            surface.mapColorId,
            fluidDepth,
            floorColor,
            clampLight(source.blockLightAbove(x, surfaceY, z)),
            materialId(surface.kind, source.materialIdAt(x, surfaceY, z)),
            floorMaterial,
            overlayMaterial
        );
    }

    private static String materialId(final SurfaceKind kind, final String fallback) {
        if (kind == SurfaceKind.WATER) {
            return "minecraft:water";
        }
        if (kind == SurfaceKind.LAVA) {
            return "minecraft:lava";
        }
        return fallback == null ? "" : fallback;
    }

    private static int clampLight(final int light) {
        return Math.max(0, Math.min(15, light));
    }

    private BlockInfo blockAt(
        final ChunkColumnSource source,
        final int x,
        final int y,
        final int z
    ) {
        final SurfaceKind fluid = source.fluidKindAt(x, y, z);
        if (fluid == SurfaceKind.WATER) {
            return new BlockInfo(SurfaceKind.WATER, 12);
        }
        if (fluid == SurfaceKind.LAVA) {
            return new BlockInfo(SurfaceKind.LAVA, 4);
        }
        return classify(source.blockNameAt(x, y, z), mapColors);
    }

    public static BlockInfo classify(final String value, final MapColorResolver mapColors) {
        final String name = value == null ? "minecraft:air" : value;
        final MapColorResolver resolver = mapColors == null ? UNRESOLVED : mapColors;
        final SurfaceKind kind;
        final int fallback;
        if (name.contains("water") || isKelp(name)) {
            kind = SurfaceKind.WATER;
            fallback = 12;
        } else if (name.contains("lava")) {
            kind = SurfaceKind.LAVA;
            fallback = 4;
        } else if (name.contains("leaves") || name.contains("vine")) {
            kind = SurfaceKind.FOLIAGE;
            fallback = 7;
        } else if (name.contains("snow") || name.contains("powder_snow")) {
            kind = SurfaceKind.SNOW;
            fallback = 3;
        } else if (name.contains("ice")) {
            kind = SurfaceKind.ICE;
            fallback = 12;
        } else if (name.endsWith("sand") || name.contains("sandstone")) {
            kind = SurfaceKind.SAND;
            fallback = 2;
        } else if (name.contains("bedrock")) {
            kind = SurfaceKind.BEDROCK_CEILING;
            fallback = 11;
        } else if (name.endsWith("air")) {
            return new BlockInfo(SurfaceKind.UNKNOWN, MAP_COLOR_NONE);
        } else {
            kind = SurfaceKind.LAND;
            fallback = name.contains("stone") || name.contains("brick")
                || name.contains("concrete") ? 11 : 1;
        }
        final int resolved = resolver.mapColorId(name);
        return new BlockInfo(kind, resolved > 0 ? resolved : fallback);
    }

    private static int scanFluidFloorY(
        final ChunkColumnSource source,
        final int x,
        final int surfaceY,
        final int z
    ) {
        int y = surfaceY - 1;
        while (y >= source.bottomY() && isUnderwater(source, x, y, z)) {
            y--;
        }
        return y;
    }

    private static boolean isUnderwater(
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

    private static boolean isSurfaceCover(final String name) {
        return "minecraft:snow".equals(name) || "minecraft:powder_snow".equals(name)
            || name != null && name.endsWith("_carpet");
    }

    /**
     * Motion-blocking blocks that do not stop light, so the surface a viewer actually sees is
     * the one beneath them. Deliberately the glass family only: tinted glass blocks light
     * (opacity 15) and must remain the surface, and other translucent shapes (slabs, stairs)
     * stay with the heightmap so this name-based approximation never over-descends. The client's
     * authoritative floor scan treats the same blocks as transparent overlays.
     */
    private static boolean isLightPermeableCover(final String name) {
        if (name == null || name.endsWith("tinted_glass")) {
            return false;
        }
        return "minecraft:glass".equals(name) || name.endsWith("_stained_glass")
            || "minecraft:glass_pane".equals(name) || name.endsWith("_stained_glass_pane");
    }

    /**
     * Air never stops the authoritative scan, so the name-based scan must cross it too. Exact
     * names only: a suffix match would swallow unrelated blocks whose id merely ends in "air".
     */
    private static boolean isAir(final String name) {
        return "minecraft:air".equals(name) || "minecraft:cave_air".equals(name)
            || "minecraft:void_air".equals(name);
    }

    /**
     * Non-colliding decoration standing on the surface (flowers, roses, mushrooms, fire, ...)
     * worth reporting as an overlay so the synced map composites it like the client does.
     * Bulk foliage (the grass family) is excluded: its tint is visually absorbed by the block
     * beneath it, and it would turn most vegetated columns into patch records.
     */
    private static boolean isOverlayDecoration(
        final ChunkColumnSource source,
        final int x,
        final int y,
        final int z,
        final String name
    ) {
        if (name == null || name.endsWith("air")) {
            return false;
        }
        if (source.fluidKindAt(x, y, z) != SurfaceKind.UNKNOWN) {
            return false;
        }
        return !name.contains("grass") && !name.contains("fern");
    }

    private static int clamp(final int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static short clampShort(final int value) {
        return (short) Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, value));
    }
}
