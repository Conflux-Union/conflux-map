package cn.net.rms.confluxmap.mc.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.net.ChunkLoadBand;
import cn.net.rms.confluxmap.core.net.HelloPolicyS2C;
import cn.net.rms.confluxmap.core.net.LoadStateDeltaS2C;
import cn.net.rms.confluxmap.core.net.LoadStateSubscribeC2S;
import cn.net.rms.confluxmap.core.net.Message;
import cn.net.rms.confluxmap.core.predict.WorldPreset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkLoadStateClientTest {
    @Test
    void doesNotSubscribeOrInferStateWithoutServerAuthorization() {
        final CompanionSession companion = new CompanionSession();
        companion.onPolicy(new HelloPolicyS2C(
            new HelloPolicyS2C.Flags(false, true, false, false),
            "world",
            "1.17.1",
            new HelloPolicyS2C.Budgets(65_536, 8, 300, 2),
            List.of(new HelloPolicyS2C.DimDescriptor(
                "minecraft:overworld", "overworld", true, false, 0L, WorldPreset.DEFAULT
            ))
        ));
        final List<Message> sent = new ArrayList<>();
        final ChunkLoadStateClient client = new ChunkLoadStateClient(companion, message -> {
            sent.add(message);
            return 1;
        });

        assertFalse(client.reportViewport(DimensionId.OVERWORLD, -10, 10, -20, 20));
        assertTrue(sent.isEmpty());
        assertTrue(client.snapshot().entries().isEmpty());
        assertFalse(client.snapshot().active());
    }

    @Test
    void subscribesOnlyWhenAuthorizedAndCancelsWithoutLocalInference() {
        final CompanionSession companion = new CompanionSession();
        companion.onPolicy(new HelloPolicyS2C(
            new HelloPolicyS2C.Flags(false, true, false, true),
            "world",
            "1.17.1",
            new HelloPolicyS2C.Budgets(65_536, 8, 300, 2),
            List.of(new HelloPolicyS2C.DimDescriptor(
                "minecraft:overworld", "overworld", true, false, 0L, WorldPreset.DEFAULT
            ))
        ));
        final List<Message> sent = new ArrayList<>();
        final ChunkLoadStateClient client = new ChunkLoadStateClient(companion, message -> {
            sent.add(message);
            return 1;
        });

        assertTrue(client.reportViewport(DimensionId.OVERWORLD, -10, 10, -20, 20));
        final LoadStateSubscribeC2S subscribe = assertInstanceOf(LoadStateSubscribeC2S.class, sent.get(0));
        assertTrue(subscribe.active());
        assertTrue(subscribe.minChunkX() < -10);
        assertTrue(subscribe.maxChunkZ() > 20);
        assertTrue(client.snapshot().entries().isEmpty());

        assertFalse(client.reportViewport(DimensionId.OVERWORLD, -5, 5, -10, 10));
        assertEquals(1, sent.size());

        client.deactivate();
        assertFalse(client.snapshot().active());
        assertFalse(assertInstanceOf(LoadStateSubscribeC2S.class, sent.get(1)).active());
    }

    @Test
    void exportRequestsOneExactTilePageAndCompletesItsImmutablePlane() {
        final CompanionSession companion = new CompanionSession();
        companion.onPolicy(new HelloPolicyS2C(
            new HelloPolicyS2C.Flags(false, true, false, true),
            "world",
            "1.17.1",
            new HelloPolicyS2C.Budgets(65_536, 8, 300, 2),
            List.of(new HelloPolicyS2C.DimDescriptor(
                "minecraft:overworld", "overworld", true, false, 0L, WorldPreset.DEFAULT
            ))
        ));
        final List<Message> sent = new ArrayList<>();
        final ChunkLoadStateClient client = new ChunkLoadStateClient(companion, message -> {
            sent.add(message);
            return 1;
        });
        final TileKey tile = new TileKey(
            new WorldIdentity("server", "world"),
            DimensionId.OVERWORLD,
            MapLayer.SURFACE.cacheId(),
            1,
            0,
            -1
        );

        final var page = client.requestExportTile(tile);
        final LoadStateSubscribeC2S subscribe = assertInstanceOf(LoadStateSubscribeC2S.class, sent.get(0));
        assertEquals(0, subscribe.minChunkX());
        assertEquals(31, subscribe.maxChunkX());
        assertEquals(-32, subscribe.minChunkZ());
        assertEquals(-1, subscribe.maxChunkZ());

        client.onDelta(new LoadStateDeltaS2C(
            subscribe.subscriptionId(),
            false,
            true,
            List.of(new LoadStateDeltaS2C.Entry(0, -32, 31, ChunkLoadBand.ENTITY_TICKING))
        ));

        assertTrue(page.isDone());
        assertTrue(page.join().overlayAt(0, -512, cn.net.rms.confluxmap.core.export.MapExportResolution.ONE_BLOCK) != 0);
        assertFalse(client.snapshot().active());
        assertFalse(assertInstanceOf(LoadStateSubscribeC2S.class, sent.get(1)).active());
    }

    @Test
    void cancellingExportPageCancelsServerSubscription() {
        final CompanionSession companion = new CompanionSession();
        companion.onPolicy(new HelloPolicyS2C(
            new HelloPolicyS2C.Flags(false, true, false, true),
            "world",
            "1.17.1",
            new HelloPolicyS2C.Budgets(65_536, 8, 300, 2),
            List.of(new HelloPolicyS2C.DimDescriptor(
                "minecraft:overworld", "overworld", true, false, 0L, WorldPreset.DEFAULT
            ))
        ));
        final List<Message> sent = new ArrayList<>();
        final ChunkLoadStateClient client = new ChunkLoadStateClient(companion, message -> {
            sent.add(message);
            return 1;
        });
        final TileKey tile = new TileKey(
            new WorldIdentity("server", "world"), DimensionId.OVERWORLD,
            MapLayer.SURFACE.cacheId(), 0, 0, 0
        );

        client.requestExportTile(tile).cancel(true);

        assertFalse(client.snapshot().active());
        assertFalse(assertInstanceOf(LoadStateSubscribeC2S.class, sent.get(1)).active());
    }
}
