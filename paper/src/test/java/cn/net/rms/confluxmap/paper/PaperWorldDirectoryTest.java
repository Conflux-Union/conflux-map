package cn.net.rms.confluxmap.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PaperWorldDirectoryTest {
    @Test
    void onlyVanillaPredictableDimensionsMapToCubiomes() {
        assertEquals(0, PaperWorldDirectory.nativeDimension("minecraft:overworld"));
        assertEquals(1, PaperWorldDirectory.nativeDimension("minecraft:the_end"));
        assertEquals(-1, PaperWorldDirectory.nativeDimension("minecraft:the_nether"));
        assertEquals(-1, PaperWorldDirectory.nativeDimension("example:overworld"));
        assertEquals(-1, PaperWorldDirectory.nativeDimension("example:the_end"));
    }
}
