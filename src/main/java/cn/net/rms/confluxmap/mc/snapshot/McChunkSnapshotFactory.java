package cn.net.rms.confluxmap.mc.snapshot;

import cn.net.rms.confluxmap.core.color.LightTint;
import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.util.Argb;
import cn.net.rms.confluxmap.mc.color.BiomeTintResolver;
import cn.net.rms.confluxmap.mc.color.SpriteColorSampler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.SnowBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.Heightmap;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Builds immutable {@link ChunkSnapshot}s from live client chunks, per
 * surface-color-sampling.md §1-§3 (top-down surface/color/tint) for
 * {@link MapLayer.Type#SURFACE}/{@link MapLayer.Type#END_SURFACE}, and per
 * cave-nether-layers.md §2 (bounded pivot-relative floor scan) for every other
 * layer. Must only be called on the main thread; the produced snapshot is safe
 * to hand to worker threads.
 */
public final class McChunkSnapshotFactory {
    /** Brightness of the solid-rock cross-section drawn where the floor scan finds no opening. */
    private static final float CROSS_SECTION_DARKEN = 0.4f;
    /** cave-nether-layers.md §2.1: the upward branch's fixed search window above the pivot. */
    private static final int UPWARD_SCAN_CAP = 10;

    private final MinecraftClient client;
    private final SpriteColorSampler sampler;
    private final BiomeTintResolver tintResolver;

    public McChunkSnapshotFactory(
        final MinecraftClient client,
        final SpriteColorSampler sampler,
        final BiomeTintResolver tintResolver
    ) {
        this.client = client;
        this.sampler = sampler;
        this.tintResolver = tintResolver;
    }

    /**
     * Null if the chunk is not currently loaded. {@code pivotY} is only consulted for
     * layers using the cave-nether-layers.md §2 floor scan (ignored for SURFACE/END_SURFACE,
     * which keep using the world heightmap); see {@link cn.net.rms.confluxmap.mc.world.LayerSelector}
     * for how callers derive it (debounced player Y, a slice's fixed Y, or the nether-roof pivot).
     */
    public ChunkSnapshot snapshot(final int chunkX, final int chunkZ, final MapLayer layer, final int pivotY, final long sessionToken) {
        final ClientWorld world = client.world;
        if (world == null) {
            return null;
        }
        final WorldChunk chunk = (WorldChunk) world.getChunkManager().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (chunk == null) {
            return null;
        }

        final short[] surfaceY = new short[ChunkSnapshot.COLUMNS];
        final byte[] fluidDepth = new byte[ChunkSnapshot.COLUMNS];
        final int[] baseArgb = new int[ChunkSnapshot.COLUMNS];
        final int[] tintArgb = new int[ChunkSnapshot.COLUMNS];
        final int[] overlayArgb = new int[ChunkSnapshot.COLUMNS];
        final byte[] kind = new byte[ChunkSnapshot.COLUMNS];

        final BlockPos.Mutable pos = new BlockPos.Mutable();
        final int baseX = chunkX << 4;
        final int baseZ = chunkZ << 4;

        if (layer.type() == MapLayer.Type.SURFACE || layer.type() == MapLayer.Type.END_SURFACE) {
            final ClientPlayerEntity player = client.player;
            final int playerY = player != null ? player.getBlockPos().getY() : world.getBottomY();
            final Heightmap heightmap = chunk.getHeightmap(Heightmap.Type.MOTION_BLOCKING);
            final int bottomY = world.getBottomY();
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    sampleColumn(
                        chunk, world, pos, baseX, baseZ, x, z, bottomY, playerY, heightmap, z * 16 + x,
                        surfaceY, fluidDepth, baseArgb, tintArgb, overlayArgb, kind
                    );
                }
            }
        } else {
            final boolean netherAmbient = isNetherLayer(layer.type());
            final int worldMinY = world.getBottomY();
            final int worldMaxY = world.getTopY();
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    sampleFloorColumn(
                        chunk, world, pos, baseX, baseZ, x, z, pivotY, worldMinY, worldMaxY, netherAmbient, z * 16 + x,
                        surfaceY, fluidDepth, baseArgb, tintArgb, overlayArgb, kind
                    );
                }
            }
        }
        return new ChunkSnapshot(chunkX, chunkZ, sessionToken, surfaceY, fluidDepth, baseArgb, tintArgb, overlayArgb, kind);
    }

    private void sampleColumn(
        final WorldChunk chunk,
        final ClientWorld world,
        final BlockPos.Mutable pos,
        final int baseX,
        final int baseZ,
        final int localX,
        final int localZ,
        final int bottomY,
        final int playerY,
        final Heightmap heightmap,
        final int index,
        final short[] surfaceY,
        final byte[] fluidDepth,
        final int[] baseArgb,
        final int[] tintArgb,
        final int[] overlayArgb,
        final byte[] kind
    ) {
        final int worldX = baseX + localX;
        final int worldZ = baseZ + localZ;

        int y = heightmap.get(localX, localZ) - 1;
        if (y < bottomY) {
            writeVoid(index, playerY, surfaceY, kind, baseArgb, tintArgb, overlayArgb, fluidDepth);
            return;
        }
        pos.set(worldX, y, worldZ);
        BlockState state = collapse(chunk.getBlockState(pos));

        BlockState topOverlay = null;
        int topOverlayY = 0;
        BlockState bottomOverlay = null;
        int bottomOverlayY = 0;
        boolean descended = false;

        while (!isOpaque(state, world, pos) && y > bottomY) {
            if (!descended) {
                topOverlay = state;
                topOverlayY = y;
            }
            bottomOverlay = state;
            bottomOverlayY = y;
            descended = true;
            y--;
            pos.setY(y);
            state = collapse(chunk.getBlockState(pos));
        }
        if (!isOpaque(state, world, pos)) {
            // World bottom reached and never found an opaque block: §1 void fallback.
            writeVoid(index, playerY, surfaceY, kind, baseArgb, tintArgb, overlayArgb, fluidDepth);
            return;
        }

        BlockState surfaceState = state;
        int surfaceYVal = y;
        BlockState transparentOverlay = null;
        int transparentOverlayY = 0;
        BlockState foliageOverlay = null;
        int foliageOverlayY = 0;

        if (!descended) {
            pos.set(worldX, surfaceYVal + 1, worldZ);
            final BlockState above = collapse(chunk.getBlockState(pos));
            if (!above.isAir()) {
                foliageOverlay = above;
                foliageOverlayY = surfaceYVal + 1;
            }
        } else if (bottomOverlay.getBlock() instanceof SnowBlock) {
            // §1 snow-layer promotion: the foliage candidate becomes the surface itself.
            surfaceState = bottomOverlay;
            surfaceYVal = bottomOverlayY;
            if (topOverlayY != bottomOverlayY) {
                transparentOverlay = topOverlay;
                transparentOverlayY = topOverlayY;
            }
        } else {
            transparentOverlay = topOverlay;
            transparentOverlayY = topOverlayY;
            if (topOverlayY != bottomOverlayY) {
                foliageOverlay = bottomOverlay;
                foliageOverlayY = bottomOverlayY;
            }
        }

        final Block surfaceBlock = surfaceState.getBlock();
        final SurfaceKind resolvedKind = classifySurfaceKind(surfaceBlock);
        final boolean seafloorScan = resolvedKind == SurfaceKind.WATER || resolvedKind == SurfaceKind.ICE;

        BlockState seafloorState = null;
        int seafloorY = 0;
        boolean bottomless = false;

        if (seafloorScan) {
            int scanY = surfaceYVal - 1;
            pos.set(worldX, scanY, worldZ);
            BlockState scan = collapse(chunk.getBlockState(pos));
            while (scanY > bottomY && seafloorContinues(scan, world, pos)) {
                if (isSeafloorCapturable(scan)) {
                    if (transparentOverlay == null) {
                        transparentOverlay = scan;
                        transparentOverlayY = scanY;
                    } else if (foliageOverlay == null && !scan.equals(transparentOverlay)) {
                        foliageOverlay = scan;
                        foliageOverlayY = scanY;
                    }
                }
                scanY--;
                pos.setY(scanY);
                scan = collapse(chunk.getBlockState(pos));
            }
            if (scanY <= bottomY && seafloorContinues(scan, world, pos)) {
                bottomless = true;
            } else {
                seafloorState = scan;
                seafloorY = scanY;
            }
        }

        writeSurface(
            index, worldX, worldZ, pos, world,
            surfaceState, surfaceYVal, resolvedKind,
            transparentOverlay, transparentOverlayY, foliageOverlay, foliageOverlayY,
            seafloorState, seafloorY, bottomless,
            surfaceY, fluidDepth, baseArgb, tintArgb, overlayArgb, kind
        );
    }

    private void writeSurface(
        final int index,
        final int worldX,
        final int worldZ,
        final BlockPos.Mutable pos,
        final ClientWorld world,
        final BlockState surfaceState,
        final int surfaceYVal,
        final SurfaceKind resolvedKind,
        final BlockState transparentOverlay,
        final int transparentOverlayY,
        final BlockState foliageOverlay,
        final int foliageOverlayY,
        final BlockState seafloorState,
        final int seafloorY,
        final boolean bottomless,
        final short[] surfaceY,
        final byte[] fluidDepth,
        final int[] baseArgb,
        final int[] tintArgb,
        final int[] overlayArgb,
        final byte[] kind
    ) {
        pos.set(worldX, surfaceYVal, worldZ);
        final int surfaceBaseColor = sampler.colorFor(surfaceState, world, pos);
        final int surfaceTintColor = tintResolver.resolve(surfaceState, world, pos);

        if (resolvedKind == SurfaceKind.WATER || resolvedKind == SurfaceKind.ICE) {
            // baseArgb/tintArgb hold the water/ice surface's own color; overlayArgb is repurposed to
            // hold the pre-composited floor (seafloor + underwater overlays) beneath it - see §5.
            int waterColor = Argb.multiply(surfaceBaseColor, surfaceTintColor);
            if (transparentOverlay != null) {
                waterColor = Argb.over(coloredLayer(transparentOverlay, transparentOverlayY, worldX, worldZ, pos, world), waterColor);
            }
            baseArgb[index] = waterColor;
            tintArgb[index] = 0xFFFFFFFF;

            int floorComposite = Argb.TRANSPARENT;
            int depth = 0;
            if (!bottomless && seafloorState != null) {
                floorComposite = coloredLayer(seafloorState, seafloorY, worldX, worldZ, pos, world);
                if (foliageOverlay != null) {
                    floorComposite = Argb.over(
                        coloredLayer(foliageOverlay, foliageOverlayY, worldX, worldZ, pos, world), floorComposite
                    );
                }
                depth = surfaceYVal - seafloorY;
            }
            overlayArgb[index] = floorComposite;
            fluidDepth[index] = (byte) Math.min(Math.max(depth, 0), 127);
        } else {
            baseArgb[index] = surfaceBaseColor;
            tintArgb[index] = surfaceTintColor;

            int overlayComposite = Argb.TRANSPARENT;
            if (foliageOverlay != null) {
                overlayComposite = coloredLayer(foliageOverlay, foliageOverlayY, worldX, worldZ, pos, world);
            }
            if (transparentOverlay != null) {
                final int top = coloredLayer(transparentOverlay, transparentOverlayY, worldX, worldZ, pos, world);
                overlayComposite = overlayComposite == Argb.TRANSPARENT ? top : Argb.over(top, overlayComposite);
            }
            overlayArgb[index] = overlayComposite;
            fluidDepth[index] = 0;
        }
        surfaceY[index] = clampSurfaceY(surfaceYVal);
        kind[index] = (byte) resolvedKind.ordinal();
    }

    /** Sampled base color x its own tint, already composited, for one overlay/seafloor layer. */
    private int coloredLayer(
        final BlockState state,
        final int y,
        final int worldX,
        final int worldZ,
        final BlockPos.Mutable pos,
        final ClientWorld world
    ) {
        pos.set(worldX, y, worldZ);
        final int base = sampler.colorFor(state, world, pos);
        final int tint = tintResolver.resolve(state, world, pos);
        return Argb.multiply(base, tint);
    }

    /**
     * cave-nether-layers.md §2.1's bounded nearest-floor scan around {@code pivotY}:
     * unbounded downward when the pivot itself sits in open space, or capped {@link
     * #UPWARD_SCAN_CAP} blocks upward when the pivot starts inside solid/lava material.
     * Shared by every layer that isn't a top-down surface scan (CAVE_AUTO/CAVE_SLICE,
     * NETHER_CURRENT/NETHER_SLICE, NETHER_CEILING) - only the pivot Y and whether the
     * nether ambient-light floor applies (§5.2, via {@link #isNetherLayer}) differ.
     *
     * <p>Unlike the surface scan, {@link ChunkSnapshot#surfaceY} here stores the actual
     * solid/lava block's Y (not the spec's own "one above" return convention) to match
     * every other layer's "surfaceY = the ground block" contract, since {@link
     * cn.net.rms.confluxmap.core.tile.TileService}'s height/slope shading reads that
     * field generically regardless of layer.
     */
    private void sampleFloorColumn(
        final WorldChunk chunk,
        final ClientWorld world,
        final BlockPos.Mutable pos,
        final int baseX,
        final int baseZ,
        final int localX,
        final int localZ,
        final int pivotY,
        final int worldMinY,
        final int worldMaxY,
        final boolean netherAmbient,
        final int index,
        final short[] surfaceY,
        final byte[] fluidDepth,
        final int[] baseArgb,
        final int[] tintArgb,
        final int[] overlayArgb,
        final byte[] kind
    ) {
        final int worldX = baseX + localX;
        final int worldZ = baseZ + localZ;
        final int clampedPivot = MathHelper.clamp(pivotY, worldMinY, worldMaxY - 1);

        pos.set(worldX, clampedPivot, worldZ);
        final boolean pivotOpen = isOpenForFloorScan(collapse(chunk.getBlockState(pos)), world, pos);

        final int openY;
        if (pivotOpen) {
            int y = clampedPivot;
            boolean found = false;
            while (y > worldMinY) {
                y--;
                pos.setY(y);
                if (!isOpenForFloorScan(collapse(chunk.getBlockState(pos)), world, pos)) {
                    found = true;
                    break;
                }
            }
            openY = found ? y + 1 : worldMinY;
        } else {
            final int upperBound = Math.min(clampedPivot + UPWARD_SCAN_CAP, worldMaxY - 1);
            int y = clampedPivot;
            boolean found = false;
            while (y < upperBound) {
                y++;
                pos.setY(y);
                if (isOpenForFloorScan(collapse(chunk.getBlockState(pos)), world, pos)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                // Solid rock throughout the window: render a darkened cross-section of the
                // block at the pivot instead of the spec's transparent sentinel - a mostly
                // blank map reads as broken, and the cross-section shows ores near the
                // pivot Y as colored specks (Xaero-style cave view, deliberate deviation).
                pos.set(worldX, clampedPivot, worldZ);
                final BlockState rockState = collapse(chunk.getBlockState(pos));
                final int rockBase = sampler.colorFor(rockState, world, pos);
                final int rockTint = tintResolver.resolve(rockState, world, pos);
                surfaceY[index] = clampSurfaceY(clampedPivot);
                kind[index] = (byte) SurfaceKind.LAND.ordinal();
                baseArgb[index] = Argb.scale(Argb.multiply(rockBase, rockTint), CROSS_SECTION_DARKEN);
                tintArgb[index] = 0xFFFFFFFF;
                overlayArgb[index] = Argb.TRANSPARENT;
                fluidDepth[index] = 0;
                return;
            }
            openY = y;
        }

        final int solidY = openY - 1;
        if (solidY < worldMinY) {
            // Only reachable if the downward scan ran all the way to the world floor without
            // finding anything solid (an all-air column) - treat exactly like "no floor found".
            writeVoid(index, pivotY, surfaceY, kind, baseArgb, tintArgb, overlayArgb, fluidDepth);
            return;
        }

        pos.set(worldX, solidY, worldZ);
        final BlockState solidState = collapse(chunk.getBlockState(pos));
        final Block solidBlock = solidState.getBlock();

        final int solidBase = sampler.colorFor(solidState, world, pos);
        final int solidTint = tintResolver.resolve(solidState, world, pos);
        // Light is baked directly into baseArgb (tintArgb left neutral) rather than threading a
        // separate light channel through ChunkSnapshot/RegionColumns/the disk codec - see
        // McChunkSnapshotFactory's class javadoc and the S7 report for why.
        final int litColor = applyLight(Argb.multiply(solidBase, solidTint), pos, world, solidBlock, netherAmbient);

        int overlayColor = Argb.TRANSPARENT;
        if (solidY + 1 < worldMaxY) {
            pos.set(worldX, solidY + 1, worldZ);
            final BlockState above = collapse(chunk.getBlockState(pos));
            if (isFloorOverlayCandidate(above)) {
                final int overlayBase = sampler.colorFor(above, world, pos);
                final int overlayTint = tintResolver.resolve(above, world, pos);
                overlayColor = applyLight(Argb.multiply(overlayBase, overlayTint), pos, world, above.getBlock(), netherAmbient);
            }
        }

        surfaceY[index] = clampSurfaceY(solidY);
        kind[index] = (byte) classifySurfaceKind(solidBlock).ordinal();
        baseArgb[index] = litColor;
        tintArgb[index] = 0xFFFFFFFF;
        overlayArgb[index] = overlayColor;
        fluidDepth[index] = 0;
    }

    /** §2.1's pivot-scan open test: non-opaque (§1's opacity test) and not lava. */
    private static boolean isOpenForFloorScan(final BlockState state, final ClientWorld world, final BlockPos pos) {
        return !isOpaque(state, world, pos) && state.getBlock() != Blocks.LAVA;
    }

    /** §2.1's overhang/foliage overlay eligibility: snow, or anything that isn't air/lava/water. */
    private static boolean isFloorOverlayCandidate(final BlockState state) {
        if (state.getBlock() instanceof SnowBlock) {
            return true;
        }
        return !state.isAir() && state.getBlock() != Blocks.LAVA && state.getBlock() != Blocks.WATER;
    }

    /** §2/§6 unified block-type classification, shared by the surface scan and the floor scan. */
    private static SurfaceKind classifySurfaceKind(final Block surfaceBlock) {
        if (surfaceBlock == Blocks.LAVA) {
            return SurfaceKind.LAVA;
        } else if (surfaceBlock == Blocks.WATER) {
            return SurfaceKind.WATER;
        } else if (surfaceBlock == Blocks.ICE) {
            return SurfaceKind.ICE;
        } else if (surfaceBlock instanceof SnowBlock) {
            return SurfaceKind.SNOW;
        } else if (surfaceBlock instanceof LeavesBlock) {
            return SurfaceKind.FOLIAGE;
        } else if (surfaceBlock == Blocks.SAND || surfaceBlock == Blocks.RED_SAND) {
            return SurfaceKind.SAND;
        } else {
            return SurfaceKind.LAND;
        }
    }

    /**
     * cave-nether-layers.md §5.1/§5.2: darken/tint {@code argb} by the block+sky light at
     * {@code pos}, with lava/magma's block-light forced to 14 (§3's lava-glow override) and
     * the result forced fully transparent when both channels are exactly zero (§5.3 - the
     * same treatment as a "no floor found" column). See {@link LightTint} for the
     * (simplified) block-light/sky-light -> color curve itself.
     */
    private static int applyLight(
        final int argb,
        final BlockPos pos,
        final ClientWorld world,
        final Block block,
        final boolean netherAmbient
    ) {
        final int skyLevel = world.getLightLevel(LightType.SKY, pos);
        final int blockLevel = block == Blocks.LAVA || block == Blocks.MAGMA_BLOCK
            ? 14
            : world.getLightLevel(LightType.BLOCK, pos);
        if (blockLevel == 0 && skyLevel == 0) {
            return argb & 0x00FFFFFF;
        }
        return Argb.multiply(argb, LightTint.multiplier(blockLevel, skyLevel, netherAmbient));
    }

    /** Whether {@code type} is one of the Nether's floor-scan layers, for §5.2's ambient-light floor. */
    private static boolean isNetherLayer(final MapLayer.Type type) {
        return type == MapLayer.Type.NETHER_CURRENT || type == MapLayer.Type.NETHER_CEILING || type == MapLayer.Type.NETHER_SLICE;
    }

    private void writeVoid(
        final int index,
        final int playerY,
        final short[] surfaceY,
        final byte[] kind,
        final int[] baseArgb,
        final int[] tintArgb,
        final int[] overlayArgb,
        final byte[] fluidDepth
    ) {
        surfaceY[index] = clampSurfaceY(playerY + 1);
        kind[index] = (byte) SurfaceKind.VOID.ordinal();
        baseArgb[index] = Argb.TRANSPARENT;
        tintArgb[index] = 0xFFFFFFFF;
        overlayArgb[index] = Argb.TRANSPARENT;
        fluidDepth[index] = 0;
    }

    private static short clampSurfaceY(final int v) {
        if (v >= Short.MAX_VALUE) {
            return Short.MAX_VALUE;
        }
        if (v <= ChunkSnapshot.NO_SURFACE) {
            return ChunkSnapshot.NO_SURFACE + 1;
        }
        return (short) v;
    }

    /** §1 opacity test: light-dampening > 0, else a full-square occlusion shape on the top or bottom face. */
    private static boolean isOpaque(final BlockState state, final ClientWorld world, final BlockPos pos) {
        if (state.getOpacity(world, pos) > 0) {
            return true;
        }
        if (!state.isOpaque()) {
            return false;
        }
        return state.isSideSolidFullSquare(world, pos, Direction.DOWN)
            || state.isSideSolidFullSquare(world, pos, Direction.UP);
    }

    /** §1 seafloor-scan continuation: dampening < 5 and not leaves. */
    private static boolean seafloorContinues(final BlockState state, final ClientWorld world, final BlockPos pos) {
        return state.getOpacity(world, pos) < 5 && !(state.getBlock() instanceof LeavesBlock);
    }

    /** §1 seafloor-scan capture eligibility: not water/ice/air/bubble-column, and counts on the motion-blocking heightmap. */
    private static boolean isSeafloorCapturable(final BlockState state) {
        final Block block = state.getBlock();
        if (state.isAir() || block == Blocks.WATER || block == Blocks.ICE || block == Blocks.BUBBLE_COLUMN) {
            return false;
        }
        return Heightmap.Type.MOTION_BLOCKING.getBlockPredicate().test(state);
    }

    /**
     * §1 waterlogged collapse, restricted to blocks carrying the shared {@code WATERLOGGED}
     * property (stairs, slabs, signs, plants placed underwater - the spec's own examples)
     * rather than every block with any non-empty fluid state. Kelp/seagrass/coral report a
     * permanent water fluid state without ever being "waterlogged" in the placeable sense; a
     * blanket fluid-state collapse would erase them before the seafloor scan could ever
     * capture them as overlays, contradicting that part of the spec.
     */
    private static BlockState collapse(final BlockState state) {
        if (!state.contains(Properties.WATERLOGGED) || !state.get(Properties.WATERLOGGED)) {
            return state;
        }
        final FluidState fluid = state.getFluidState();
        return fluid.isEmpty() ? state : fluid.getBlockState();
    }
}
