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

    /** A whole chunk arrived from the server; its already-captured neighbours go stale with it. */
    public static void chunkLoaded(final int chunkX, final int chunkZ) {
        final ChunkCaptureService s = service;
        if (s != null) {
            s.markChunkLoaded(chunkX, chunkZ);
        }
    }

    public static void blockDirty(final int blockX, final int blockZ) {
        chunkDirty(blockX >> 4, blockZ >> 4);
    }

    public static void blockDirty(
        final int blockX, final int blockY, final int blockZ, final int stateId
    ) {
        final ChunkCaptureService s = service;
        if (s != null) {
            s.markBlockDirty(blockX, blockY, blockZ, stateId);
        }
    }
}
