package cn.net.rms.confluxmap.paper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaperPluginMessageDispatcherTest {
    private static final String MAP_CHANNEL = "confluxmap:map_sync";

    @Test
    void flushesHandshakeRepliesWhenTheClientChannelRegistrationArrives() {
        final PaperPluginMessageDispatcher dispatcher = new PaperPluginMessageDispatcher();
        final FakeRecipient player = new FakeRecipient();
        final byte[] compatibility = {0x10, 0x01};
        final byte[] policy = {0x02, 0x03};

        dispatcher.send(player, MAP_CHANNEL, compatibility);
        dispatcher.send(player, MAP_CHANNEL, policy);

        assertTrue(player.sent.isEmpty());

        player.listening.add(MAP_CHANNEL);
        dispatcher.channelRegistered(player, MAP_CHANNEL);

        assertEquals(2, player.sent.size());
        assertArrayEquals(compatibility, player.sent.get(0));
        assertArrayEquals(policy, player.sent.get(1));
    }

    @Test
    void copiesDeferredPayloads() {
        final PaperPluginMessageDispatcher dispatcher = new PaperPluginMessageDispatcher();
        final FakeRecipient player = new FakeRecipient();
        final byte[] payload = {0x02, 0x04};

        dispatcher.send(player, MAP_CHANNEL, payload);
        payload[1] = 0x05;
        player.listening.add(MAP_CHANNEL);
        dispatcher.channelRegistered(player, MAP_CHANNEL);

        assertEquals(1, player.sent.size());
        assertArrayEquals(new byte[] {0x02, 0x04}, player.sent.get(0));
    }

    @Test
    void dropsDeferredPayloadsOnDisconnect() {
        final PaperPluginMessageDispatcher dispatcher = new PaperPluginMessageDispatcher();
        final FakeRecipient player = new FakeRecipient();

        dispatcher.send(player, MAP_CHANNEL, new byte[] {0x02});
        dispatcher.disconnect(player.id());
        player.listening.add(MAP_CHANNEL);
        dispatcher.channelRegistered(player, MAP_CHANNEL);

        assertTrue(player.sent.isEmpty());
    }

    @Test
    void boundsDeferredMessagesFromRepeatedEarlyHellos() {
        final PaperPluginMessageDispatcher dispatcher = new PaperPluginMessageDispatcher();
        final FakeRecipient player = new FakeRecipient();
        final int extra = 3;

        for (int index = 0;
             index < PaperPluginMessageDispatcher.MAX_PENDING_MESSAGES_PER_CHANNEL + extra;
             index++) {
            dispatcher.send(player, MAP_CHANNEL, new byte[] {(byte) index});
        }

        player.listening.add(MAP_CHANNEL);
        dispatcher.channelRegistered(player, MAP_CHANNEL);

        assertEquals(
            PaperPluginMessageDispatcher.MAX_PENDING_MESSAGES_PER_CHANNEL,
            player.sent.size()
        );
        assertArrayEquals(new byte[] {(byte) extra}, player.sent.get(0));
    }

    private static final class FakeRecipient implements PaperPluginMessageDispatcher.Recipient {
        private final UUID id = UUID.fromString("00000000-0000-0000-0000-000000000701");
        private final Set<String> listening = new HashSet<>();
        private final List<byte[]> sent = new ArrayList<>();

        @Override
        public UUID id() {
            return id;
        }

        @Override
        public boolean listensTo(final String channel) {
            return listening.contains(channel);
        }

        @Override
        public void send(final String channel, final byte[] payload) {
            sent.add(payload.clone());
        }
    }
}
