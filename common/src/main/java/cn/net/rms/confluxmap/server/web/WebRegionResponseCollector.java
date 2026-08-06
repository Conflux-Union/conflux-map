package cn.net.rms.confluxmap.server.web;

import cn.net.rms.confluxmap.core.net.ErrorS2C;
import cn.net.rms.confluxmap.core.net.MapRegionPatchS2C;
import cn.net.rms.confluxmap.core.net.Message;
import cn.net.rms.confluxmap.core.net.MsgCodec;
import cn.net.rms.confluxmap.core.net.ProtoException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Collects paced map-sync messages into one finite HTTP response. */
public final class WebRegionResponseCollector {
    private final int expectedPatches;
    private final List<byte[]> frames = new ArrayList<>();
    private final CompletableFuture<List<byte[]>> future = new CompletableFuture<>();
    private int receivedPatches;

    public WebRegionResponseCollector(final int expectedPatches) {
        if (expectedPatches < 1) {
            throw new IllegalArgumentException("expectedPatches must be positive");
        }
        this.expectedPatches = expectedPatches;
    }

    public synchronized void send(final Message message) {
        if (future.isDone()) {
            return;
        }
        if (!(message instanceof MapRegionPatchS2C) && !(message instanceof ErrorS2C)) {
            return;
        }
        try {
            accept(message, MsgCodec.encode(message));
        } catch (final ProtoException e) {
            future.completeExceptionally(e);
        }
    }

    public synchronized void sendEncoded(final Message message, final byte[] payload) {
        if (!future.isDone()
            && (message instanceof MapRegionPatchS2C || message instanceof ErrorS2C)) {
            accept(message, payload.clone());
        }
    }

    private void accept(final Message message, final byte[] payload) {
        frames.add(payload);
        if (message instanceof MapRegionPatchS2C) {
            receivedPatches++;
        }
        if (message instanceof ErrorS2C || receivedPatches >= expectedPatches) {
            future.complete(List.copyOf(frames));
        }
    }

    public CompletableFuture<List<byte[]>> future() {
        return future;
    }
}
