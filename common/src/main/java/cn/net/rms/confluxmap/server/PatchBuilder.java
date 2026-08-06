package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapPixel;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.core.predict.BaselineDeriver;
import cn.net.rms.confluxmap.core.predict.BaselineGrid;
import cn.net.rms.confluxmap.core.predict.BaselineSampler;
import cn.net.rms.confluxmap.core.predict.CanopyStylizer;
import cn.net.rms.confluxmap.core.predict.DerivedGrid;
import cn.net.rms.confluxmap.core.predict.FlatBaseline;
import cn.net.rms.confluxmap.core.predict.LodSampling;
import cn.net.rms.confluxmap.core.predict.PredictionDimensions;
import cn.net.rms.confluxmap.core.util.TileMath;
import java.util.ArrayList;
import java.util.List;

/** Builds one correction patch from summaries and the same deterministic client baseline. */
public final class PatchBuilder {
    public record PreparedBaseline(
        BaselineGrid baseline,
        DerivedGrid derived,
        int mapColorId,
        boolean absolute,
        MapPixel uniformPixel
    ) {
        public PreparedBaseline(
            final BaselineGrid baseline,
            final DerivedGrid derived,
            final int mapColorId,
            final boolean absolute
        ) {
            this(baseline, derived, mapColorId, absolute, null);
        }

        public static PreparedBaseline absoluteOnly() {
            return new PreparedBaseline(null, null, Proto.MAP_COLOR_NONE, true, null);
        }

        public static PreparedBaseline uniform(
            final FlatBaseline flat, final boolean absolute
        ) {
            if (flat == null) {
                throw new IllegalArgumentException("flat baseline is null");
            }
            return new PreparedBaseline(
                null,
                null,
                flat.mapColorId(),
                absolute,
                new MapPixel(
                    flat.biomeId(), flat.surfaceY(), flat.kind(), flat.mapColorId(),
                    flat.fluidDepth(), MapPixel.MAP_COLOR_NONE
                )
            );
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
        return buildWithDerived(
            summary, sinceRevision, baseline, BaselineDeriver.derive(baseline),
            Proto.MAP_COLOR_NONE, null, absolute
        );
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
        final MapPixel uniformPixel,
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
                final MapPixel expected;
                if (uniformPixel != null) {
                    expected = uniformPixel;
                } else {
                    final int baseIndex = BaselineGrid.index(x, z);
                    expected = new MapPixel(
                        baseline.biomeId[baseIndex],
                        derived.surfaceY[baseIndex],
                        derived.kind[baseIndex] & 255,
                        baselineMapColorId,
                        derived.fluidDepth[baseIndex],
                        MapPixel.MAP_COLOR_NONE
                    );
                }
                if (absolute || !expected.sameTerrainSemantics(column.pixel())) {
                    records.add(toSample(x, z, column));
                }
            }
        }
        return result(
            summary,
            sinceRevision,
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

    public Result buildFromSampler(
        final SummaryView summary,
        final long sinceRevision,
        final BaselineSampler sampler,
        final DimensionId dimension,
        final long seed,
        final boolean absolute
    ) {
        final PreparedBaseline prepared = prepareFromSampler(
            summary, sampler, dimension, seed, absolute
        );
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

    /** Prepares the matching Overworld, Nether-roof, or End residual baseline. */
    public PreparedBaseline prepareFromSampler(
        final SummaryView summary,
        final BaselineSampler sampler,
        final DimensionId dimension,
        final long seed,
        final boolean absolute
    ) {
        if (!supported(summary) || sampler == null || !PredictionDimensions.supported(dimension)) {
            return null;
        }
        final long originX = summary.originBlockX();
        final long originZ = summary.originBlockZ();
        if (originX < Integer.MIN_VALUE || originX > Integer.MAX_VALUE
            || originZ < Integer.MIN_VALUE || originZ > Integer.MAX_VALUE) {
            return null;
        }
        final BaselineGrid baseline = LodSampling.sample(
            sampler, dimension, summary.lod(), (int) originX, (int) originZ
        );
        if (baseline == null) {
            return null;
        }
        final DerivedGrid derived = BaselineDeriver.derive(baseline);
        CanopyStylizer.apply(derived, baseline, seed, summary.lod(), (int) originX, (int) originZ);
        final int mapColorId = dimension.equals(DimensionId.NETHER)
            ? PredictionDimensions.NETHER_ROOF_MAP_COLOR_ID
            : Proto.MAP_COLOR_NONE;
        return new PreparedBaseline(baseline, derived, mapColorId, absolute);
    }

    /** Prepares only the coarse output pixels owned by one cropped region page. */
    public PreparedBaseline prepareFromSamplerWindow(
        final SummaryView summary,
        final BaselineSampler sampler,
        final boolean end,
        final long seed,
        final boolean absolute,
        final int minPixelX,
        final int minPixelZ,
        final int maxPixelX,
        final int maxPixelZ
    ) {
        if (!supported(summary) || sampler == null) {
            return null;
        }
        if (end || summary.lod() < 2) {
            // End interpolation and the close-view exact residual lattice cross window bounds.
            // Absolute pages retain quality without recreating a tile-wide baseline here.
            return PreparedBaseline.absoluteOnly();
        }
        final long originX = summary.originBlockX();
        final long originZ = summary.originBlockZ();
        if (originX < Integer.MIN_VALUE || originX > Integer.MAX_VALUE
            || originZ < Integer.MIN_VALUE || originZ > Integer.MAX_VALUE) {
            return null;
        }
        final BaselineGrid baseline = LodSampling.sampleOverworldWindow(
            sampler, summary.lod(), (int) originX, (int) originZ,
            minPixelX, minPixelZ, maxPixelX, maxPixelZ
        );
        if (baseline == null) {
            return null;
        }
        final DerivedGrid derived = BaselineDeriver.deriveWindow(
            baseline, minPixelX, minPixelZ, maxPixelX, maxPixelZ
        );
        CanopyStylizer.applyWindow(
            derived, baseline, seed, summary.lod(), (int) originX, (int) originZ,
            minPixelX, minPixelZ, maxPixelX, maxPixelZ
        );
        return new PreparedBaseline(baseline, derived, Proto.MAP_COLOR_NONE, absolute);
    }

    /** Prepares one cropped region page for a supported dimension baseline. */
    public PreparedBaseline prepareFromSamplerWindow(
        final SummaryView summary,
        final BaselineSampler sampler,
        final DimensionId dimension,
        final long seed,
        final boolean absolute,
        final int minPixelX,
        final int minPixelZ,
        final int maxPixelX,
        final int maxPixelZ
    ) {
        if (!supported(summary) || sampler == null || !PredictionDimensions.supported(dimension)) {
            return null;
        }
        if (dimension.equals(DimensionId.END) || summary.lod() < 2) {
            return PreparedBaseline.absoluteOnly();
        }
        final long originX = summary.originBlockX();
        final long originZ = summary.originBlockZ();
        if (originX < Integer.MIN_VALUE || originX > Integer.MAX_VALUE
            || originZ < Integer.MIN_VALUE || originZ > Integer.MAX_VALUE) {
            return null;
        }
        final BaselineGrid baseline = dimension.equals(DimensionId.NETHER)
            ? LodSampling.sampleNetherRoofWindow(
                sampler, summary.lod(), (int) originX, (int) originZ,
                minPixelX, minPixelZ, maxPixelX, maxPixelZ
            )
            : LodSampling.sampleOverworldWindow(
                sampler, summary.lod(), (int) originX, (int) originZ,
                minPixelX, minPixelZ, maxPixelX, maxPixelZ
            );
        if (baseline == null) {
            return null;
        }
        final DerivedGrid derived = BaselineDeriver.deriveWindow(
            baseline, minPixelX, minPixelZ, maxPixelX, maxPixelZ
        );
        CanopyStylizer.applyWindow(
            derived, baseline, seed, summary.lod(), (int) originX, (int) originZ,
            minPixelX, minPixelZ, maxPixelX, maxPixelZ
        );
        final int mapColorId = dimension.equals(DimensionId.NETHER)
            ? PredictionDimensions.NETHER_ROOF_MAP_COLOR_ID
            : Proto.MAP_COLOR_NONE;
        return new PreparedBaseline(baseline, derived, mapColorId, absolute);
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
        return PreparedBaseline.uniform(flat, absolute);
    }

    public Result buildPrepared(
        final SummaryView summary,
        final long sinceRevision,
        final PreparedBaseline prepared
    ) {
        if (!supported(summary) || prepared == null) {
            return unavailable();
        }
        if (prepared.uniformPixel() == null
            && (prepared.baseline() == null || prepared.derived() == null)) {
            return buildAbsolute(summary, sinceRevision);
        }
        return buildWithDerived(
            summary,
            sinceRevision,
            prepared.baseline(),
            prepared.derived(),
            prepared.mapColorId(),
            prepared.uniformPixel(),
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
        return result(summary, sinceRevision, evaluated, records, Proto.PATCH_MODE_ABSOLUTE);
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
        final long sinceRevision,
        final byte[] evaluated,
        final List<PatchCodec.Sample> records,
        final int mode
    ) {
        final byte[] presence = summary.presence();
        final long[] sourceRevisions = new long[PatchCodec.PIXELS];
        java.util.Arrays.fill(sourceRevisions, Long.MIN_VALUE);
        final byte[] blockLight = new byte[PatchCodec.PIXELS];
        for (int pixel = 0; pixel < PatchCodec.PIXELS; pixel++) {
            if ((evaluated[pixel >>> 3] & (1 << (pixel & 7))) == 0) {
                continue;
            }
            final SummaryView.Pixel source = summary.pixel(pixel & 255, pixel >>> 8);
            if (source != null && source.column() != null) {
                sourceRevisions[pixel] = source.revision();
                blockLight[pixel] = (byte) source.column().blockLight();
            }
        }
        final long revision = snapshotRevision(
            mode, presence, evaluated, records, sourceRevisions, blockLight
        );
        if (sinceRevision != Long.MIN_VALUE && sinceRevision == revision) {
            return new Result(
                Proto.PATCH_MODE_UNCHANGED,
                revision,
                presence,
                new byte[0],
                0
            );
        }
        final byte[] body = PatchCodec.encode(new PatchCodec.Patch(
            evaluated, records, sourceRevisions, blockLight
        ));
        return new Result(
            mode,
            revision,
            presence,
            body,
            records.size()
        );
    }

    /** Stable opaque token for one complete authoritative snapshot. */
    private static long snapshotRevision(
        final int mode,
        final byte[] presence,
        final byte[] evaluated,
        final List<PatchCodec.Sample> records,
        final long[] sourceRevisions,
        final byte[] blockLight
    ) {
        long hash = 0xcbf29ce484222325L;
        hash = fnv1a(hash, mode);
        for (final byte value : presence) {
            hash = fnv1a(hash, value);
        }
        for (final byte value : evaluated) {
            hash = fnv1a(hash, value);
        }
        for (int pixel = 0; pixel < PatchCodec.PIXELS; pixel++) {
            if ((evaluated[pixel >>> 3] & (1 << (pixel & 7))) == 0) {
                continue;
            }
            hash = fnv1aLong(hash, sourceRevisions[pixel]);
            hash = fnv1a(hash, blockLight[pixel]);
        }
        for (final PatchCodec.Sample sample : records) {
            hash = fnv1aInt(hash, sample.pixelIndex());
            hash = fnv1a(hash, sample.biomeId());
            hash = fnv1aInt(hash, sample.surfaceY());
            hash = fnv1a(hash, sample.kind());
            hash = fnv1a(hash, sample.mapColorId());
            hash = fnv1a(hash, sample.fluidDepth());
            hash = fnv1a(hash, sample.floorMapColorId());
            hash = fnv1aString(hash, sample.materialId());
            hash = fnv1aString(hash, sample.floorMaterialId());
        }
        return hash == Long.MIN_VALUE ? Long.MAX_VALUE : hash;
    }

    private static long fnv1aInt(long hash, final int value) {
        for (int shift = 0; shift < Integer.SIZE; shift += Byte.SIZE) {
            hash = fnv1a(hash, value >>> shift);
        }
        return hash;
    }

    private static long fnv1aLong(long hash, final long value) {
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            hash = fnv1a(hash, (int) (value >>> shift));
        }
        return hash;
    }

    private static long fnv1aString(long hash, final String value) {
        final byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (final byte b : bytes) {
            hash = fnv1a(hash, b);
        }
        return fnv1a(hash, 0);
    }

    private static long fnv1a(final long hash, final int value) {
        return (hash ^ (value & 0xFFL)) * 0x100000001b3L;
    }

    public static Result unavailable() {
        return new Result(Proto.PATCH_MODE_UNAVAILABLE, 0L, new byte[Proto.PATCH_PRESENCE_BYTES], new byte[0], 0);
    }

    private static boolean supported(final SummaryView summary) {
        return summary != null && summary.lod() >= 0 && summary.lod() <= TileMath.MAX_LOD;
    }
}
