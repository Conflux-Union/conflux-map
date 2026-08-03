package cn.net.rms.confluxmap.nativepredict;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class McVersionsTest {
    @Test
    void minecraft261UsesItsPinnedCubiomesGenerator() {
        assertEquals(30, McVersions.toCubiomes("26.1").orElseThrow());
        assertEquals(30, McVersions.toCubiomes("26.1.2").orElseThrow());
    }

    @Test
    void minecraft262UsesItsPinnedCubiomesGenerator() {
        assertEquals(31, McVersions.toCubiomes("26.2").orElseThrow());
        assertEquals(31, McVersions.toCubiomes("26.2.1").orElseThrow());
    }

    @Test
    void playerSelectionsResolvePatchVersionsToTheirWorldgenFamily() {
        final int version117 = McVersions.selectionIndex("1.17");
        assertEquals("1.17-1.17.1", McVersions.selections().get(version117).label());
        assertEquals(21, McVersions.selections().get(version117).cubiomesVersion());

        final int future262Patch = McVersions.selectionIndex("26.2.1");
        assertEquals("26.2", McVersions.selections().get(future262Patch).worldgenVersion());
        assertEquals(31, McVersions.selections().get(future262Patch).cubiomesVersion());
    }
}
