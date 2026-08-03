package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SeedInputTest {
    @Test
    void acceptsMinecraftNumericAndTextSeedForms() {
        assertEquals(-9223372036854775808L, SeedInput.parse(" -9223372036854775808 ").orElseThrow());
        assertEquals(474293735L, SeedInput.parse("Conflux Map").orElseThrow());
        assertTrue(SeedInput.parse("   ").isEmpty());
    }
}
