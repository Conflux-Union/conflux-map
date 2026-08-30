package cn.net.rms.confluxmap.mc.snapshot;

import cn.net.rms.confluxmap.terrain.protocol.MaterialDescriptor;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

/** Resolves the small Minecraft-dependent material predicate table requested by the child. */
final class McTerrainMaterialResolver {
    private final MinecraftClient client;

    McTerrainMaterialResolver(final MinecraftClient client) {
        this.client = client;
    }

    Map<Integer, MaterialDescriptor> resolve(final Set<Integer> stateIds) {
        final ClientWorld world = client.world;
        if (world == null || stateIds.isEmpty()) {
            return Map.of();
        }
        final BlockPos position = client.player == null
            ? BlockPos.ORIGIN : client.player.getBlockPos();
        final Map<Integer, MaterialDescriptor> result = new LinkedHashMap<>();
        for (final int stateId : stateIds) {
            final BlockState state = McChunkSnapshotFactory.collapse(Block.getStateFromRawId(stateId));
            result.put(stateId, new MaterialDescriptor(
                McChunkSnapshotFactory.isOpenForFloorScan(state, world, position),
                McChunkSnapshotFactory.isFloorOverlayCandidate(state)
            ));
        }
        return result;
    }
}
