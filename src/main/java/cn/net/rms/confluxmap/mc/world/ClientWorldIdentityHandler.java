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

    /** Connection establishment is an identity boundary even before its first join packet arrives. */
    public static void connectionEstablished() {
        final ClientMultiworldService current = service;
        if (current != null) {
            current.onConnectionEstablished();
        }
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

    public static void spawnPosition(final int x, final int y, final int z, final float angle) {
        final ClientMultiworldService current = service;
        if (current != null) {
            current.onSpawnPosition(x, y, z, angle);
        }
    }

    /** Called only after a server full-chunk packet has updated the logical client world. */
    public static void fullChunkLoaded(final int chunkX, final int chunkZ) {
        final ClientMultiworldService current = service;
        if (current != null) {
            current.onFullChunkLoaded(chunkX, chunkZ);
        }
    }

    /** Called when the client discards a fully loaded logical chunk. */
    public static void fullChunkUnloaded(final int chunkX, final int chunkZ) {
        final ClientMultiworldService current = service;
        if (current != null) {
            current.onFullChunkUnloaded(chunkX, chunkZ);
        }
    }

    /** Called from the ChatScreen mixin without cancelling or rewriting the submitted text. */
    public static void chatSubmitted(final String rawText) {
        final ClientMultiworldService current = service;
        if (current != null) {
            current.onChatSubmitted(rawText);
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
