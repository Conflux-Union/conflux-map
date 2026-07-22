package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.net.DiffSpec;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.predict.BaselineDeriver;
import cn.net.rms.confluxmap.core.predict.BaselineGrid;
import cn.net.rms.confluxmap.core.predict.BaselineSampler;
import cn.net.rms.confluxmap.core.predict.CanopyStylizer;
import cn.net.rms.confluxmap.core.predict.DerivedGrid;
import cn.net.rms.confluxmap.core.predict.FlatBaseline;
import cn.net.rms.confluxmap.core.predict.LodSampling;
import java.util.ArrayList;
import java.util.List;

/** Builds one correction patch from summaries and the same deterministic client baseline. */
public final class PatchBuilder {
    public static final int MAX_SUPPORTED_LOD = 2;

    public record Result(int mode, long revision, byte[] presence, byte[] body, int recordCount) {
    }

    /** Builds a residual or absolute patch from a tile-wide summary grid. */
    public Result build(
        final SummaryTile summary,
        final long sinceRevision,
        final BaselineGrid baseline,
        final boolean absolute
    ) {
        if (summary == null || summary.lod() > MAX_SUPPORTED_LOD || baseline == null) {
            return unavailable();
        }
        return buildWithDerived(summary, sinceRevision, baseline, BaselineDeriver.derive(baseline), Proto.MAP_COLOR_NONE, absolute);
    }

    /**
     * Compatibility overload for callers that only have one LOD-0 region. Higher-LOD callers
     * must provide a {@link SummaryTile}; a single region cannot describe a coarse tile.
     */
    public Result build(
        final SummaryCodec.Region summary,
        final int lod,
        final int tileX,
        final int tileZ,
        final long sinceRevision,
        final BaselineGrid baseline,
        final boolean absolute
    ) {
        if (summary == null) {
            return unavailable();
        }
        return build(new SummaryTile(lod, tileX, tileZ, List.of(summary)), sinceRevision, baseline, absolute);
    }

    private Result buildWithDerived(
        final SummaryTile summary,
        final long sinceRevision,
        final BaselineGrid baseline,
        final DerivedGrid derived,
        final int baselineMapColorId,
        final boolean absolute
    ) {
        final List<PatchCodec.Sample> records = new ArrayList<>();
        for (int z = 0; z < SummaryTile.PIXELS; z++) {
            for (int x = 0; x < SummaryTile.PIXELS; x++) {
                final SummaryTile.Pixel actual = summary.pixel(x, z);
                if (actual == null || !actual.chunk().generated() || actual.column() == null
                    || actual.chunk().revision() <= sinceRevision) {
                    continue;
                }
                final int baseIndex = BaselineGrid.index(x, z);
                final DiffSpec.Sample expected = new DiffSpec.Sample(
                    baseline.biomeId[baseIndex], derived.surfaceY[baseIndex], derived.kind[baseIndex], baselineMapColorId,
                    derived.fluidDepth[baseIndex]
                );
                final SummaryCodec.Column column = actual.column();
                if (column.kind() == SurfaceKind.UNKNOWN.ordinal()) {
                    continue;
                }
                final DiffSpec.Sample observed = new DiffSpec.Sample(
                    column.biomeId(), column.surfaceY(), column.kind(), column.mapColorId(), column.fluidDepth()
                );
                if (absolute || DiffSpec.differs(expected, observed)) {
                    records.add(toSample(x, z, column));
                } else if (sinceRevision != 0L) {
                    records.add(PatchCodec.removal(z * SummaryTile.PIXELS + x));
                }
            }
        }
        return result(summary, records, absolute ? Proto.PATCH_MODE_ABSOLUTE : Proto.PATCH_MODE_RESIDUAL);
    }

