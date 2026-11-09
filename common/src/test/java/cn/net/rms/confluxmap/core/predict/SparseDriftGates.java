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
        System.out.printf(
            "%s %s drift: mismatch %d/%d (%.4f%%), max |dY| %d, status flips %d, |dY| hist"
                + " [0=%d 1=%d 2=%d 3-4=%d 5-8=%d 9-16=%d >16=%d]%n",
            plane, where, mismatches, expected.length, 100.0 * mismatches / expected.length,
            maxAbsDy, statusFlips, hist[0], hist[1], hist[2], hist[3], hist[4], hist[5], hist[6]
        );
        assertTrue(
            !requireDrift || mismatches > 0,
            plane + " " + where + ": zero drift - the seam did not serve float"
        );
        assertTrue(
            maxAbsDy <= 8,
            plane + " " + where + ": max |dY| " + maxAbsDy + " > 8 smoke bound"
        );
    }
}
