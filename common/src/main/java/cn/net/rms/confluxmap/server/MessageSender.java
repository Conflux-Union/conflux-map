package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.net.Message;
import cn.net.rms.confluxmap.core.net.MsgCodec;
import cn.net.rms.confluxmap.core.net.ProtoException;
import java.util.function.Consumer;

/** Delivery channel for companion messages to one client, raw or pre-encoded. */
@FunctionalInterface
public interface MessageSender {
    void send(Message message);

    default void sendEncoded(final Message message, final byte[] payload) {
        send(message);
    }

    /** Adapts a transport that consumes already encoded payloads, as the web-map backends do. */
    static MessageSender forwardingTo(final Consumer<byte[]> response) {
        return new MessageSender() {
            @Override
            public void send(final Message message) {
                try {
                    response.accept(MsgCodec.encode(message));
                } catch (final ProtoException ignored) {
                    // Every server-originated message is validated before it reaches this seam.
                }
            }

            @Override
            public void sendEncoded(final Message message, final byte[] payload) {
                response.accept(payload);
            }
        };
    }
}
