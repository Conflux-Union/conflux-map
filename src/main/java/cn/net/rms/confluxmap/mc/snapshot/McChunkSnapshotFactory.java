package cn.net.rms.confluxmap.mc.snapshot;

import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
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
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Builds immutable {@link ChunkSnapshot}s from live client chunks, per
 * surface-color-sampling.md §1 (surface determination) and §2/§3 (color and
 * tint sourcing). Must only be called on the main thread; the produced
 * snapshot is safe to hand to worker threads.
 *
 * <p>Only §1's top-down, world-heightmap-based surface search is implemented
 * (the "cave/nether-style" viewer-relative search is a later slice, since this
 * factory is only ever used for {@code MapLayer.SURFACE} so far).
 */
public final class McChunkSnapshotFactory {
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

    /** Null if the chunk is not currently loaded. */
    public ChunkSnapshot snapshot(final int chunkX, final int chunkZ, final long sessionToken) {
        final ClientWorld world = client.world;
        if (world == null) {
            return null;
        }
        final WorldChunk chunk = (WorldChunk) world.getChunkManager().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (chunk == null) {
            return null;
        }
        final ClientPlayerEntity player = client.player;
        final int playerY = player != null ? player.getBlockPos().getY() : world.getBottomY();

        final short[] surfaceY = new short[ChunkSnapshot.COLUMNS];
        final byte[] fluidDepth = new byte[ChunkSnapshot.COLUMNS];
        final int[] baseArgb = new int[ChunkSnapshot.COLUMNS];
        final int[] tintArgb = new int[ChunkSnapshot.COLUMNS];
        final int[] overlayArgb = new int[ChunkSnapshot.COLUMNS];
        final byte[] kind = new byte[ChunkSnapshot.COLUMNS];

        final Heightmap heightmap = chunk.getHeightmap(Heightmap.Type.MOTION_BLOCKING);
        final BlockPos.Mutable pos = new BlockPos.Mutable();
        final int bottomY = world.getBottomY();
        final int baseX = chunkX << 4;
        final int baseZ = chunkZ << 4;

        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                sampleColumn(
                    chunk, world, pos, baseX, baseZ, x, z, bottomY, playerY, heightmap, z * 16 + x,
                    surfaceY, fluidDepth, baseArgb, tintArgb, overlayArgb, kind
                );
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
        final SurfaceKind resolvedKind;
        final boolean seafloorScan;
        if (surfaceBlock == Blocks.LAVA) {
            resolvedKind = SurfaceKind.LAVA;
            seafloorScan = false;
        } else if (surfaceBlock == Blocks.WATER) {
            resolvedKind = SurfaceKind.WATER;
            seafloorScan = true;
        } else if (surfaceBlock == Blocks.ICE) {
            resolvedKind = SurfaceKind.ICE;
            seafloorScan = true;
        } else if (surfaceBlock instanceof SnowBlock) {
            resolvedKind = SurfaceKind.SNOW;
            seafloorScan = false;
        } else if (surfaceBlock instanceof LeavesBlock) {
            resolvedKind = SurfaceKind.FOLIAGE;
            seafloorScan = false;
        } else if (surfaceBlock == Blocks.SAND || surfaceBlock == Blocks.RED_SAND) {
            resolvedKind = SurfaceKind.SAND;
            seafloorScan = false;
        } else {
            resolvedKind = SurfaceKind.LAND;
            seafloorScan = false;
        }

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
