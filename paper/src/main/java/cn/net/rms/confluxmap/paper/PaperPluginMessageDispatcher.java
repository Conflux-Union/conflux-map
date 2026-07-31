package cn.net.rms.confluxmap.paper;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.entity.Player;

/** Delivers Paper plugin messages only after the client has registered the target channel. */
final class PaperPluginMessageDispatcher {
    static final int MAX_PENDING_MESSAGES_PER_CHANNEL = 16;
    static final int MAX_PENDING_BYTES_PER_CHANNEL = 2 * 1024 * 1024;

    private static final class PendingChannel {
        private final ArrayDeque<byte[]> payloads = new ArrayDeque<>();
        private int bytes;

        private void add(final byte[] payload) {
            if (payload.length > MAX_PENDING_BYTES_PER_CHANNEL) {
                return;
            }
            while (!payloads.isEmpty()
                && (payloads.size() >= MAX_PENDING_MESSAGES_PER_CHANNEL
                    || bytes + payload.length > MAX_PENDING_BYTES_PER_CHANNEL)) {
                bytes -= payloads.removeFirst().length;
            }
            payloads.addLast(payload.clone());
            bytes += payload.length;
        }
    }

    private final Map<UUID, Map<String, PendingChannel>> pending = new HashMap<>();

    interface Recipient {
        UUID id();

        boolean listensTo(String channel);

        void send(String channel, byte[] payload);
    }

    static Recipient recipient(
        final ConfluxMapPaperPlugin plugin,
        final Player player
    ) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(player, "player");
        return new Recipient() {
            @Override
            public UUID id() {
                return player.getUniqueId();
            }

            @Override
            public boolean listensTo(final String channel) {
                return player.getListeningPluginChannels().contains(channel);
            }

            @Override
            public void send(final String channel, final byte[] payload) {
                player.sendPluginMessage(plugin, channel, payload);
            }
        };
    }

    synchronized void send(
        final Recipient recipient,
        final String channel,
        final byte[] payload
    ) {
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(payload, "payload");
        if (recipient.listensTo(channel)) {
            recipient.send(channel, payload);
            return;
        }
        pending.computeIfAbsent(recipient.id(), ignored -> new HashMap<>())
            .computeIfAbsent(channel, ignored -> new PendingChannel())
            .add(payload);
    }

    synchronized void channelRegistered(final Recipient recipient, final String channel) {
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(channel, "channel");
        if (!recipient.listensTo(channel)) {
            return;
        }
        final Map<String, PendingChannel> playerPending = pending.get(recipient.id());
        if (playerPending == null) {
            return;
        }
        final PendingChannel ready = playerPending.remove(channel);
        if (playerPending.isEmpty()) {
            pending.remove(recipient.id());
        }
        if (ready == null) {
            return;
        }
        for (final byte[] payload : ready.payloads) {
            recipient.send(channel, payload);
        }
    }

    synchronized void disconnect(final UUID playerId) {
        pending.remove(Objects.requireNonNull(playerId, "playerId"));
    }

    synchronized void clear() {
        pending.clear();
    }
}
