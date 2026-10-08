package cn.net.rms.confluxmap.mc.portal;

import net.minecraft.block.BlockState;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Static bridge between mixins and the portal scan service, mirroring
 * {@code ChunkCaptureHandler}: mixins fire before the client entrypoint may
 * have run, so every hook is null-tolerant.
 */
public final class PortalScanHandler {
    private static volatile PortalScanService service;

    private PortalScanHandler() {
    }

    public static void bind(final PortalScanService scanService) {
        service = scanService;
    }

    public static void chunkLoaded(final WorldChunk chunk) {
        final PortalScanService s = service;
        if (s != null) {
            s.chunkLoaded(chunk);
        }
    }

    public static void blockUpdated(final int x, final int y, final int z, final BlockState state) {
        final PortalScanService s = service;
        if (s != null) {
            s.blockUpdated(x, y, z, state);
        }
    }
}
