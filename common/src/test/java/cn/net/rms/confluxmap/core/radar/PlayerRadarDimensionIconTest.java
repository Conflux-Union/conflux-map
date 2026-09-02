package cn.net.rms.confluxmap.core.radar;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.model.DimensionId;
import org.junit.jupiter.api.Test;

class PlayerRadarDimensionIconTest {
    @Test
    void mapsVanillaAndCustomDimensionsToTheRequestedItems() {
        assertEquals("minecraft:grass_block", PlayerRadarDimensionIcon.itemId(DimensionId.OVERWORLD));
        assertEquals("minecraft:netherrack", PlayerRadarDimensionIcon.itemId(DimensionId.NETHER));
        assertEquals("minecraft:end_stone", PlayerRadarDimensionIcon.itemId(DimensionId.END));
        assertEquals(
            "minecraft:compass",
            PlayerRadarDimensionIcon.itemId(DimensionId.of("example", "moon"))
        );
    }
}
