package cn.net.rms.confluxmap.bridge;

import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.util.Optional;

/**
 * The only doorway from version-agnostic code to the running game.
 * Implemented once per Minecraft version in the {@code mc} package;
 * {@code core} and UI logic depend on this interface alone.
 */
public interface GameBridge {
    /** The current world session (world identity + dimension + token). */
    SessionGuard.Session session();

    /**
     * Player pose, empty while no world is loaded. {@code tickDelta} interpolates
     * position and yaw between ticks for smooth per-frame rendering.
     */
    Optional<PlayerView> player(float tickDelta);

    default Optional<PlayerView> player() {
        return player(1.0F);
    }

    /** Run a task on the render thread (next frame at the latest). */
    void runOnRenderThread(Runnable task);
}
