package cn.net.rms.confluxmap.nativepredict;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class McVersionsTest {
    @Test
    void minecraft261UsesItsPinnedCubiomesGenerator() {
        assertEquals(30, McVersions.toCubiomes("26.1").orElseThrow());
        assertEquals(30, McVersions.toCubiomes("26.1.2").orElseThrow());
    }
}
