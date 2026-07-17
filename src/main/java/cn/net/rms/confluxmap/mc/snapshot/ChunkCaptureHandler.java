package cn.net.rms.confluxmap.mc.snapshot;

/**
 * Static bridge between mixins and the capture service. Mixins fire before
 * the client entrypoint may have run, so every hook is null-tolerant.
 */
public final class ChunkCaptureHandler {
    private static volatile ChunkCaptureService service;

    private ChunkCaptureHandler() {
    }

    public static void bind(final ChunkCaptureService captureService) {
        service = captureService;
    }

    public static void chunkDirty(final int chunkX, final int chunkZ) {
        final ChunkCaptureService s = service;
        if (s != null) {
            s.markDirty(chunkX, chunkZ);
        }
    }

    public static void blockDirty(final int blockX, final int blockZ) {
        chunkDirty(blockX >> 4, blockZ >> 4);
    }
}
