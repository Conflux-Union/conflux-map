package cn.net.rms.confluxmap.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import cn.net.rms.confluxmap.core.net.ErrorS2C;
import cn.net.rms.confluxmap.core.net.MapRegionPatchS2C;
import cn.net.rms.confluxmap.core.net.MsgCodec;
import org.junit.jupiter.api.Test;

class WebRegionResponseCollectorTest {
    @Test
    void completesAfterEveryRequestedRegion() throws Exception {
        final WebRegionResponseCollector collector = new WebRegionResponseCollector(2);

        collector.send(patch(1));
        assertFalse(collector.future().isDone());
        collector.send(patch(2));

        assertEquals(2, collector.future().get().size());
    }

    @Test
    void errorCompletesImmediately() throws Exception {
        final WebRegionResponseCollector collector = new WebRegionResponseCollector(3);

        collector.send(new ErrorS2C(ErrorS2C.ERR_RATE_LIMITED, "busy"));

        assertEquals(1, collector.future().get().size());
        assertInstanceOf(ErrorS2C.class, MsgCodec.decode(collector.future().get().get(0)));
    }

    private static MapRegionPatchS2C patch(final int reqId) {
        return new MapRegionPatchS2C(
            reqId, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1L, new byte[0]
        );
    }
}
