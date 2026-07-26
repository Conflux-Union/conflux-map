package cn.net.rms.confluxmap.mc.teleport;

import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.core.util.TileMath;
import java.util.OptionalInt;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

/** Pure-client two-stage teleport that resolves the final ground height from the loaded target chunk. */
public final class ClientGroundTeleportService {
    static final int STAGING_HEADROOM = 32;
    private static final int MAX_WAIT_TICKS = 200;

    private final MinecraftClient client;
    private Pending pending;

    public ClientGroundTeleportService(final MinecraftClient client) {
        this.client = client;
    }

    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ignored -> tick());
    }

    public void reset() {
        pending = null;
    }

    /**
     * Starts a teleport to any map coordinate. The estimate can come from cubiomes or map cache,
     * but is only used to stage above the predicted terrain while the authoritative client chunk loads.
     */
    public void teleport(
        final int blockX,
        final int blockZ,
        final OptionalInt estimatedPlayerY
    ) {
        final ClientWorld world = client.world;
        if (world == null || client.player == null) {
            return;
        }
        final GroundSample sample = sampleGround(world, blockX, blockZ);
        if (sample.loaded()) {
            sample.playerY().ifPresent(y -> MinecraftAccess.sendCommand(client, commandAt(blockX, y, blockZ)));
            return;
        }

        pending = new Pending(
            world,
            blockX,
            blockZ,
            client.player.getX(),
            client.player.getY(),
            client.player.getZ(),
            0
        );
        MinecraftAccess.sendCommand(
            client,
            commandAt(blockX, stagingY(estimatedPlayerY, world.getBottomY(), world.getTopY()), blockZ)
        );
    }

    private void tick() {
        final Pending current = pending;
        if (current == null) {
            return;
        }
        if (client.world != current.world() || client.player == null) {
            pending = null;
            return;
        }
        if (current.waitedTicks() >= MAX_WAIT_TICKS) {
            pending = null;
            if (isInTargetChunk(client.player.getX(), client.player.getZ(), current.blockX(), current.blockZ())) {
                MinecraftAccess.sendCommand(
                    client,
                    commandAt(current.returnX(), current.returnY(), current.returnZ())
                );
            }
            return;
        }
        pending = current.waitOneTick();
        if (!isInTargetChunk(client.player.getX(), client.player.getZ(), current.blockX(), current.blockZ())) {
            return;
        }

        final GroundSample sample = sampleGround(current.world(), current.blockX(), current.blockZ());
        if (!sample.loaded()) {
            return;
        }
        pending = null;
        if (sample.playerY().isPresent()) {
            MinecraftAccess.sendCommand(
                client,
                commandAt(current.blockX(), sample.playerY().getAsInt(), current.blockZ())
            );
        } else {
            MinecraftAccess.sendCommand(
                client,
                commandAt(current.returnX(), current.returnY(), current.returnZ())
            );
        }
    }

    private static GroundSample sampleGround(
        final ClientWorld world,
        final int blockX,
        final int blockZ
    ) {
        final WorldChunk chunk = (WorldChunk) world.getChunkManager().getChunk(
            TileMath.blockToChunk(blockX), TileMath.blockToChunk(blockZ), ChunkStatus.FULL, false
        );
        if (chunk == null) {
            return new GroundSample(false, OptionalInt.empty());
        }
        final int height = chunk.sampleHeightmap(
            Heightmap.Type.MOTION_BLOCKING,
            Math.floorMod(blockX, 16),
            Math.floorMod(blockZ, 16)
        );
        return new GroundSample(true, groundY(height, world.getBottomY(), world.getTopY()));
    }

    static int stagingY(
        final OptionalInt estimatedPlayerY,
        final int bottomY,
        final int topY
    ) {
        if (estimatedPlayerY.isEmpty()) {
            return topY;
        }
        final long withHeadroom = (long) estimatedPlayerY.getAsInt() + STAGING_HEADROOM;
        return (int) Math.max((long) bottomY + 1L, Math.min(withHeadroom, topY));
    }

    static OptionalInt groundY(final int motionBlockingHeight, final int bottomY, final int topY) {
        return motionBlockingHeight > bottomY && motionBlockingHeight <= topY
            ? OptionalInt.of(motionBlockingHeight)
            : OptionalInt.empty();
    }

    static String commandAt(final int blockX, final int playerY, final int blockZ) {
        return "tp " + centered(blockX) + " " + playerY + " " + centered(blockZ);
    }

    private static String commandAt(final double x, final double y, final double z) {
        return "tp " + Double.toString(x) + " " + Double.toString(y) + " " + Double.toString(z);
    }

    static boolean isInTargetChunk(
        final double playerX,
        final double playerZ,
        final int blockX,
        final int blockZ
    ) {
        return TileMath.blockToChunk((int) Math.floor(playerX)) == TileMath.blockToChunk(blockX)
            && TileMath.blockToChunk((int) Math.floor(playerZ)) == TileMath.blockToChunk(blockZ);
    }

    private static String centered(final int block) {
        return Double.toString(block + 0.5);
    }

    private record GroundSample(boolean loaded, OptionalInt playerY) {
    }

    private record Pending(
        ClientWorld world,
        int blockX,
        int blockZ,
        double returnX,
        double returnY,
        double returnZ,
        int waitedTicks
    ) {
        Pending waitOneTick() {
            return new Pending(world, blockX, blockZ, returnX, returnY, returnZ, waitedTicks + 1);
        }
    }
}
