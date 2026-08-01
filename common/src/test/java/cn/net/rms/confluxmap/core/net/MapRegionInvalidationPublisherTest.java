package cn.net.rms.confluxmap.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MapRegionInvalidationPublisherTest {
    @Test
    void publishesOnlyChangedRegionsIntersectingExactChunkViewport() {
        final UUID player = UUID.randomUUID();
        final MapRegionInvalidationPublisher publisher = new MapRegionInvalidationPublisher();
        publisher.subscribe(player, new MapRegionSyncSubscribeC2S(
            0, 4, true, 15, 16, 3, 4
        ));

        publisher.invalidateRegion(0, -1, 0);
        publisher.invalidateRegion(0, 0, 0);
        publisher.invalidateRegion(0, 1, 0);

        final MapRegionInvalidateS2C invalidation = publisher.poll(player);
        assertEquals(
            List.of(
                new MapRegionInvalidateS2C.Region(0, 0),
                new MapRegionInvalidateS2C.Region(1, 0)
            ),
            invalidation.regions()
        );
        publisher.acknowledge(player, new MapRegionViewReqC2S(
            1, 0, 4, List.of(
                new MapRegionViewReqC2S.RegionReq(0, 0, 15, 3, 15, 4, Long.MIN_VALUE),
                new MapRegionViewReqC2S.RegionReq(1, 0, 0, 3, 0, 4, Long.MIN_VALUE)
            )
        ));
        assertNull(publisher.poll(player));
    }
}
