package cn.net.rms.confluxmap.core.portal;

import cn.net.rms.confluxmap.core.model.DimensionId;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalRegistryTest {
    private static final DimensionId OVERWORLD = DimensionId.OVERWORLD;
    private static final DimensionId NETHER = DimensionId.NETHER;

    private static PortalMarker marker(
        final DimensionId dimension,
        final PortalKind kind,
        final int chunkX,
        final int chunkZ,
        final int anchorX,
        final int anchorZ
    ) {
        return new PortalMarker(dimension, chunkX, chunkZ, kind, anchorX, anchorZ);
    }

    @Test
    void applyReportsNewAndUnchangedMarkers() {
        final PortalRegistry registry = new PortalRegistry();
        assertTrue(registry.apply(marker(OVERWORLD, PortalKind.NETHER_PORTAL, 3, -7, 55, -104)));
        assertFalse(registry.apply(marker(OVERWORLD, PortalKind.NETHER_PORTAL, 3, -7, 55, -104)));
        assertTrue(registry.apply(marker(OVERWORLD, PortalKind.NETHER_PORTAL, 3, -7, 60, -100)));
        assertEquals(1, registry.list(OVERWORLD).size());
    }

    @Test
    void sameChunkTracksEachKindSeparately() {
        final PortalRegistry registry = new PortalRegistry();
        registry.apply(marker(OVERWORLD, PortalKind.NETHER_PORTAL, 0, 0, 1, 1));
        registry.apply(marker(OVERWORLD, PortalKind.END_PORTAL, 0, 0, 2, 2));
        assertEquals(2, registry.list(OVERWORLD).size());
        assertTrue(registry.hasMarkersInChunk(OVERWORLD, 0, 0));
        assertFalse(registry.hasMarkersInChunk(OVERWORLD, 1, 0));
        assertFalse(registry.hasMarkersInChunk(NETHER, 0, 0));
    }

    @Test
    void chunkScanRemovesVanishedKindsAndKeepsReconfirmedOnes() {
        final PortalRegistry registry = new PortalRegistry();
        registry.applyChunkScan(OVERWORLD, 4, 4, List.of(
            marker(OVERWORLD, PortalKind.NETHER_PORTAL, 4, 4, 70, 70),
            marker(OVERWORLD, PortalKind.END_PORTAL, 4, 4, 71, 71)
        ));
        assertTrue(registry.hasMarkersInChunk(OVERWORLD, 4, 4));

        // Only the nether portal survived a rescan; losing the end portal marker is a change.
        assertTrue(registry.applyChunkScan(OVERWORLD, 4, 4, List.of(
            marker(OVERWORLD, PortalKind.NETHER_PORTAL, 4, 4, 70, 70)
        )));
        assertEquals(
            List.of(marker(OVERWORLD, PortalKind.NETHER_PORTAL, 4, 4, 70, 70)),
            registry.list(OVERWORLD)
        );

        // A scan finding nothing clears the chunk but leaves other chunks alone.
        registry.apply(marker(OVERWORLD, PortalKind.END_GATEWAY, 9, 9, 150, 150));
        assertTrue(registry.applyChunkScan(OVERWORLD, 4, 4, List.of()));
        assertEquals(
            List.of(marker(OVERWORLD, PortalKind.END_GATEWAY, 9, 9, 150, 150)),
            registry.list(OVERWORLD)
        );
    }

    @Test
    void chunkScanOnUnknownChunkWithoutFindingsIsANoOp() {
        final PortalRegistry registry = new PortalRegistry();
        assertFalse(registry.applyChunkScan(OVERWORLD, 1, 1, List.of()));
        assertTrue(registry.isEmpty());
    }

    @Test
    void dimensionsAreKeptSeparately() {
        final PortalRegistry registry = new PortalRegistry();
        registry.apply(marker(OVERWORLD, PortalKind.NETHER_PORTAL, 2, 2, 40, 40));
        registry.apply(marker(NETHER, PortalKind.NETHER_PORTAL, 2, 2, 5, 5));
        assertEquals(1, registry.list(OVERWORLD).size());
        assertEquals(1, registry.list(NETHER).size());
        registry.applyChunkScan(NETHER, 2, 2, List.of());
        assertEquals(1, registry.list(OVERWORLD).size());
        assertTrue(registry.list(NETHER).isEmpty());
    }

    @Test
    void loadReplacesStateAndSnapshotIsImmutable() {
        final PortalRegistry registry = new PortalRegistry();
        registry.apply(marker(OVERWORLD, PortalKind.NETHER_PORTAL, 2, 2, 40, 40));
        registry.load(java.util.Map.of(
            NETHER, List.of(marker(NETHER, PortalKind.END_GATEWAY, 8, 8, 130, 130))
        ));
        assertTrue(registry.list(OVERWORLD).isEmpty());
        assertEquals(1, registry.list(NETHER).size());
        assertEquals(1, registry.snapshot().size());
    }
}
