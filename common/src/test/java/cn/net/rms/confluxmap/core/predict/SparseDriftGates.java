package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Phase 5 sparse-seam drift gates shared by the sparse GPU suites. The shim's packed sparse
 * entries serve the fork's fp32 kernels, whose outputs approximate the CPU reference, so the
 * former bit-equality gates become bounds: biome-style planes under a 2% mismatch rate,
 * height-style planes under max |dY| &lt;= 8 over same-status pairs. Every comparison prints its
 * measured counts and rates for the post-land calibration. Planes the float kernels write
 * directly (biomeId, terrainY) additionally require a nonzero mismatch count where the caller
 * proves the seam was GPU-served - zero drift there would mean the seam silently fell back to
 * the CPU loops. Derived planes (fluidY, baseSurfaceY, surfaceFlags) legitimately settle at zero
 * drift at a scene whose drifted columns cross no fluid threshold, so only their bounds gate.
 */
final class SparseDriftGates {
    private SparseDriftGates() {
    }

    /**
     * Per-plane drift gate for one {@link BaselineGrid} pair. {@code requireBiomeDrift}/
     * {@code requireHeightDrift} switch on the nonzero-mismatch proof for the directly-served
     * planes; callers pass the GPU-served evidence they measured (always {@code true} behind a
     * class-level serving assumption).
     */
    static void assertGridsDriftBounded(
        final BaselineGrid expected,
        final BaselineGrid actual,
        final String where,
        final boolean requireBiomeDrift,
        final boolean requireHeightDrift
    ) {
        assertCategoricalPlane(expected.biomeId, actual.biomeId, "biomeId", where, requireBiomeDrift);
        assertHeightPlane(
            expected.terrainY, actual.terrainY, BaselineGrid.NO_SURFACE, "terrainY", where, requireHeightDrift);
        assertHeightPlane(expected.fluidY, actual.fluidY, BaselineGrid.NO_FLUID, "fluidY", where, false);
        assertHeightPlane(
            expected.baseSurfaceY, actual.baseSurfaceY, BaselineGrid.NO_SURFACE, "baseSurfaceY", where, false);
        assertCategoricalPlane(expected.surfaceFlags, actual.surfaceFlags, "surfaceFlags", where, false);
    }

    /**
     * Transport-parity gate for one {@link BaselineGrid} pair produced by two dispatch shapes
     * of the same requests (packed chunk vs per-point repair, full tile vs window). Both sides
     * run fp32 since the precision unification, and the height/biome kernel entries round
     * independently, so the former bit-equality contract becomes tight bounds: height planes
     * within max |dY| &lt;= 2 and categorical planes under a 2% mismatch rate, with measured
     * counts printed. A broken transport (wrong coordinates, dropped points) still fails
     * loudly - its errors are index-scale, not rounding-scale.
     */
    static void assertGridsTransportParity(
        final BaselineGrid expected,
        final BaselineGrid actual,
        final String where
    ) {
        assertTightHeightPlane(expected.terrainY, actual.terrainY, BaselineGrid.NO_SURFACE, "terrainY", where);
        assertCategoricalPlane(expected.biomeId, actual.biomeId, "biomeId", where, false);
        assertTightHeightPlane(expected.fluidY, actual.fluidY, BaselineGrid.NO_FLUID, "fluidY", where);
        assertTightHeightPlane(
            expected.baseSurfaceY, actual.baseSurfaceY, BaselineGrid.NO_SURFACE, "baseSurfaceY", where);
        assertCategoricalPlane(expected.surfaceFlags, actual.surfaceFlags, "surfaceFlags", where, false);
    }

    /**
     * Height plane within max |dY| &lt;= 2 over same-status pairs (fp32 rounding across kernel
     * entries); a sentinel-status flip (e.g. an aquifer threshold crossed by a 1-block drift)
     * counts as a mismatch under the rate bound instead of an index-scale |dY|.
     */
    private static void assertTightHeightPlane(
        final int[] expected,
        final int[] actual,
        final int sentinel,
        final String plane,
        final String where
    ) {
        assertEquals(expected.length, actual.length, plane + " " + where + " length");
        int maxAbsDy = 0;
        long mismatches = 0;
        for (int i = 0; i < expected.length; i++) {
            final int a = expected[i];
            final int b = actual[i];
            if (a == b) {
                continue;
            }
            mismatches++;
            if ((a == sentinel) != (b == sentinel)) {
                continue; // status flip: the rate bound covers it, no meaningful |dY|
            }
            if (a == sentinel) {
                continue; // both sentinel but unequal: impossible for one sentinel value
            }
            final int dy = Math.abs(a - b);
            if (dy > maxAbsDy) {
                maxAbsDy = dy;
            }
        }
        final double rate = 100.0 * mismatches / expected.length;
        System.out.printf(
            "%s %s transport parity: mismatch %d/%d (%.4f%%), max |dY| %d%n",
            plane, where, mismatches, expected.length, rate, maxAbsDy
        );
        assertTrue(
            maxAbsDy <= 2,
            plane + " " + where + ": max |dY| " + maxAbsDy + " > 2 transport-parity bound"
        );
        assertTrue(
            rate < 2.0,
            plane + " " + where + ": mismatch rate " + rate + "% >= 2.0% transport-parity bound"
        );
    }

