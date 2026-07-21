package cn.net.rms.confluxmap.core.net;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.predict.BiomeTable;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Shared tolerant equivalence predicate used by both the client and companion server. */
public final class DiffSpec {
    private static final int MAP_COLOR_NONE = Proto.MAP_COLOR_NONE;
    // Grass, sand, plant, snow, dirt and wood: what naturally sits under a real tree canopy.
    // 10 (dirt) is present because the registry-backed summarizer reports real map colours;
    // the pre-registry heuristic folded dirt into 1.
    private static final Set<Integer> NATURAL_GROUND_MAP_COLORS = Set.of(MAP_COLOR_NONE, 1, 2, 7, 8, 10, 13);

    private DiffSpec() {
    }

    public record Sample(int biomeId, int surfaceY, int kind, int mapColorId, int fluidDepth) {
    }

    public static boolean differs(final Sample baseline, final Sample actual) {
        return differs(baseline, actual, baseline.fluidDepth());
    }

    public static boolean isCorrection(final Sample baseline, final Sample actual) {
        return differs(baseline, actual);
    }

    public static boolean differs(
        final int baselineBiomeId,
        final int baselineSurfaceY,
        final int baselineKind,
        final Sample actual,
        final double treeCover
    ) {
        final SurfaceKind expectedKind = SurfaceKind.byOrdinal(baselineKind);
        final SurfaceKind actualKind = SurfaceKind.byOrdinal(actual.kind());
        if (keepsPredictedCanopy(expectedKind, actualKind, actual.mapColorId())) {
            return false;
        }
        if (baselineBiomeId != actual.biomeId()) {
            return true;
        }
        if (!kindEquivalent(expectedKind, actualKind, treeCover)) {
            return true;
        }
        final int heightTolerance = treeCover > 0.0 && forestEquivalent(expectedKind, actualKind) ? 6 : 2;
        if (Math.abs(baselineSurfaceY - actual.surfaceY()) > heightTolerance) {
            return true;
        }
        if (fluidBucket(0) != fluidBucket(actual.fluidDepth())) {
            // A baseline without a fluid depth is the common case. Callers with a non-zero
            // baseline use the overload below so the bucket comparison remains symmetric.
            return true;
        }
        return actual.mapColorId() != MAP_COLOR_NONE
            && !expectedMapColors(baselineBiomeId).contains(actual.mapColorId());
    }

    public static boolean differs(final Sample baseline, final Sample actual, final int baselineFluidDepth) {
        final SurfaceKind expected = SurfaceKind.byOrdinal(baseline.kind());
        final SurfaceKind observed = SurfaceKind.byOrdinal(actual.kind());
        if (keepsPredictedCanopy(expected, observed, actual.mapColorId())) {
            return false;
        }
        if (baseline.biomeId() != actual.biomeId()) {
            return true;
        }
        final double cover = BiomeTable.get(baseline.biomeId()).treeCover();
        if (!kindEquivalent(expected, observed, cover)) {
            return true;
        }
        final int tolerance = cover > 0.0 && forestEquivalent(expected, observed) ? 6 : 2;
        return Math.abs(baseline.surfaceY() - actual.surfaceY()) > tolerance
            || fluidBucket(baselineFluidDepth) != fluidBucket(actual.fluidDepth())
            || (actual.mapColorId() != MAP_COLOR_NONE && !expectedMapColors(baseline.biomeId()).contains(actual.mapColorId()));
    }

    public static boolean keepsPredictedCanopy(
        final SurfaceKind expected,
        final SurfaceKind actual,
        final int actualMapColorId
    ) {
        return actual == SurfaceKind.FOLIAGE
            || (expected == SurfaceKind.FOLIAGE
                && actual == SurfaceKind.LAND
                && NATURAL_GROUND_MAP_COLORS.contains(actualMapColorId));
    }

    public static int fluidBucket(final int depth) {
        if (depth <= 0) {
            return 0;
        }
        if (depth <= 3) {
            return 1;
        }
        if (depth <= 9) {
            return 2;
        }
        return 3;
    }

    public static boolean kindEquivalent(final SurfaceKind expected, final SurfaceKind actual, final double treeCover) {
        if (expected == actual) {
            return true;
        }
        return treeCover > 0.0
            && ((expected == SurfaceKind.LAND && actual == SurfaceKind.FOLIAGE)
                || (expected == SurfaceKind.FOLIAGE && actual == SurfaceKind.LAND));
    }

    private static boolean forestEquivalent(final SurfaceKind expected, final SurfaceKind actual) {
        return (expected == SurfaceKind.LAND && actual == SurfaceKind.FOLIAGE)
            || (expected == SurfaceKind.FOLIAGE && actual == SurfaceKind.LAND);
    }

    /** Vanilla map-colour ids expected for a generated biome. The set is intentionally tolerant. */
    public static Set<Integer> expectedMapColors(final int biomeId) {
        final BiomeTable.Entry entry = BiomeTable.get(biomeId);
        final Set<Integer> result = new HashSet<>();
        if (entry.waterBiome()) {
            result.add(12);
            result.add(13);
            return Collections.unmodifiableSet(result);
        }
        switch (entry.kind()) {
            case SAND:
                result.add(2);
                result.add(15);
                result.add(16);
                break;
            case SNOW:
            case ICE:
                result.add(3);
                result.add(4);
                result.add(5);
                result.add(8);
                result.add(11);
                result.add(12);
                break;
            case FOLIAGE:
                result.add(1);
                result.add(7);
                result.add(8);
                result.add(10);
                result.add(13);
                break;
            default:
                // Grass, dirt and common natural blocks. Stone/metal map colours are deliberately
                // absent so a player-built stone structure produces a correction.
                result.add(1);
                result.add(2);
                result.add(7);
                result.add(8);
                result.add(10);
                result.add(13);
                break;
        }
        return Collections.unmodifiableSet(result);
    }
}
