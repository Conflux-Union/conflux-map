package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.model.MapPixel;
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
import cn.net.rms.confluxmap.core.util.TileMath;
import java.util.ArrayList;
import java.util.List;

/** Builds one correction patch from summaries and the same deterministic client baseline. */
public final class PatchBuilder {
    public record PreparedBaseline(
        BaselineGrid baseline,
        DerivedGrid derived,
        int mapColorId,
        boolean absolute
    ) {
        public static PreparedBaseline absoluteOnly() {
            return new PreparedBaseline(null, null, Proto.MAP_COLOR_NONE, true);
        }
    }

    public record Result(int mode, long revision, byte[] presence, byte[] body, int recordCount) {
    }

    /** Builds a residual or absolute patch from a tile-wide summary grid. */
    public Result build(
        final SummaryView summary,
        final long sinceRevision,
        final BaselineGrid baseline,
        final boolean absolute
    ) {
        if (!supported(summary) || baseline == null) {
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
        final SummaryView summary,
        final long sinceRevision,
        final BaselineGrid baseline,
        final DerivedGrid derived,
        final int baselineMapColorId,
        final boolean absolute
    ) {
        final byte[] evaluated = new byte[PatchCodec.MASK_BYTES];
        final List<PatchCodec.Sample> records = new ArrayList<>();
        for (int z = 0; z < SummaryTile.PIXELS; z++) {
            for (int x = 0; x < SummaryTile.PIXELS; x++) {
                final SummaryView.Pixel actual = summary.pixel(x, z);
                if (actual == null || !actual.generated() || actual.column() == null) {
                    continue;
                }
                final SummaryCodec.Column column = actual.column();
                if (column.kind() == SurfaceKind.UNKNOWN.ordinal()) {
                    continue;
                }
                final int pixel = z * SummaryTile.PIXELS + x;
                PatchCodec.setEvaluated(evaluated, pixel);
                final int baseIndex = BaselineGrid.index(x, z);
                final MapPixel expected = new MapPixel(
                    baseline.biomeId[baseIndex],
                    derived.surfaceY[baseIndex],
                    derived.kind[baseIndex] & 255,
                    baselineMapColorId,
                    derived.fluidDepth[baseIndex],
                    MapPixel.MAP_COLOR_NONE
                );
                if (absolute || !expected.equals(column.pixel())) {
                    records.add(toSample(x, z, column));
                }
            }
        }
        return result(
            summary,
            evaluated,
            records,
            absolute ? Proto.PATCH_MODE_ABSOLUTE : Proto.PATCH_MODE_RESIDUAL
        );
    }

    public Result buildFromSampler(
        final SummaryView summary,
        final long sinceRevision,
        final BaselineSampler sampler,
        final boolean end,
        final long seed,
        final boolean absolute
    ) {
        final PreparedBaseline prepared = prepareFromSampler(summary, sampler, end, seed, absolute);
        return prepared == null ? unavailable() : buildPrepared(summary, sinceRevision, prepared);
    }

    public PreparedBaseline prepareFromSampler(
        final SummaryView summary,
        final BaselineSampler sampler,
        final boolean end,
        final long seed,
        final boolean absolute
    ) {
        if (!supported(summary) || sampler == null) {
            return null;
        }
        final long originX = summary.originBlockX();
        final long originZ = summary.originBlockZ();
        if (originX < Integer.MIN_VALUE || originX > Integer.MAX_VALUE
            || originZ < Integer.MIN_VALUE || originZ > Integer.MAX_VALUE) {
            return null;
        }
        final BaselineGrid baseline = LodSampling.sample(
            sampler, end, summary.lod(), (int) originX, (int) originZ
        );
        if (baseline == null) {
            return null;
        }
        final DerivedGrid derived = BaselineDeriver.derive(baseline);
        CanopyStylizer.apply(derived, baseline, seed, summary.lod(), (int) originX, (int) originZ);
        return new PreparedBaseline(baseline, derived, Proto.MAP_COLOR_NONE, absolute);
    }

    /**
     * Residual patch against a superflat dimension's uniform surface: the baseline the client
     * composes from the same {@link FlatBaseline} without cubiomes. The expected sample carries
     * the flat top block's real map color, so an untouched flat surface produces no records.
     */
    public Result buildFromUniform(
        final SummaryView summary,
        final long sinceRevision,
        final FlatBaseline flat,
        final boolean absolute
    ) {
        final PreparedBaseline prepared = prepareFromUniform(summary, flat, absolute);
        return prepared == null ? unavailable() : buildPrepared(summary, sinceRevision, prepared);
    }

    public PreparedBaseline prepareFromUniform(
        final SummaryView summary,
        final FlatBaseline flat,
        final boolean absolute
    ) {
        if (!supported(summary) || flat == null) {
            return null;
        }
        return new PreparedBaseline(
            flat.toBaselineGrid(), flat.toDerivedGrid(), flat.mapColorId(), absolute
        );
    }

    public Result buildPrepared(
        final SummaryView summary,
        final long sinceRevision,
        final PreparedBaseline prepared
    ) {
        if (!supported(summary) || prepared == null) {
            return unavailable();
        }
        if (prepared.baseline() == null || prepared.derived() == null) {
            return buildAbsolute(summary, sinceRevision);
        }
        return buildWithDerived(
            summary,
            sinceRevision,
            prepared.baseline(),
            prepared.derived(),
            prepared.mapColorId(),
            prepared.absolute()
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
    public Result buildAbsolute(final SummaryView summary, final long sinceRevision) {
        if (!supported(summary)) {
            return unavailable();
        }
        final byte[] evaluated = new byte[PatchCodec.MASK_BYTES];
        final List<PatchCodec.Sample> records = new ArrayList<>();
        for (int z = 0; z < SummaryTile.PIXELS; z++) {
            for (int x = 0; x < SummaryTile.PIXELS; x++) {
                final SummaryView.Pixel actual = summary.pixel(x, z);
                if (actual == null || !actual.generated() || actual.column() == null
                    || actual.column().kind() == SurfaceKind.UNKNOWN.ordinal()) {
                    continue;
                }
                PatchCodec.setEvaluated(evaluated, z * SummaryTile.PIXELS + x);
                records.add(toSample(x, z, actual.column()));
            }
        }
        return result(summary, evaluated, records, Proto.PATCH_MODE_ABSOLUTE);
    }

    /** Compatibility overload for the LOD-0 single-region caller. */
    public Result buildAbsolute(final SummaryCodec.Region summary, final int lod, final long sinceRevision) {
        if (summary == null || lod != 0) {
            return unavailable();
        }
        return buildAbsolute(new SummaryTile(0, summary.rx(), summary.rz(), List.of(summary)), sinceRevision);
    }

    private static PatchCodec.Sample toSample(final int pixelX, final int pixelZ, final SummaryCodec.Column column) {
        return new PatchCodec.Sample(pixelZ * SummaryTile.PIXELS + pixelX, column.pixel());
    }

    private static Result result(
        final SummaryView summary,
        final byte[] evaluated,
        final List<PatchCodec.Sample> records,
        final int mode
    ) {
        return new Result(
            mode,
            summary.revision(),
            summary.presence(),
            PatchCodec.encode(new PatchCodec.Patch(evaluated, records)),
            records.size()
        );
    }

    public static Result unavailable() {
        return new Result(Proto.PATCH_MODE_UNAVAILABLE, 0L, new byte[Proto.PATCH_PRESENCE_BYTES], new byte[0], 0);
    }

    private static boolean supported(final SummaryView summary) {
        return summary != null && summary.lod() >= 0 && summary.lod() <= TileMath.MAX_LOD;
    }
}