    public Result buildFromSampler(
        final SummaryTile summary,
        final long sinceRevision,
        final BaselineSampler sampler,
        final boolean end,
        final long seed,
        final boolean absolute
    ) {
        if (summary == null || summary.lod() > MAX_SUPPORTED_LOD || sampler == null) {
            return unavailable();
        }
        final long originX = summary.originBlockX();
        final long originZ = summary.originBlockZ();
        if (originX < Integer.MIN_VALUE || originX > Integer.MAX_VALUE
            || originZ < Integer.MIN_VALUE || originZ > Integer.MAX_VALUE) {
            return unavailable();
        }
        final BaselineGrid baseline = LodSampling.sample(
            sampler, end, summary.lod(), (int) originX, (int) originZ
        );
        if (baseline == null) {
            return unavailable();
        }
        final DerivedGrid derived = BaselineDeriver.derive(baseline);
        CanopyStylizer.apply(derived, baseline, sampler, seed, summary.lod(), (int) originX, (int) originZ);
        return buildWithDerived(summary, sinceRevision, baseline, derived, Proto.MAP_COLOR_NONE, absolute);
    }

    /**
     * Residual patch against a superflat dimension's uniform surface: the baseline the client
     * composes from the same {@link FlatBaseline} without cubiomes. The expected sample carries
     * the flat top block's real map color, so an untouched flat surface produces no records.
     */
    public Result buildFromUniform(
        final SummaryTile summary,
        final long sinceRevision,
        final FlatBaseline flat,
        final boolean absolute
    ) {
        if (summary == null || summary.lod() > MAX_SUPPORTED_LOD || flat == null) {
            return unavailable();
        }
        return buildWithDerived(
            summary, sinceRevision, flat.toBaselineGrid(), flat.toDerivedGrid(), flat.mapColorId(), absolute
        );
    }

    /** Compatibility overload for a single-region LOD-0 caller. */
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
        if (summary == null) {
            return unavailable();
        }
        return buildFromSampler(
            new SummaryTile(lod, tileX, tileZ, List.of(summary)), sinceRevision, sampler, end, seed, absolute
        );
    }

    /** Absolute fallback used when the server cannot load the matching native predictor. */
    public Result buildAbsolute(final SummaryTile summary, final long sinceRevision) {
        if (summary == null || summary.lod() > MAX_SUPPORTED_LOD) {
            return unavailable();
        }
        final List<PatchCodec.Sample> records = new ArrayList<>();
        for (int z = 0; z < SummaryTile.PIXELS; z++) {
            for (int x = 0; x < SummaryTile.PIXELS; x++) {
                final SummaryTile.Pixel actual = summary.pixel(x, z);
                if (actual == null || !actual.chunk().generated() || actual.column() == null
                    || actual.chunk().revision() <= sinceRevision) {
                    continue;
                }
                if (actual.column().kind() == SurfaceKind.UNKNOWN.ordinal()) {
                    continue;
                }
                records.add(toSample(x, z, actual.column()));
            }
        }
        return result(summary, records, Proto.PATCH_MODE_ABSOLUTE);
    }

    /** Compatibility overload for the LOD-0 single-region caller. */
    public Result buildAbsolute(final SummaryCodec.Region summary, final int lod, final long sinceRevision) {
        if (summary == null || lod != 0) {
            return unavailable();
        }
        return buildAbsolute(new SummaryTile(0, summary.rx(), summary.rz(), List.of(summary)), sinceRevision);
    }

    private static PatchCodec.Sample toSample(final int pixelX, final int pixelZ, final SummaryCodec.Column column) {
        return new PatchCodec.Sample(
            pixelZ * SummaryTile.PIXELS + pixelX,
            column.biomeId(), column.surfaceY(), column.kind(), column.mapColorId(), column.fluidDepth()
        );
    }

    private static Result result(
        final SummaryTile summary, final List<PatchCodec.Sample> records, final int nonEmptyMode
    ) {
        if (records.isEmpty()) {
            return new Result(Proto.PATCH_MODE_UNCHANGED, summary.revision(), summary.presence(), new byte[0], 0);
        }
        return new Result(nonEmptyMode, summary.revision(), summary.presence(), PatchCodec.encode(records), records.size());
    }

    public static Result unavailable() {
        return new Result(Proto.PATCH_MODE_UNAVAILABLE, 0L, new byte[Proto.PATCH_PRESENCE_BYTES], new byte[0], 0);
    }
}
