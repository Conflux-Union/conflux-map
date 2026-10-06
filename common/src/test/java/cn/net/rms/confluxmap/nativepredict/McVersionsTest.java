package cn.net.rms.confluxmap.nativepredict;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class McVersionsTest {
    @Test
    void minecraft261UsesItsPinnedCubiomesGenerator() {
        assertEquals(33, McVersions.toCubiomes("26.1").orElseThrow());
        assertEquals(33, McVersions.toCubiomes("26.1.2").orElseThrow());
    }

    @Test
    void minecraft262UsesItsPinnedCubiomesGenerator() {
        assertEquals(34, McVersions.toCubiomes("26.2").orElseThrow());
        assertEquals(34, McVersions.toCubiomes("26.2.1").orElseThrow());
    }

    @Test
    void minecraft263UsesItsPinnedCubiomesGenerator() {
        assertEquals(35, McVersions.toCubiomes("26.3").orElseThrow());
        assertEquals(35, McVersions.toCubiomes("26.3.1").orElseThrow());
    }

    @Test
    void post125ReleasesResolveToTheirExactCubiomesEntries() {
        // The cubiomes 26.3 merge gave 1.21.6/9/11 their own enum entries; patches of a drop
        // ride that drop's entry (1.21.7/1.21.8 -> 1.21.6, 1.21.10 -> 1.21.9).
        assertEquals(29, McVersions.toCubiomes("1.21.5").orElseThrow());
        assertEquals(30, McVersions.toCubiomes("1.21.6").orElseThrow());
        assertEquals(30, McVersions.toCubiomes("1.21.7").orElseThrow());
        assertEquals(30, McVersions.toCubiomes("1.21.8").orElseThrow());
        assertEquals(31, McVersions.toCubiomes("1.21.9").orElseThrow());
        assertEquals(31, McVersions.toCubiomes("1.21.10").orElseThrow());
        assertEquals(32, McVersions.toCubiomes("1.21.11").orElseThrow());
    }

    @Test
    void playerSelectionsResolvePatchVersionsToTheirWorldgenFamily() {
        final int version117 = McVersions.selectionIndex("1.17");
        assertEquals("1.17-1.17.1", McVersions.selections().get(version117).label());
        assertEquals(21, McVersions.selections().get(version117).cubiomesVersion());

        final int future262Patch = McVersions.selectionIndex("26.2.1");
        assertEquals("26.2", McVersions.selections().get(future262Patch).worldgenVersion());
        assertEquals(34, McVersions.selections().get(future262Patch).cubiomesVersion());
    }
}