    /**
     * Categorical-plane drift gate (biome ids, surface flags): the mismatch rate must stay
     * under the 2% smoke bound.
     */
    static void assertCategoricalPlane(
        final int[] expected,
        final int[] actual,
        final String plane,
        final String where,
        final boolean requireDrift
    ) {
        assertEquals(expected.length, actual.length, plane + " " + where + " length");
        long mismatches = 0;
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                mismatches++;
            }
        }
        final double rate = 100.0 * mismatches / expected.length;
        System.out.printf(
            "%s %s drift: mismatch %d/%d (%.4f%%)%n", plane, where, mismatches, expected.length, rate
        );
        assertTrue(
            !requireDrift || mismatches > 0,
            plane + " " + where + ": zero drift - the seam did not serve float"
        );
        assertTrue(rate < 2.0, plane + " " + where + ": mismatch rate " + rate + "% >= 2.0% smoke bound");
    }

    /**
     * Height-plane drift gate: max |dY| over same-status pairs (both real or both sentinel)
     * must stay under the 8-block smoke bound. A sentinel-status flip (water/land category
     * change at the coastline) is a mismatch but not a |dY| - it is the expected consequence of
     * block-level height drift near the sea-level threshold and prints separately rather than
     * failing the bound.
     */
    static void assertHeightPlane(
        final int[] expected,
        final int[] actual,
        final int sentinel,
        final String plane,
        final String where,
        final boolean requireDrift
    ) {
        assertEquals(expected.length, actual.length, plane + " " + where + " length");
        final HeightDrift drift = measureHeightPlane(expected, actual, sentinel);
        printHeightDrift(drift, plane, where);
        assertTrue(
            !requireDrift || drift.mismatches > 0,
            plane + " " + where + ": zero drift - the seam did not serve float"
        );
        assertTrue(
            drift.maxAbsDy <= 8,
            plane + " " + where + ": max |dY| " + drift.maxAbsDy + " > 8 smoke bound"
        );
    }

    /**
     * The carve-discontinuity compositions (documented cave-mouth regions, the 30M world
     * border, and any other bucket whose anchor window sits on carved terrain - measured:
     * the 1M-class bucket's composed terrainY reaches |dY| 16, the same pick-a-different-
     * carved-block class): the categorical planes keep the 2% mismatch gate, while the height
     * planes only print their measured distributions. fp32 drift near the density-zero
     * carving boundary can pick a different carved block - |dY| far beyond the 8-block smoke
     * bound is accepted drift for these scenes (the fork's own col harness classifies its
     * border/cave probes print-only for the same reason) - so the printed rates feed the
     * drift ledger instead of failing.
     */
    static void assertGridsCarveClassDrift(
        final BaselineGrid expected,
        final BaselineGrid actual,
        final String where
    ) {
        assertCategoricalPlane(expected.biomeId, actual.biomeId, "biomeId", where, false);
        printHeightPlane(expected.terrainY, actual.terrainY, BaselineGrid.NO_SURFACE, "terrainY", where);
        printHeightPlane(expected.fluidY, actual.fluidY, BaselineGrid.NO_FLUID, "fluidY", where);
        printHeightPlane(
            expected.baseSurfaceY, actual.baseSurfaceY, BaselineGrid.NO_SURFACE, "baseSurfaceY", where);
        assertCategoricalPlane(expected.surfaceFlags, actual.surfaceFlags, "surfaceFlags", where, false);
    }

    /** Per-plane |dY| accounting shared by the asserting and print-only height gates. */
    private record HeightDrift(long mismatches, long statusFlips, int maxAbsDy, long[] hist, int length) {
    }

    private static HeightDrift measureHeightPlane(final int[] expected, final int[] actual, final int sentinel) {
        long mismatches = 0;
        long statusFlips = 0;
        int maxAbsDy = 0;
        final long[] hist = new long[7]; // |dY| = 0, 1, 2, 3-4, 5-8, 9-16, >16
        for (int i = 0; i < expected.length; i++) {
            final int a = expected[i];
            final int b = actual[i];
            if (a == b) {
                hist[0]++;
                continue;
            }
            mismatches++;
            if ((a == sentinel) != (b == sentinel)) {
                statusFlips++;
                continue;
            }
            if (a == sentinel) {
                continue; // both sentinel but unequal: impossible for one sentinel value
            }
            final int dy = Math.abs(a - b);
            if (dy > maxAbsDy) {
                maxAbsDy = dy;
            }
            hist[dy == 1 ? 1 : dy == 2 ? 2 : dy <= 4 ? 3 : dy <= 8 ? 4 : dy <= 16 ? 5 : 6]++;
        }
        return new HeightDrift(mismatches, statusFlips, maxAbsDy, hist, expected.length);
    }

    private static void printHeightDrift(final HeightDrift drift, final String plane, final String where) {
        System.out.printf(
            "%s %s drift: mismatch %d/%d (%.4f%%), max |dY| %d, status flips %d, |dY| hist"
                + " [0=%d 1=%d 2=%d 3-4=%d 5-8=%d 9-16=%d >16=%d]%n",
            plane, where, drift.mismatches, drift.length,
            100.0 * drift.mismatches / drift.length,
            drift.maxAbsDy, drift.statusFlips,
            drift.hist[0], drift.hist[1], drift.hist[2],
            drift.hist[3], drift.hist[4], drift.hist[5], drift.hist[6]
        );
    }

    /** Height-plane accounting without the |dY| bound - the carve-discontinuity classes. */
    private static void printHeightPlane(
        final int[] expected,
        final int[] actual,
        final int sentinel,
        final String plane,
        final String where
    ) {
        assertEquals(expected.length, actual.length, plane + " " + where + " length");
        printHeightDrift(measureHeightPlane(expected, actual, sentinel), plane, where);
    }
}
