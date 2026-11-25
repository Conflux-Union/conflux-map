package cn.net.rms.confluxmap.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import cn.net.rms.confluxmap.core.net.ErrorS2C;
import cn.net.rms.confluxmap.core.net.MapPatchS2C;
import cn.net.rms.confluxmap.core.net.MapRegionPatchS2C;
import cn.net.rms.confluxmap.core.net.MapRegionViewReqC2S;
import cn.net.rms.confluxmap.core.net.MapViewReqC2S;
import cn.net.rms.confluxmap.core.net.Message;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.core.util.ChunkRegionSlice;
import cn.net.rms.confluxmap.server.MessageSender;
import cn.net.rms.confluxmap.server.ServerConfig;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

class PaperCorrectionLiveChangeTest {
    @TempDir
    Path temporary;

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void acceptedRegionRequestSurvivesLiveSummaryChange(final int lod) throws Exception {
        final PaperWorldDirectory worlds = directory(temporary);
        final PaperWorldDirectory.Entry world = worlds.at(0);
        final AtomicInteger responses = new AtomicInteger();
        final MessageSender sender = sender(
            MapRegionPatchS2C.class, responses
        );

        try (PaperCorrectionService service = service(worlds)) {
            final ChunkRegionSlice slice = new ChunkRegionSlice(0, 0, 0, 0, 15, 15);
            service.requestRegions(
                new UUID(1L, lod + 1L),
                new MapRegionViewReqC2S(
                    1,
                    0,
                    lod,
                    List.of(new MapRegionViewReqC2S.RegionReq(slice, Long.MIN_VALUE))
                ),
                64,
                true,
                sender
            );

            service.putLive(world, 0, 0, SummaryCodec.Chunk.empty());
            tickUntilResponse(service, responses);

            assertEquals(
                1,
                responses.get(),
                "an accepted LOD " + lod + " region request must receive a response"
            );
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void acceptedTileRequestSurvivesLiveSummaryChange(final int lod) throws Exception {
        final PaperWorldDirectory worlds = directory(temporary);
        final PaperWorldDirectory.Entry world = worlds.at(0);
        final AtomicInteger responses = new AtomicInteger();
        final MessageSender sender = sender(MapPatchS2C.class, responses);

        try (PaperCorrectionService service = service(worlds)) {
            service.requestTiles(
                new UUID(2L, lod + 1L),
                new MapViewReqC2S(
                    1,
                    0,
                    lod,
                    List.of(new MapViewReqC2S.TileReq(0, 0, Long.MIN_VALUE))
                ),
                64,
                true,
                sender
            );

            service.putLive(world, 0, 0, SummaryCodec.Chunk.empty());
            tickUntilResponse(service, responses);

            assertEquals(
                1,
                responses.get(),
                "an accepted LOD " + lod + " tile request must receive a response"
            );
        }
    }

    @Test
    void unknownDimensionTileRequestIsRejectedUpFront() throws Exception {
        final PaperWorldDirectory worlds = directory(temporary);
        final AtomicReference<ErrorS2C> error = new AtomicReference<>();
        final AtomicInteger patches = new AtomicInteger();
        final MessageSender sender =
            new MessageSender() {
                @Override
                public void send(final Message message) {
                    record(message);
                }

                @Override
                public void sendEncoded(final Message message, final byte[] payload) {
                    record(message);
                }

                private void record(final Message message) {
                    if (message instanceof ErrorS2C) {
                        error.set((ErrorS2C) message);
                    } else if (message instanceof MapPatchS2C) {
                        patches.incrementAndGet();
                    }
                }
            };

        try (PaperCorrectionService service = service(worlds)) {
            service.requestTiles(
                new UUID(3L, 3L),
                new MapViewReqC2S(
                    1,
                    1,
                    0,
                    List.of(new MapViewReqC2S.TileReq(0, 0, Long.MIN_VALUE))
                ),
                64,
                true,
                sender
            );

            final ErrorS2C rejection = error.get();
            assertNotNull(rejection, "an unknown dimension index must be rejected up front");
            assertEquals(
                ErrorS2C.ERR_RATE_LIMITED,
                rejection.code(),
                "the rejection must match the requestRegions dimension check"
            );
            assertEquals(
                0,
                patches.get(),
                "a rejected request must not schedule tile work"
            );
        }
    }

    private static PaperCorrectionService service(final PaperWorldDirectory worlds) {
        final ServerConfig config = new ServerConfig();
        config.minRequestIntervalMs = 0;
        config.maxBytesPerSecondPerPlayer = 100_000_000;
        return new PaperCorrectionService(
            config,
            worlds,
            "1.21.1",
            1L,
            ignored -> null,
            new PaperMapColors(Map.of()),
            LoggerFactory.getLogger(PaperCorrectionLiveChangeTest.class)
        );
    }

    private static MessageSender sender(
        final Class<? extends Message> responseType,
        final AtomicInteger responses
    ) {
        return new MessageSender() {
            @Override
            public void send(final Message message) {
                record(message);
            }

            @Override
            public void sendEncoded(final Message message, final byte[] payload) {
                record(message);
            }

            private void record(final Message message) {
                if (responseType.isInstance(message)) {
                    responses.incrementAndGet();
                }
            }
        };
    }

    private static void tickUntilResponse(
        final PaperCorrectionService service,
        final AtomicInteger responses
    ) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        while (responses.get() == 0 && System.nanoTime() < deadline) {
            service.tick();
            Thread.sleep(1L);
        }
    }

    private static PaperWorldDirectory directory(final Path root) throws Exception {
        Files.createDirectories(root.resolve("region"));
        final World world = (World) Proxy.newProxyInstance(
            World.class.getClassLoader(),
            new Class<?>[] {World.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getKey" -> NamespacedKey.minecraft("overworld");
                case "getWorldFolder" -> root.toFile();
                case "getEnvironment" -> World.Environment.NORMAL;
                case "getWorldType" -> WorldType.NORMAL;
                case "getGenerator" -> null;
                case "toString" -> "PaperCorrectionLiveChangeTestWorld";
                default -> defaultValue(method.getReturnType());
            }
        );
        final PaperWorldDirectory worlds = new PaperWorldDirectory();
        worlds.add(world);
        return worlds;
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        return null;
    }
}
