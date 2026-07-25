package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.compat.Regs;
import cn.net.rms.confluxmap.core.predict.CubiomesBiomeIds;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;

/** Main-thread adapter that exposes one loaded {@link WorldChunk} through {@link ChunkColumnSource}. */
final class WorldChunkColumnSource implements ChunkColumnSource {
    private final ServerWorld world;
    private final WorldChunk chunk;
    private final long revision;
    private final int startX;
    private final int startZ;
    private final BlockPos.Mutable pos = new BlockPos.Mutable();
    private final Map<Block, String> blockNames = new IdentityHashMap<>();
    private final Map<BiomeSample, Integer> biomeIds = new HashMap<>();

    private record BiomeSample(int x, int y, int z) {
    }

    WorldChunkColumnSource(final ServerWorld world, final WorldChunk chunk, final long revision) {
        this.world = world;
        this.chunk = chunk;
        this.revision = revision;
        startX = chunk.getPos().getStartX();
        startZ = chunk.getPos().getStartZ();
    }

    @Override
    public boolean generated() {
        return true;
    }

    @Override
    public long revision() {
        return revision;
    }

    @Override
    public int bottomY() {
        return world.getBottomY();
    }

    @Override
    public int motionBlockingHeight(final int x, final int z) {
        return chunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING, x, z);
    }

    @Override
    public int oceanFloorHeight(final int x, final int z) {
        return chunk.sampleHeightmap(Heightmap.Type.OCEAN_FLOOR, x, z);
    }

    @Override
    public String blockNameAt(final int x, final int y, final int z) {
        pos.set(startX + x, y, startZ + z);
        final BlockState state = chunk.getBlockState(pos);
        return blockNames.computeIfAbsent(state.getBlock(), block -> {
            final Identifier id = Regs.blockId(block);
            return id == null ? "minecraft:air" : id.toString();
        });
    }

    @Override
    public int biomeIdAt(final int x, final int y, final int z) {
        final int worldX = startX + x;
        final int worldZ = startZ + z;
        final BiomeSample sample = new BiomeSample(worldX >> 2, y >> 2, worldZ >> 2);
        return biomeIds.computeIfAbsent(sample, ignored -> {
            pos.set(worldX, y, worldZ);
            final Identifier id = Regs.biomeIdAt(world, pos);
            return id == null ? 1 : CubiomesBiomeIds.idForName(id.getPath()).orElse(1);
        });
    }
}
