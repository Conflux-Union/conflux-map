package cn.net.rms.confluxmap.core.loadstate;

/** Zoom-dependent decoration policy kept independent of Minecraft rendering APIs. */
public record ChunkLoadOverlayStyle(boolean drawOutline, boolean drawLevelLabel) {
    private static final double MIN_OUTLINE_WIDTH = 4.0;
    private static final double MIN_LABEL_WIDTH = 24.0;

    public static ChunkLoadOverlayStyle forChunkWidth(
        final double chunkWidth,
        final ChunkLoadDetailMode detailMode
    ) {
        return new ChunkLoadOverlayStyle(
            chunkWidth >= MIN_OUTLINE_WIDTH,
            detailMode == ChunkLoadDetailMode.EXACT && chunkWidth >= MIN_LABEL_WIDTH
        );
    }
}
