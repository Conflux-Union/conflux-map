package cn.net.rms.confluxmap.mc.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ClientChunkLookupTest {
    @Test
    void unloadedChunkDoesNotMasqueradeAsRealMapData() {
        assertFalse(ClientChunkLookup.isLoaded(32, 48, (chunkX, chunkZ) -> false));
    }

    @Test
    void loadedChunkRemainsAvailableForRealBiomeReadout() {
        assertTrue(ClientChunkLookup.isLoaded(32, 48, (chunkX, chunkZ) -> true));
    }

    @Test
    void blockCoordinatesUseFloorChunkCoordinates() {
        final int[] requestedChunk = new int[2];

        ClientChunkLookup.isLoaded(-1, -17, (chunkX, chunkZ) -> {
            requestedChunk[0] = chunkX;
            requestedChunk[1] = chunkZ;
            return false;
        });

        assertEquals(-1, requestedChunk[0]);
        assertEquals(-2, requestedChunk[1]);
    }
}
