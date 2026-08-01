package cn.net.rms.confluxmap.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MapInvalidationPublisherTest {
    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void changedRegionInvalidatesOnlyItsVisibleLodTile() {
        final MapInvalidationPublisher publisher = new MapInvalidationPublisher();
        assertTrue(publisher.subscribe(PLAYER, new MapSyncSubscribeC2S(
            1, 3, true, -1, 1, -1, 1
        )));

        publisher.invalidateRegion(1, 7, -1);
        publisher.invalidateRegion(0, 7, -1);
        publisher.invalidateRegion(1, 24, 0);

        assertEquals(
            new MapInvalidateS2C(1, 3, List.of(new MapInvalidateS2C.Tile(0, -1))),
            publisher.poll(PLAYER)
        );
        assertNull(publisher.poll(PLAYER));
    }

    @Test
    void notificationIsCoalescedUntilTheClientRequestsThatTile() {
        final MapInvalidationPublisher publisher = new MapInvalidationPublisher();
        publisher.subscribe(PLAYER, new MapSyncSubscribeC2S(0, 4, true, 0, 0, 0, 0));

        publisher.invalidateRegion(0, 1, 1);
        assertEquals(1, publisher.poll(PLAYER).tiles().size());
        publisher.invalidateRegion(0, 2, 2);
        assertNull(publisher.poll(PLAYER));

        publisher.acknowledge(PLAYER, new MapViewReqC2S(
            7, 0, 4, List.of(new MapViewReqC2S.TileReq(0, 0, 3L))
        ));
        publisher.invalidateRegion(0, 3, 3);
        assertEquals(1, publisher.poll(PLAYER).tiles().size());
    }

    @Test
    void inactiveOrReplacedSubscriptionStopsOldViewportNotifications() {
        final MapInvalidationPublisher publisher = new MapInvalidationPublisher();
        assertFalse(publisher.subscribe(PLAYER, new MapSyncSubscribeC2S(
            0, 0, true, 0, 16, 0, 16
        )));
        assertTrue(publisher.subscribe(PLAYER, new MapSyncSubscribeC2S(
            0, 0, true, 5, 5, 6, 6
        )));
        assertTrue(publisher.watches(0, 0, 5, 6));

        assertTrue(publisher.subscribe(PLAYER, new MapSyncSubscribeC2S(
            0, 0, false, 0, 0, 0, 0
        )));
        publisher.invalidateRegion(0, 5, 6);
        assertNull(publisher.poll(PLAYER));
    }

    @Test
    void overlappingViewportUpdateKeepsAnUndeliveredInvalidation() {
        final MapInvalidationPublisher publisher = new MapInvalidationPublisher();
        publisher.subscribe(PLAYER, new MapSyncSubscribeC2S(0, 2, true, 0, 1, 0, 0));
        publisher.invalidateRegion(0, 0, 0);

        publisher.subscribe(PLAYER, new MapSyncSubscribeC2S(0, 2, true, -1, 0, 0, 0));

        assertEquals(
            List.of(new MapInvalidateS2C.Tile(0, 0)),
            publisher.poll(PLAYER).tiles()
        );
    }
}
