package cn.net.rms.confluxmap.mc.world;

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
}
