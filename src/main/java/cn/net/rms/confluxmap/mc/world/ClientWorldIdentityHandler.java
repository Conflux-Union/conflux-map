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

    /** Observes rendered chat structure without changing whether the message is displayed. */
    public static void chatMessage(final Text message) {
        final ClientMultiworldService current = service;
        if (current != null) {
            VelocityServerTextParser.parse(message).ifPresent(current::onVelocityServerIdentified);
        }
    }
}
