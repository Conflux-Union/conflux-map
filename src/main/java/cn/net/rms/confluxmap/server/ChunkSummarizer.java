package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.core.predict.CubiomesBiomeIds;
import cn.net.rms.confluxmap.nativepredict.NativeChunkNbtScanner;
import net.minecraft.nbt.NbtCompound;

/** Converts a narrow column source into a cheap surface-only chunk summary. */
public final class ChunkSummarizer {
    /**
     * Resolves a block id string (e.g. {@code minecraft:oak_planks}) to its vanilla map-colour
     * id, or a negative value when unknown. Kept as a seam so the registry-backed resolver
     * (which needs a bootstrapped Minecraft) stays out of this NBT-only class and its tests.
     */
    @FunctionalInterface
    public interface MapColorResolver extends ChunkColumnSummarizer.MapColorResolver {
    }

    private static final MapColorResolver UNRESOLVED = name -> -1;

    private final MapColorResolver mapColors;
    private final ChunkColumnSummarizer columns;

    public ChunkSummarizer() {
        this(UNRESOLVED);
    }

    public ChunkSummarizer(final MapColorResolver mapColors) {
        this.mapColors = mapColors == null ? UNRESOLVED : mapColors;
        this.columns = new ChunkColumnSummarizer(this.mapColors);
    }

    public SummaryCodec.Chunk summarize(final NbtCompound root) {
        return summarize(new NbtChunkColumnSource(root));
    }

    public SummaryCodec.Chunk summarize(final ChunkColumnSource source) {
        return columns.summarize(source);
    }

    SummaryCodec.SampledChunk summarizeForLod(final NbtCompound root, final int lod) {
        return summarizeForLod(new NbtChunkColumnSource(root), lod);
    }

    SummaryCodec.SampledChunk summarizeForLod(final ChunkColumnSource source, final int lod) {
        return columns.summarizeForLod(source, lod);
    }

    SummaryCodec.SampledChunk summarizeNative(final NativeChunkNbtScanner.Chunk chunk) {
        if (chunk == null || !chunk.generated()) {
            return SummaryCodec.SampledChunk.empty(chunk == null ? 16 : chunk.sampleStride());
        }
        final SummaryCodec.Column[] columns = new SummaryCodec.Column[chunk.samples().length];
        for (int i = 0; i < columns.length; i++) {
            final NativeChunkNbtScanner.Sample sample = chunk.samples()[i];
            final int biome = sample.biomeId() >= 0
                ? sample.biomeId()
                : biomeId(sample.biomeName());
            final BlockInfo surface;
            if (sample.fluidKind() == 1) {
                surface = new BlockInfo(SurfaceKind.WATER, 12);
            } else if (sample.fluidKind() == 2) {
                surface = new BlockInfo(SurfaceKind.LAVA, 4);
            } else {
                surface = classify(sample.surfaceBlock(), mapColors);
            }
            final int floorMapColorId = sample.fluidDepth() == 0
                ? ProtoColor.NONE
                : classify(sample.floorBlock(), mapColors).mapColorId;
            columns[i] = new SummaryCodec.Column(
                biome & 255,
                clampShort(sample.surfaceY()),
                surface.kind.ordinal(),
                surface.mapColorId,
                clamp(sample.fluidDepth()),
                floorMapColorId
            );
        }
        return new SummaryCodec.SampledChunk(
            true, chunk.revision(), chunk.sampleStride(), columns
        );
    }

    /** Classifies one block id string into its surface kind and map colour; also used by {@code FlatWorldBaseline}. */
    public static BlockInfo classify(final String value, final MapColorResolver mapColors) {
        final ChunkColumnSummarizer.BlockInfo info = ChunkColumnSummarizer.classify(
            value, mapColors
        );
        return new BlockInfo(info.kind(), info.mapColorId());
    }

    private static int clamp(final int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static short clampShort(final int value) {
        return (short) Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, value));
    }

    private static int biomeId(final String name) {
        if (name == null) {
            return 1;
        }
        final int separator = name.indexOf(':');
        final String path = separator >= 0 ? name.substring(separator + 1) : name;
        return CubiomesBiomeIds.idForName(path).orElse(1);
    }

    /** One classified block: its map surface kind and vanilla map colour id. */
    public record BlockInfo(SurfaceKind kind, int mapColorId) {
    }

    private static final class ProtoColor {
        private static final int NONE = 255;
    }
}
