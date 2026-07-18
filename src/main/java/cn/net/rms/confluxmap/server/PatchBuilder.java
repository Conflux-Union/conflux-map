package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.net.DiffSpec;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.core.predict.BaselineSampler;
import cn.net.rms.confluxmap.core.predict.BaselineGrid;
import cn.net.rms.confluxmap.core.predict.BaselineDeriver;
import cn.net.rms.confluxmap.core.predict.CanopyStylizer;
import cn.net.rms.confluxmap.core.predict.DerivedGrid;
import java.util.ArrayList;
import java.util.List;

/** Builds one correction patch from summaries and the same deterministic client baseline. */
public final class PatchBuilder {
    public record Result(int mode, long revision, byte[] presence, byte[] body, int recordCount) {
    }

    public Result build(
        final SummaryCodec.Region summary,
        final int lod,
        final int tileX,
        final int tileZ,
        final long sinceRevision,
        final BaselineGrid baseline,
        final boolean absolute
    ) {
        if (summary == null || lod < 0 || lod > 2 || baseline == null) {
            return unavailable();
        }
        return buildWithDerived(summary, lod, tileX, tileZ, sinceRevision, baseline, BaselineDeriver.derive(baseline), absolute);
    }

    private Result buildWithDerived(
        final SummaryCodec.Region summary,
        final int lod,
        final int tileX,
        final int tileZ,
        final long sinceRevision,
        final BaselineGrid baseline,
        final DerivedGrid derived,
        final boolean absolute
    ) {
        final byte[] presence = new byte[Proto.PATCH_PRESENCE_BYTES];
        final List<PatchCodec.Sample> records = new ArrayList<>();
        long revision = 0L;
        final int blockScale = 1 << lod;
        for (int chunkZ = 0; chunkZ < 16; chunkZ++) {
            for (int chunkX = 0; chunkX < 16; chunkX++) {
                final int chunkIndex = chunkZ * 16 + chunkX;
                final SummaryCodec.Chunk chunk = summary.chunks()[chunkIndex];
                if (!chunk.generated()) {
                    continue;
                }
                presence[chunkIndex >>> 3] |= (byte) (1 << (chunkIndex & 7));
                revision = Math.max(revision, chunk.revision());
            }
        }
        for (int z = 0; z < 256; z++) {
            for (int x = 0; x < 256; x++) {
                final int blockX = x * blockScale + (blockScale >>> 1);
                final int blockZ = z * blockScale + (blockScale >>> 1);
                final int chunkX = Math.min(15, Math.max(0, blockX >>> 4));
                final int chunkZIndex = Math.min(15, Math.max(0, blockZ >>> 4));
                final SummaryCodec.Chunk chunk = summary.chunks()[chunkZIndex * 16 + chunkX];
                if (!chunk.generated() || chunk.revision() <= sinceRevision) {
                    continue;
                }
                final SummaryCodec.Column actual = chunk.columns()[(blockZ & 15) * 16 + (blockX & 15)];
                if (actual == null) {
                    continue;
                }
                final int baseIndex = BaselineGrid.index(x, z);
                final DiffSpec.Sample expected = new DiffSpec.Sample(
                    baseline.biomeId[baseIndex], derived.surfaceY[baseIndex], derived.kind[baseIndex], Proto.MAP_COLOR_NONE,
                    derived.fluidDepth[baseIndex]
                );
                final DiffSpec.Sample observed = new DiffSpec.Sample(
                    actual.biomeId(), actual.surfaceY(), actual.kind(), actual.mapColorId(), actual.fluidDepth()
                );
                if (absolute || DiffSpec.differs(expected, observed)) {
                    records.add(new PatchCodec.Sample(
                        z * 256 + x, actual.biomeId(), actual.surfaceY(), actual.kind(), actual.mapColorId(), actual.fluidDepth()
                    ));
                }
            }
        }
        final int mode = records.isEmpty() ? Proto.PATCH_MODE_UNCHANGED
            : (absolute ? Proto.PATCH_MODE_ABSOLUTE : Proto.PATCH_MODE_RESIDUAL);
        final byte[] body = records.isEmpty() ? new byte[0] : PatchCodec.encode(records);
        return new Result(mode, revision, presence, body, records.size());
    }

    public Result buildFromSampler(
        final SummaryCodec.Region summary,
        final int lod,
        final int tileX,
        final int tileZ,
        final long sinceRevision,
        final BaselineSampler sampler,
        final boolean end,
        final long seed,
        final boolean absolute
    ) {
        final int originX = tileX * 256 * (1 << lod);
        final int originZ = tileZ * 256 * (1 << lod);
        final BaselineGrid baseline = cn.net.rms.confluxmap.core.predict.LodSampling.sample(
            sampler, end, lod, originX, originZ
        );
        if (baseline == null) {
            return unavailable();
        }
        final DerivedGrid derived = BaselineDeriver.derive(baseline);
        CanopyStylizer.apply(derived, baseline, seed, lod, originX, originZ);
        return buildWithDerived(summary, lod, tileX, tileZ, sinceRevision, baseline, derived, absolute);
    }

    /** Absolute fallback used when the server cannot load the matching native predictor. */
    public Result buildAbsolute(final SummaryCodec.Region summary, final int lod, final long sinceRevision) {
        if (summary == null || lod < 0 || lod > 2) {
            return unavailable();
        }
        final byte[] presence = new byte[Proto.PATCH_PRESENCE_BYTES];
        final List<PatchCodec.Sample> records = new ArrayList<>();
        long revision = 0L;
        for (int chunkZ = 0; chunkZ < 16; chunkZ++) {
            for (int chunkX = 0; chunkX < 16; chunkX++) {
                final int chunkIndex = chunkZ * 16 + chunkX;
                final SummaryCodec.Chunk chunk = summary.chunks()[chunkIndex];
                if (!chunk.generated()) {
                    continue;
                }
                presence[chunkIndex >>> 3] |= (byte) (1 << (chunkIndex & 7));
                revision = Math.max(revision, chunk.revision());
                if (chunk.revision() <= sinceRevision) {
                    continue;
                }
                for (int column = 0; column < 256; column++) {
                    final SummaryCodec.Column sample = chunk.columns()[column];
                    if (sample == null) {
                        continue;
                    }
                    final int localX = chunkX * 16 + (column & 15);
                    final int localZ = chunkZ * 16 + (column >>> 4);
                    records.add(new PatchCodec.Sample(localZ * 256 + localX, sample.biomeId(), sample.surfaceY(),
                        sample.kind(), sample.mapColorId(), sample.fluidDepth()));
                }
            }
        }
        if (records.isEmpty()) {
            return new Result(Proto.PATCH_MODE_UNCHANGED, revision, presence, new byte[0], 0);
        }
        return new Result(Proto.PATCH_MODE_ABSOLUTE, revision, presence, PatchCodec.encode(records), records.size());
    }

    public static Result unavailable() {
        return new Result(Proto.PATCH_MODE_UNAVAILABLE, 0L, new byte[Proto.PATCH_PRESENCE_BYTES], new byte[0], 0);
    }
}
