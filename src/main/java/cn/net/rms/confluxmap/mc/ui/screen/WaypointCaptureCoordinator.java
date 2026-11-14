package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.Screenshots;
import cn.net.rms.confluxmap.mc.net.waypointimage.WaypointImageClient;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.client.MinecraftClient;

/**
 * Bridges the notes screen' screenshot button to a real world frame. The
 * editor covers the world, so pressing capture stores the pending state,
 * closes the screen chain, waits for the first tick where the world renders
 * with no screen open, grabs that frame, seeds its texture, uploads it under
 * a fresh image id, and reopens the chain. A 10s timeout restores the pending
 * state unchanged, so the player never loses their draft.
 */
public final class WaypointCaptureCoordinator {
    private static final long TIMEOUT_MS = 10_000;
    private static final int RETRY_TICKS = 2;

    private static String pendingDescription = "";
    private static String pendingImageId = "";
    private static BiConsumer<String, String> pendingReopen;
    private static long startedEpochMs;
    private static boolean capturing;
    private static boolean grabbing;

    private WaypointCaptureCoordinator() {
    }

    public static boolean capturing() {
        return capturing;
    }

    /** Arms one capture; {@code reopen} runs on the client thread when it settles. */
    public static void begin(
        final String description,
        final String imageId,
        final BiConsumer<String, String> reopen
    ) {
        pendingDescription = description;
        pendingImageId = imageId;
        pendingReopen = reopen;
        startedEpochMs = System.currentTimeMillis();
        grabbing = false;
        capturing = true;
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), null);
    }

    /** Client tick hook: grabs the first world-visible frame, or times out. */
    public static void tick(final MinecraftClient client) {
        if (!capturing) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (now - startedEpochMs > TIMEOUT_MS) {
            finish();
            return;
        }
        if (grabbing || client.world == null || MinecraftAccess.screen(client) != null) {
            return;
        }
        // One settle tick so the frame being read is the first fully rendered
        // world frame, not the teardown frame of the closing screen.
        if (now - startedEpochMs < RETRY_TICKS * 50L) {
            return;
        }
        grabbing = true;
        final WaypointImageClient images = ConfluxMapClient.get().waypointImages();
        if (images == null) {
            finish();
            return;
        }
        Screenshots.grabFrame(client, (width, height, argb) -> {
            final UUID imageId = UUID.randomUUID();
            images.seedDraftTexture(imageId.toString(), width, height, argb);
            images.uploadDraftImage(imageId, argb, width, height, status -> {});
            pendingImageId = imageId.toString();
            finish();
        });
    }

    private static void finish() {
        capturing = false;
        grabbing = false;
        final BiConsumer<String, String> reopen = pendingReopen;
        final String description = pendingDescription;
        final String imageId = pendingImageId;
        pendingReopen = null;
        pendingDescription = "";
        pendingImageId = "";
        if (reopen != null) {
            MinecraftClient.getInstance().execute(() -> reopen.accept(description, imageId));
        }
    }
}
