package cn.net.rms.confluxmap.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WorldChunkColumnSourceTest {
    @Test
    void convertsMinecraftTopBlockYToTheExclusiveHeightContract() {
        assertEquals(1, WorldChunkColumnSource.toExclusiveHeight(0));
        assertEquals(64, WorldChunkColumnSource.toExclusiveHeight(63));
    }
}
