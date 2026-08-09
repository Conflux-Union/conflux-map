package cn.net.rms.confluxmap.mc.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.server.PatchDispatcher;
import cn.net.rms.confluxmap.server.PlayerBudget;
import org.junit.jupiter.api.Test;

class PatchDispatcherApiTest {
    @Test
    void platformAdapterCanConfigureDeliveryMeasurements() {
        final PatchDispatcher dispatcher = new PatchDispatcher(
            new PlayerBudget(1 << 20, 0),
            16,
            () -> 1L,
            delivery -> { }
        );

        assertEquals(0, dispatcher.queued());
    }
}
