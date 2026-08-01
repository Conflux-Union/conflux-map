package cn.net.rms.confluxmap.paper;

import cn.net.rms.confluxmap.core.predict.FlatBaseline;
import cn.net.rms.confluxmap.core.predict.NativeBaselineSampler;
import cn.net.rms.confluxmap.core.predict.WorldPreset;
import cn.net.rms.confluxmap.core.util.ChunkRegionSlice;
import cn.net.rms.confluxmap.nativepredict.McVersions;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import cn.net.rms.confluxmap.server.PatchBuilder;
import cn.net.rms.confluxmap.server.SummaryView;

/** Selects the same residual baseline and safe absolute fallbacks as the Fabric companion. */
final class PaperCorrectionBaseline {
    private PaperCorrectionBaseline() {
    }

    static PatchBuilder.PreparedBaseline prepare(
        final PatchBuilder patchBuilder,
        final SummaryView summary,
        final ChunkRegionSlice slice,
        final boolean forceAbsolute,
        final FlatBaseline flat,
        final WorldPreset preset,
        final String worldgenVersion,
        final long seed,
        final int nativeDimension
    ) {
        if (forceAbsolute) {
            return PatchBuilder.PreparedBaseline.absoluteOnly();
        }
        if (preset == WorldPreset.FLAT && flat != null) {
            final PatchBuilder.PreparedBaseline prepared = patchBuilder.prepareFromUniform(
                summary, flat, false
            );
            return prepared == null ? PatchBuilder.PreparedBaseline.absoluteOnly() : prepared;
        }
        final java.util.OptionalInt version = McVersions.toCubiomes(worldgenVersion);
        if (nativeDimension < 0 || !preset.predictable()
            || !NativeLib.available() || version.isEmpty()) {
            return PatchBuilder.PreparedBaseline.absoluteOnly();
        }
        final boolean end = nativeDimension == 1;
        final NativeBaselineSampler sampler = new NativeBaselineSampler(
            version.getAsInt(), seed, nativeDimension, preset.cubiomesFlags()
        );
        final PatchBuilder.PreparedBaseline prepared;
        if (slice == null) {
            prepared = patchBuilder.prepareFromSampler(summary, sampler, end, seed, false);
        } else {
            final int chunksPerTile = 16 << summary.lod();
            final int samplesPerChunk = 16 >> summary.lod();
            final int minX = Math.floorMod(slice.minChunkX(), chunksPerTile) * samplesPerChunk;
            final int minZ = Math.floorMod(slice.minChunkZ(), chunksPerTile) * samplesPerChunk;
            prepared = patchBuilder.prepareFromSamplerWindow(
                summary,
                sampler,
                end,
                seed,
                false,
                minX,
                minZ,
                minX + slice.width() * samplesPerChunk - 1,
                minZ + slice.height() * samplesPerChunk - 1
            );
        }
        return prepared == null ? PatchBuilder.PreparedBaseline.absoluteOnly() : prepared;
    }
}
