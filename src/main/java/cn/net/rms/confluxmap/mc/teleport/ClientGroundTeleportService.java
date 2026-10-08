package cn.net.rms.confluxmap.mc.teleport;

import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.config.TeleportCommandTemplate;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.store.ColumnStore;
import cn.net.rms.confluxmap.core.util.TileMath;
import java.util.OptionalInt;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Pure-client two-stage teleport that resolves the final ground height from the loaded target
 * chunk; a column that resolves to void has no height to resolve and lands at the player's
 * pre-teleport Y instead.
 */
public final class ClientGroundTeleportService {
    static final int STAGING_HEADROOM = 32;
    private static final int MAX_WAIT_TICKS = 200;
    /** Chat feedback for a wait that expired before the player ever reached the target chunk. */
    static final String TIMEOUT_KEY = "confluxmap.teleport.feedback.timeout";

    private final MinecraftClient client;
    private final ConfluxConfig config;
    private final Consumer<String> feedback;
    private Pending pending;

    public ClientGroundTeleportService(
        final MinecraftClient client,
        final ConfluxConfig config
    ) {
        this(client, config, key -> sendChatFeedback(client, key));
    }

    ClientGroundTeleportService(
        final MinecraftClient client,
        final ConfluxConfig config,
        final Consumer<String> feedback
    ) {
        this.client = client;
        this.config = config;
        this.feedback = feedback;
    }

    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ignored -> tick());
    }

    public void reset() {
        pending = null;
    }

    /** Sends the configured command to the waypoint's exact stored coordinates. */
    public void teleportExact(
        final double x,
        final double y,
        final double z,
        final DimensionId dimension,
        final WorldIdentity worldIdentity
    ) {
        if (client.world == null || client.player == null) {
            return;
        }
        pending = null;
        sendCommand(x, y, z, dimension, worldIdentity);
    }

    /**
     * Starts a teleport to any map coordinate. The ground estimate can come from cubiomes or
     * map cache, but is only used to stage above the predicted terrain while the authoritative
     * client chunk loads. A known void column skips the wait entirely and lands at the
     * player's pre-teleport Y.
     */
    public void teleport(
        final int blockX,
        final int blockZ,
        final ColumnStore.SurfaceLookup ground,
        final DimensionId dimension,
        final WorldIdentity worldIdentity,
        final boolean direct
    ) {
        final ClientWorld world = client.world;
        if (world == null || client.player == null) {
            return;
        }
        final boolean voidColumn = ground.known() && ground.surfaceY().isEmpty();
        if (direct) {
            if (voidColumn) {
                sendCommand(
                    centered(blockX), client.player.getY(), centered(blockZ),
                    dimension, worldIdentity
                );
            } else {
                estimatedPlayerY(ground).ifPresent(y -> sendCommand(
                    centered(blockX), y, centered(blockZ), dimension, worldIdentity
                ));
            }
            return;
        }
        final GroundSample sample = sampleGround(world, blockX, blockZ);
        if (sample.loaded()) {
            sendCommand(
                centered(blockX),
                sample.playerY().isPresent() ? sample.playerY().getAsInt() : client.player.getY(),
                centered(blockZ),
                dimension,
                worldIdentity
            );
            return;
        }
        if (voidColumn) {
            sendCommand(
                centered(blockX), client.player.getY(), centered(blockZ),
                dimension, worldIdentity
            );
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
            stagingY(estimatedPlayerY(ground), world.getBottomY(), world.getTopY()),
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
        final boolean timedOut = current.waitedTicks() >= MAX_WAIT_TICKS;
        if (!timedOut) {
            pending = current.waitOneTick();
        }
        final boolean inTargetChunk = isInTargetChunk(
            client.player.getX(), client.player.getZ(), current.blockX(), current.blockZ()
        );
        final GroundSample sample = timedOut || !inTargetChunk
            ? GroundSample.NOT_LOADED
            : sampleGround(current.world(), current.blockX(), current.blockZ());
        final CorrectionStep step = nextCorrectionStep(timedOut, inTargetChunk, sample.loaded());
        if (step.clearsPending()) {
            pending = null;
        }
        if (step.sendsTarget()) {
            sendCommand(
                centered(current.blockX()),
                sample.playerY().isPresent() ? sample.playerY().getAsInt() : current.returnY(),
                centered(current.blockZ()),
                current.dimension(), current.worldIdentity()
            );
        } else if (step.sendsReturn()) {
            sendCommand(
                current.returnX(), current.returnY(), current.returnZ(),
                current.dimension(), current.worldIdentity()
            );
        }
        if (step.feedbackKey() != null) {
            feedback.accept(step.feedbackKey());
        }
    }

    /**
     * One correction tick's decision, kept free of Minecraft state so the landing contract is
     * unit-testable: keep waiting, land on the resolved ground, land on the saved pre-teleport
     * Y over a void column, return to the saved origin, or abandon the wait. {@code
     * feedbackKey} names the chat message for outcomes that used to resolve silently; null
     * stays silent.
     */
    static CorrectionStep nextCorrectionStep(
        final boolean timedOut,
        final boolean reachedTargetChunk,
        final boolean targetChunkLoaded
    ) {
        if (timedOut) {
            return reachedTargetChunk
                ? new CorrectionStep(true, false, true, null)
                : new CorrectionStep(true, false, false, TIMEOUT_KEY);
        }
        if (!reachedTargetChunk || !targetChunkLoaded) {
            return new CorrectionStep(false, false, false, null);
        }
        return new CorrectionStep(true, true, false, null);
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

    /** Player-feet Y standing on the estimated surface; empty when the surface is unknown. */
    static OptionalInt estimatedPlayerY(final ColumnStore.SurfaceLookup ground) {
        return ground.surfaceY().isPresent()
            ? OptionalInt.of(ground.surfaceY().getAsInt() + 1)
            : OptionalInt.empty();
    }

    static OptionalInt groundY(final int motionBlockingHeight, final int bottomY, final int topY) {
        return motionBlockingHeight > bottomY && motionBlockingHeight < topY
            ? OptionalInt.of(motionBlockingHeight + 1)
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

    /** The default feedback sink: one chat line, matching the mod's other client messages. */
    private static void sendChatFeedback(final MinecraftClient client, final String key) {
        if (client.player != null) {
            //#if MC>=260100
            //$$ client.player.sendSystemMessage(Texts.translatable(key));
            //#else
            client.player.sendMessage(Texts.translatable(key), false);
            //#endif
        }
    }

    private static double centered(final int block) {
        return block + 0.5;
    }

    /** What one correction tick must do; see {@link #nextCorrectionStep}. */
    record CorrectionStep(
        boolean clearsPending,
        boolean sendsTarget,
        boolean sendsReturn,
        String feedbackKey
    ) {
    }

    private record GroundSample(boolean loaded, OptionalInt playerY) {
        private static final GroundSample NOT_LOADED = new GroundSample(false, OptionalInt.empty());
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
