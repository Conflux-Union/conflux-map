package cn.net.rms.confluxmap.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.net.MapRegionPatchS2C;
import cn.net.rms.confluxmap.core.net.MapRegionViewReqC2S;
import cn.net.rms.confluxmap.core.net.MsgCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.predict.WorldPreset;
import cn.net.rms.confluxmap.core.util.ChunkRegionSlice;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class WebMapServerTest {
    @Test
    void manifestIsServedWithoutPlaintextSeed() throws Exception {
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
            assertTrue(!response.body().contains("123456789"), "manifest must never expose the seed");
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
            assertTrue(response.headers().firstValue("content-security-policy").isPresent());
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
    void servesSimplifiedChineseLocaleAsJson() throws Exception {
        try (WebMapServer server = WebMapServer.start(
            WebMapConfig.loopbackEphemeral(), new FakeBackend()
        )) {
            final HttpResponse<String> response = client().send(
                HttpRequest.newBuilder(server.uri("/locales/zh-CN.json")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(200, response.statusCode());
            assertEquals("application/json; charset=utf-8", response.headers().firstValue("content-type").orElseThrow());
            assertTrue(response.body().contains("\"modeAll\": \"全部区域\""));
            assertTrue(response.body().contains("\"modeGenerated\": \"已生成区域\""));
            assertFalse(response.body().contains("modeAuthoritative"));
        }
    }

    @Test
    void regionRequestReusesMapSyncCodecAndReturnsLengthFramedPatches() throws Exception {
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

            assertEquals(200, response.statusCode());
            assertEquals(request, backend.request.get());
            final ByteBuffer body = ByteBuffer.wrap(response.body());
            final int length = body.getInt();
            final byte[] frame = new byte[length];
            body.get(frame);
            assertEquals(0, body.remaining());
            final MapRegionPatchS2C patch = (MapRegionPatchS2C) MsgCodec.decode(frame);
            assertEquals(91, patch.reqId());
            assertEquals(-3, patch.regionX());
            assertEquals(Proto.PATCH_MODE_UNAVAILABLE, patch.mode());
        }
    }

    @Test
    void openEventStreamDoesNotStarveNormalRequests() throws Exception {
        try (WebMapServer server = WebMapServer.start(
            WebMapConfig.loopbackEphemeral(), new FakeBackend()
        )) {
            final HttpResponse<InputStream> events = client().send(
                HttpRequest.newBuilder(server.uri("/api/v1/events")).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream()
            );
            try (InputStream ignored = events.body()) {
                assertEquals(200, events.statusCode());
                final HttpResponse<String> manifest = client().send(
                    HttpRequest.newBuilder(server.uri("/api/v1/manifest"))
                        .timeout(Duration.ofSeconds(1)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
                );
                assertEquals(200, manifest.statusCode());
            }
        }
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    private static final class FakeBackend implements WebMapBackend {
        final AtomicReference<MapRegionViewReqC2S> request = new AtomicReference<>();

        @Override
        public WebMapManifest manifest() {
            return new WebMapManifest(
                "world-7",
                "1.21.8",
                true,
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
        public CompletableFuture<List<byte[]>> requestRegions(
            final UUID clientId,
            final MapRegionViewReqC2S requested,
            final int requestBytes
        ) {
            request.set(requested);
            final MapRegionViewReqC2S.RegionReq region = requested.regions().get(0);
            final MapRegionPatchS2C patch = new MapRegionPatchS2C(
                requested.reqId(), requested.dimIndex(), requested.lod(),
                region.regionX(), region.regionZ(),
                region.minLocalChunkX(), region.minLocalChunkZ(),
                region.maxLocalChunkX(), region.maxLocalChunkZ(),
                Proto.PATCH_MODE_UNAVAILABLE, 0L, new byte[0]
            );
            try {
                return CompletableFuture.completedFuture(List.of(MsgCodec.encode(patch)));
            } catch (final Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }
    }
}
