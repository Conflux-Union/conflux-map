package cn.net.rms.confluxmap.mc.world;

import net.minecraft.text.Text;

/** Static packet-mixin bridge into the client world identity service. */
public final class ClientWorldIdentityHandler {
    private static volatile ClientMultiworldService service;

    private ClientWorldIdentityHandler() {
    }

    public static void bind(final ClientMultiworldService value) {
        service = value;
    }

    public static void gameJoin(final long seedHash) {
        final ClientMultiworldService current = service;
        if (current != null) {
            current.onGameJoin(seedHash);
        }
    }

    public static void respawn(final long seedHash) {
        final ClientMultiworldService current = service;
        if (current != null) {
            current.onRespawn(seedHash);
        }
    }

    /** Returns whether a pending world-detection query consumed this rendered chat message. */
    public static boolean chatMessage(final Text message) {
        final ClientMultiworldService current = service;
        return current != null && current.onVelocityServerMessage(
            VelocityServerTextParser.parse(message),
            VelocityServerTextParser.isCurrentServerNotice(message)
        );
    }
}
