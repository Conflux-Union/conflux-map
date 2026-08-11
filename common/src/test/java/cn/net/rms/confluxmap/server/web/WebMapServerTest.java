package cn.net.rms.confluxmap.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.net.MapRegionInvalidateS2C;
import cn.net.rms.confluxmap.core.net.MapRegionSyncSubscribeC2S;
import cn.net.rms.confluxmap.core.net.MapRegionViewReqC2S;
import cn.net.rms.confluxmap.core.net.MapPatchS2C;
import cn.net.rms.confluxmap.core.net.MapViewReqC2S;
import cn.net.rms.confluxmap.core.net.MsgCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.predict.WorldPreset;
import cn.net.rms.confluxmap.core.util.ChunkRegionSlice;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

final class WebMapServerTest {
    @Test
    void predictionManifestExposesTheExplicitlySharedSeedAsAString() throws Exception {
        final FakeBackend backend = new FakeBackend();
        try (WebMapServer server = WebMapServer.start(WebMapConfig.loopbackEphemeral(), backend)) {
            final HttpResponse<String> response = client().send(
                HttpRequest.newBuilder(server.uri("/api/v1/manifest")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(200, response.statusCode());
            assertEquals("application/json; charset=utf-8", response.headers().firstValue("content-type").orElseThrow());
            assertTrue(response.body().contains("\"worldId\":\"world-7\""));
            assertTrue(response.body().contains("\"predictionAvailable\":true"));
            assertTrue(response.body().contains("\"seed\":\"123456789\""));
            assertTrue(response.body().contains("\"predictionBiomes\":["));
            assertTrue(response.body().contains("\"id\":1,\"kind\":\"LAND\""));
        }
    }

    @Test
    void defaultPlayerAvatarsAreServedAsSvgImages() throws Exception {
        try (WebMapServer server = WebMapServer.start(
            WebMapConfig.loopbackEphemeral(), new FakeBackend()
        )) {
            for (final String name : List.of("steve", "alex")) {
                final HttpResponse<String> response = client().send(
                    HttpRequest.newBuilder(server.uri("/default-" + name + ".svg"))
                        .GET().build(),
                    HttpResponse.BodyHandlers.ofString()
                );

                assertEquals(200, response.statusCode());
                assertEquals(
                    "image/svg+xml",
                    response.headers().firstValue("content-type").orElseThrow()
                );
                assertTrue(response.body().contains("<svg"));
            }
        }
    }

    @Test
    void servesBundledCubiomesPredictorAsWebAssembly() throws Exception {
        try (WebMapServer server = WebMapServer.start(
            WebMapConfig.loopbackEphemeral(), new FakeBackend()
        )) {
            final HttpResponse<byte[]> response = client().send(
                HttpRequest.newBuilder(server.uri("/predictor.wasm")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()
            );

            assertEquals(200, response.statusCode());
            assertEquals("application/wasm", response.headers().firstValue("content-type").orElseThrow());
            assertTrue(response.body().length > 8);
            assertEquals(0x00, response.body()[0]);
            assertEquals(0x61, response.body()[1]);
            assertEquals(0x73, response.body()[2]);
            assertEquals(0x6d, response.body()[3]);
        }
    }

    @Test
    void compressesTheBundledPredictorForBrowsers() throws Exception {
        try (WebMapServer server = WebMapServer.start(
            WebMapConfig.loopbackEphemeral(), new FakeBackend()
        )) {
            final HttpResponse<byte[]> response = client().send(
                HttpRequest.newBuilder(server.uri("/predictor.wasm"))
                    .header("Accept-Encoding", "gzip, deflate").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()
            );

            assertEquals(200, response.statusCode());
            assertEquals("gzip", response.headers().firstValue("content-encoding").orElseThrow());
            assertEquals("Accept-Encoding", response.headers().firstValue("vary").orElseThrow());
            assertEquals(0x1f, response.body()[0] & 0xff);
            assertEquals(0x8b, response.body()[1] & 0xff);
        }
    }

    @Test
    void servesTheBundledMapApplication() throws Exception {
        try (WebMapServer server = WebMapServer.start(
            WebMapConfig.loopbackEphemeral(), new FakeBackend()
        )) {
            final HttpResponse<String> response = client().send(
                HttpRequest.newBuilder(server.uri("/")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("<main id=\"map\""));
            assertTrue(response.body().contains("<option value=\"all\""));
            assertTrue(response.body().contains("<option value=\"generated\""));
            assertFalse(response.body().contains("value=\"authoritative\""));
            final String contentSecurityPolicy = response.headers()
                .firstValue("content-security-policy").orElseThrow();
            assertTrue(contentSecurityPolicy.contains("'wasm-unsafe-eval'"));
            assertFalse(contentSecurityPolicy.contains("'unsafe-eval'"));
            final String etag = response.headers().firstValue("etag").orElseThrow();
            final HttpResponse<String> unchanged = client().send(
                HttpRequest.newBuilder(server.uri("/"))
                    .header("If-None-Match", etag).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(304, unchanged.statusCode());
        }
    }

    @Test
    void legacyHttpRegionEndpointRequiresWebSocket() throws Exception {
        final FakeBackend backend = new FakeBackend();
        final MapRegionViewReqC2S request = new MapRegionViewReqC2S(
            91,
            0,
            2,
            List.of(new MapRegionViewReqC2S.RegionReq(
                new ChunkRegionSlice(-3, 4, 1, 2, 5, 7),
                Long.MIN_VALUE
            ))
        );
        final byte[] encodedRequest = MsgCodec.encode(request);

        try (WebMapServer server = WebMapServer.start(WebMapConfig.loopbackEphemeral(), backend)) {
            final HttpResponse<byte[]> response = client().send(
                HttpRequest.newBuilder(server.uri("/api/v1/regions"))
                    .header("content-type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(encodedRequest))
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray()
            );

            assertEquals(410, response.statusCode());
            assertTrue(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8)
                .contains("websocket"));
            assertEquals(null, backend.tileRequest.get());
        }
    }

    @Test
    void webSocketReusesTileMapSyncCodecAndReturnsBinaryPatchFrames() throws Exception {
        final FakeBackend backend = new FakeBackend();
        final MapViewReqC2S request = new MapViewReqC2S(
            92,
            0,
            2,
            List.of(new MapViewReqC2S.TileReq(-3, 4, Long.MIN_VALUE))
        );
        final CompletableFuture<byte[]> responseFrame = new CompletableFuture<>();

        try (WebMapServer server = WebMapServer.start(WebMapConfig.loopbackEphemeral(), backend)) {
            final URI webSocketUri = URI.create(server.uri("/api/v1/map").toString().replaceFirst("^http", "ws"));
            final WebSocket socket = client().newWebSocketBuilder()
                .header("Origin", server.uri("").toString())
                .connectTimeout(Duration.ofSeconds(2))
                .buildAsync(webSocketUri, new BinaryListener(responseFrame))
                .get(2, TimeUnit.SECONDS);
            socket.sendBinary(ByteBuffer.wrap(MsgCodec.encode(request)), true)
                .get(2, TimeUnit.SECONDS);

            final MapPatchS2C patch = (MapPatchS2C) MsgCodec.decode(
                responseFrame.get(2, TimeUnit.SECONDS)
            );
            assertEquals(request, backend.tileRequest.get());
            assertEquals(92, patch.reqId());
            assertEquals(-3, patch.tileX());
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void webSocketSubscriptionStreamsRegionInvalidations() throws Exception {
        final FakeBackend backend = new FakeBackend();
        final MapRegionSyncSubscribeC2S subscription = new MapRegionSyncSubscribeC2S(
            0, 1, true, -32, 31, -16, 47
        );
        final CompletableFuture<byte[]> responseFrame = new CompletableFuture<>();

        try (WebMapServer server = WebMapServer.start(WebMapConfig.loopbackEphemeral(), backend)) {
            final URI webSocketUri = URI.create(server.uri("/api/v1/map").toString().replaceFirst("^http", "ws"));
            final WebSocket socket = client().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .buildAsync(webSocketUri, new BinaryListener(responseFrame))
                .get(2, TimeUnit.SECONDS);
            socket.sendBinary(ByteBuffer.wrap(MsgCodec.encode(subscription)), true)
                .get(2, TimeUnit.SECONDS);

            final MapRegionInvalidateS2C invalidation = (MapRegionInvalidateS2C) MsgCodec.decode(
                responseFrame.get(2, TimeUnit.SECONDS)
            );
            assertEquals(subscription, backend.subscription.get());
            assertEquals(List.of(new MapRegionInvalidateS2C.Region(-2, 1)), invalidation.regions());
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void webSocketRejectsBrowserConnectionsFromAnotherOrigin() throws Exception {
        try (WebMapServer server = WebMapServer.start(
            WebMapConfig.loopbackEphemeral(), new FakeBackend()
        )) {
            final URI webSocketUri = URI.create(
                server.uri("/api/v1/map").toString().replaceFirst("^http", "ws")
            );

            final ExecutionException failure = assertThrows(ExecutionException.class, () ->
                client().newWebSocketBuilder()
                    .header("Origin", "https://attacker.invalid")
                    .buildAsync(webSocketUri, new WebSocket.Listener() { })
                    .get(2, TimeUnit.SECONDS)
            );
            final WebSocketHandshakeException handshake = assertInstanceOf(
                WebSocketHandshakeException.class, failure.getCause()
            );
            assertEquals(403, handshake.getResponse().statusCode());
        }
    }

    @Test
    void openMapSocketDoesNotStarveNormalRequests() throws Exception {
        try (WebMapServer server = WebMapServer.start(
            WebMapConfig.loopbackEphemeral(), new FakeBackend()
        )) {
            final URI webSocketUri = URI.create(server.uri("/api/v1/map").toString().replaceFirst("^http", "ws"));
            final WebSocket socket = client().newWebSocketBuilder()
                .buildAsync(webSocketUri, new WebSocket.Listener() { })
                .get(2, TimeUnit.SECONDS);
            final HttpResponse<String> manifest = client().send(
                HttpRequest.newBuilder(server.uri("/api/v1/manifest"))
                    .timeout(Duration.ofSeconds(1)).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, manifest.statusCode());
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS);
        }
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    private static final class BinaryListener implements WebSocket.Listener {
        private final CompletableFuture<byte[]> response;

        BinaryListener(final CompletableFuture<byte[]> response) {
            this.response = response;
        }

        @Override
        public void onOpen(final WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onBinary(
            final WebSocket webSocket,
            final ByteBuffer data,
            final boolean last
        ) {
            final byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            response.complete(bytes);
            webSocket.request(1);
            return null;
        }
    }

    private static final class FakeBackend implements WebMapBackend {
        final AtomicReference<MapViewReqC2S> tileRequest = new AtomicReference<>();
        final AtomicReference<MapRegionSyncSubscribeC2S> subscription = new AtomicReference<>();

        @Override
        public WebMapManifest manifest() {
            return new WebMapManifest(
                "world-7",
                "1.21.8",
                123456789L,
                29,
                List.of(new WebMapManifest.Dimension(
                    0,
                    "minecraft:overworld",
                    "overworld",
                    true,
                    WorldPreset.DEFAULT
                ))
            );
        }

        @Override
        public void requestTiles(
            final UUID clientId,
            final MapViewReqC2S requested,
            final int requestBytes,
            final Consumer<byte[]> response
        ) {
            tileRequest.set(requested);
            final MapViewReqC2S.TileReq tile = requested.tiles().get(0);
            final MapPatchS2C patch = new MapPatchS2C(
                requested.reqId(), requested.dimIndex(), requested.lod(),
                tile.tileX(), tile.tileZ(), Proto.PATCH_MODE_UNAVAILABLE, 0L,
                new byte[Proto.PATCH_PRESENCE_BYTES], new byte[0]
            );
            try {
                response.accept(MsgCodec.encode(patch));
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void subscribeRegions(
            final UUID clientId,
            final MapRegionSyncSubscribeC2S request,
            final Consumer<byte[]> response
        ) {
            subscription.set(request);
            try {
                response.accept(MsgCodec.encode(new MapRegionInvalidateS2C(
                    request.dimIndex(), request.lod(),
                    List.of(new MapRegionInvalidateS2C.Region(-2, 1))
                )));
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
