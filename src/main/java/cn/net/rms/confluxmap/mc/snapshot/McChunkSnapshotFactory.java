package cn.net.rms.confluxmap.mc.snapshot;

import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Builds immutable {@link ChunkSnapshot}s from live client chunks.
 * Must only be called on the main thread; the produced snapshot is safe to
 * hand to worker threads.
 */
public final class McChunkSnapshotFactory {
    private static final int MAX_FLUID_SCAN = 50;

    private final MinecraftClient client;

    public McChunkSnapshotFactory(final MinecraftClient client) {
        this.client = client;
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
        final short[] surfaceY = new short[ChunkSnapshot.COLUMNS];
        final byte[] fluidDepth = new byte[ChunkSnapshot.COLUMNS];
        final int[] baseArgb = new int[ChunkSnapshot.COLUMNS];
        final int[] tintArgb = new int[ChunkSnapshot.COLUMNS];
        final byte[] kind = new byte[ChunkSnapshot.COLUMNS];

        final Heightmap heightmap = chunk.getHeightmap(Heightmap.Type.WORLD_SURFACE);
        final BlockPos.Mutable pos = new BlockPos.Mutable();
        final int bottomY = world.getBottomY();
        final int baseX = chunkX << 4;
        final int baseZ = chunkZ << 4;

        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                final int index = z * 16 + x;
                int y = heightmap.get(x, z) - 1;
                if (y < bottomY) {
                    surfaceY[index] = ChunkSnapshot.NO_SURFACE;
                    kind[index] = (byte) SurfaceKind.VOID.ordinal();
                    continue;
                }
                pos.set(baseX + x, y, baseZ + z);
                BlockState state = chunk.getBlockState(pos);
                while (state.isAir() && y > bottomY) {
                    pos.setY(--y);
                    state = chunk.getBlockState(pos);
                }
                if (state.isAir()) {
                    surfaceY[index] = ChunkSnapshot.NO_SURFACE;
                    kind[index] = (byte) SurfaceKind.VOID.ordinal();
                    continue;
                }

                final FluidState fluid = state.getFluidState();
                if (!fluid.isEmpty() && fluid.isIn(FluidTags.WATER)) {
                    final int waterSurface = y;
                    int depth = 0;
                    while (y > bottomY && depth < MAX_FLUID_SCAN) {
                        pos.setY(y - 1);
                        final BlockState below = chunk.getBlockState(pos);
                        if (below.getFluidState().isEmpty() && !below.isAir()) {
                            break;
                        }
                        y--;
                        depth++;
                    }
                    pos.setY(Math.max(y - 1, bottomY));
                    final BlockState floor = chunk.getBlockState(pos);
                    surfaceY[index] = (short) waterSurface;
                    fluidDepth[index] = (byte) Math.min(depth, 127);
                    baseArgb[index] = 0xFF000000 | floor.getMapColor(world, pos).color;
                    tintArgb[index] = 0xFFFFFFFF;
                    kind[index] = (byte) SurfaceKind.WATER.ordinal();
                    continue;
                }
                if (!fluid.isEmpty() && fluid.isIn(FluidTags.LAVA)) {
                    surfaceY[index] = (short) y;
                    baseArgb[index] = 0xFF000000 | state.getMapColor(world, pos).color;
                    tintArgb[index] = 0xFFFFFFFF;
                    kind[index] = (byte) SurfaceKind.LAVA.ordinal();
                    continue;
                }

                surfaceY[index] = (short) y;
                baseArgb[index] = 0xFF000000 | state.getMapColor(world, pos).color;
                tintArgb[index] = 0xFFFFFFFF;
                kind[index] = (byte) SurfaceKind.LAND.ordinal();
            }
        }
        return new ChunkSnapshot(chunkX, chunkZ, sessionToken, surfaceY, fluidDepth, baseArgb, tintArgb, kind);
    }
}
