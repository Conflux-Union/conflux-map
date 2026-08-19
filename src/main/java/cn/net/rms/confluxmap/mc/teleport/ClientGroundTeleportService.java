package cn.net.rms.confluxmap.mc.teleport;

import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.config.TeleportCommandTemplate;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
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
    private final ConfluxConfig config;
    private Pending pending;

    public ClientGroundTeleportService(
        final MinecraftClient client,
        final ConfluxConfig config
    ) {
        this.client = client;
        this.config = config;
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
        final OptionalInt estimatedPlayerY,
        final DimensionId dimension,
        final WorldIdentity worldIdentity,
        final boolean direct
    ) {
        final ClientWorld world = client.world;
        if (world == null || client.player == null) {
            return;
        }
        if (direct) {
            estimatedPlayerY.ifPresent(y -> sendCommand(
                centered(blockX), y, centered(blockZ), dimension, worldIdentity
            ));
            return;
        }
        final GroundSample sample = sampleGround(world, blockX, blockZ);
        if (sample.loaded()) {
            sample.playerY().ifPresent(y -> sendCommand(
                centered(blockX), y, centered(blockZ), dimension, worldIdentity
            ));
            return;
        }

        pending = new Pending(
            world,
            blockX,
            blockZ,
            client.player.getX(),
            client.player.getY(),
            client.player.getZ(),
            dimension,
            worldIdentity,
            0
        );
        sendCommand(
            centered(blockX),
            stagingY(estimatedPlayerY, world.getBottomY(), world.getTopY()),
            centered(blockZ),
            dimension,
            worldIdentity
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
                sendCommand(
                    current.returnX(), current.returnY(), current.returnZ(),
                    current.dimension(), current.worldIdentity()
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
            sendCommand(
                centered(current.blockX()), sample.playerY().getAsInt(), centered(current.blockZ()),
                current.dimension(), current.worldIdentity()
            );
        } else {
            sendCommand(
                current.returnX(), current.returnY(), current.returnZ(),
                current.dimension(), current.worldIdentity()
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

    private void sendCommand(
        final double x,
        final double y,
        final double z,
        final DimensionId dimension,
        final WorldIdentity worldIdentity
    ) {
        MinecraftAccess.sendCommand(
            client,
            TeleportCommandTemplate.render(
                config.teleportCommand, x, y, z, dimension, worldIdentity
            )
        );
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

    private static double centered(final int block) {
        return block + 0.5;
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
        DimensionId dimension,
        WorldIdentity worldIdentity,
        int waitedTicks
    ) {
        Pending waitOneTick() {
            return new Pending(
                world, blockX, blockZ, returnX, returnY, returnZ,
                dimension, worldIdentity, waitedTicks + 1
            );
        }
    }
}
