package cn.net.rms.confluxmap.gametest;

import cn.net.rms.confluxmap.compat.Regs;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.quality.GeneratedTileComposer;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.SnowBlock;
import net.minecraft.fluid.FluidState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;

/** Reads a complete generated tile through live Vanilla chunk and heightmap APIs. */
final class GeneratedWorldTileReader {
    private static final int TILE_PIXELS = 256;

    private GeneratedWorldTileReader() {
    }

    static GeneratedTileComposer.Grid read(final ServerWorld world, final int tileX, final int tileZ) {
        final int length = TILE_PIXELS * TILE_PIXELS;
        final TileArrays tile = new TileArrays(
            new short[length],
            new byte[length],
            new byte[length],
            new byte[length],
            new int[length]
        );
        final BlockPos.Mutable position = new BlockPos.Mutable();
        for (int chunkZ = 0; chunkZ < 16; chunkZ++) {
            for (int chunkX = 0; chunkX < 16; chunkX++) {
                readChunk(
                    world,
                    world.getChunk(tileX * 16 + chunkX, tileZ * 16 + chunkZ),
                    tileX,
                    tileZ,
                    position,
                    tile
                );
            }
        }
        return new GeneratedTileComposer.Grid(
            TILE_PIXELS,
            TILE_PIXELS,
            tile.surfaceY(),
            tile.kind(),
            tile.fluidDepth(),
            tile.mapColorId(),
            tile.biomeId()
        );
    }

    private static void readChunk(
        final ServerWorld world,
        final WorldChunk chunk,
        final int tileX,
        final int tileZ,
        final BlockPos.Mutable position,
        final TileArrays tile
    ) {
        final Heightmap heightmap = chunk.getHeightmap(Heightmap.Type.MOTION_BLOCKING);
        final int chunkPixelX = Math.floorMod(chunk.getPos().x, 16) * 16;
        final int chunkPixelZ = Math.floorMod(chunk.getPos().z, 16) * 16;
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                final int pixelX = chunkPixelX + localX;
                final int pixelZ = chunkPixelZ + localZ;
                final int index = pixelZ * TILE_PIXELS + pixelX;
                final int worldX = tileX * TILE_PIXELS + pixelX;
                final int worldZ = tileZ * TILE_PIXELS + pixelZ;
                int y = heightmap.get(localX, localZ) - 1;
                if (y < world.getBottomY()) {
                    tile.kind()[index] = (byte) SurfaceKind.VOID.ordinal();
                    tile.mapColorId()[index] = (byte) Proto.MAP_COLOR_NONE;
                    continue;
                }
                position.set(worldX, y, worldZ);
                BlockState state = collapse(chunk.getBlockState(position));
                while (state.isAir() && y > world.getBottomY()) {
                    y--;
                    position.setY(y);
                    state = collapse(chunk.getBlockState(position));
                }
                if (state.isAir()) {
                    tile.kind()[index] = (byte) SurfaceKind.VOID.ordinal();
                    tile.mapColorId()[index] = (byte) Proto.MAP_COLOR_NONE;
                    continue;
                }
                final SurfaceKind surfaceKind = classify(state.getBlock());
                tile.surfaceY()[index] = clampShort(y);
                tile.kind()[index] = (byte) surfaceKind.ordinal();
                tile.fluidDepth()[index] = (byte) fluidDepth(chunk, position, y, surfaceKind);
                tile.mapColorId()[index] = (byte) visibleMapColorId(chunk, world, position, y, state);
                //#if MC>=12100
                //$$ tile.biomeId()[index] = Regs.biomes(world).getRawId(world.getBiome(position).value());
                //#else
                tile.biomeId()[index] = Regs.biomes(world).getRawId(world.getBiome(position));
                //#endif
            }
        }
    }

    private static int visibleMapColorId(
        final WorldChunk chunk,
        final ServerWorld world,
        final BlockPos.Mutable position,
        final int surfaceY,
        final BlockState surface
    ) {
        position.setY(surfaceY + 1);
        final BlockState overlay = collapse(chunk.getBlockState(position));
        if (!overlay.isAir()) {
            final int overlayColor = overlay.getMapColor(world, position).id;
            if (overlayColor != 0) {
                position.setY(surfaceY);
                return overlayColor;
            }
        }
        position.setY(surfaceY);
        return surface.getMapColor(world, position).id;
    }

    private static int fluidDepth(
        final WorldChunk chunk,
        final BlockPos.Mutable position,
        final int surfaceY,
        final SurfaceKind surfaceKind
    ) {
        if (surfaceKind != SurfaceKind.WATER && surfaceKind != SurfaceKind.ICE) {
            return 0;
        }
        int floorY = surfaceY - 1;
        position.setY(floorY);
        BlockState state = chunk.getBlockState(position);
        while (floorY > 0 && isWaterColumn(state)) {
            floorY--;
            position.setY(floorY);
            state = chunk.getBlockState(position);
        }
        position.setY(surfaceY);
        return Math.min(255, surfaceY - floorY);
    }

    private static boolean isWaterColumn(final BlockState state) {
        return state.getBlock() == Blocks.ICE || state.getFluidState().isIn(FluidTags.WATER);
    }

    private static SurfaceKind classify(final Block block) {
        if (block == Blocks.LAVA) {
            return SurfaceKind.LAVA;
        }
        if (block == Blocks.WATER) {
            return SurfaceKind.WATER;
        }
        if (block == Blocks.ICE) {
            return SurfaceKind.ICE;
        }
        if (block instanceof SnowBlock) {
            return SurfaceKind.SNOW;
        }
        if (block instanceof LeavesBlock) {
            return SurfaceKind.FOLIAGE;
        }
        if (block == Blocks.SAND || block == Blocks.RED_SAND) {
            return SurfaceKind.SAND;
        }
        return SurfaceKind.LAND;
    }

    private static BlockState collapse(final BlockState state) {
        if (!state.contains(Properties.WATERLOGGED) || !state.get(Properties.WATERLOGGED)) {
            return state;
        }
        final FluidState fluid = state.getFluidState();
        return fluid.isEmpty() ? state : fluid.getBlockState();
    }

    private static short clampShort(final int value) {
        return (short) Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, value));
    }

    private record TileArrays(
        short[] surfaceY,
        byte[] kind,
        byte[] fluidDepth,
        byte[] mapColorId,
        int[] biomeId
    ) {
    }
}
