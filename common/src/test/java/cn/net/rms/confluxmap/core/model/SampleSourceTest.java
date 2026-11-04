package cn.net.rms.confluxmap.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Merge-order contract for {@link SampleSource}, including the persisted-ordinal rules. */
class SampleSourceTest {

    @Test
    void priorityOrdersAllSources() {
        final SampleSource[] order = {
            SampleSource.UNKNOWN, SampleSource.PREDICTED, SampleSource.IMPORTED,
            SampleSource.REAL_SCAN, SampleSource.REAL_CACHED, SampleSource.REAL_LIVE
        };
        for (int i = 1; i < order.length; i++) {
            assertTrue(order[i].priority() > order[i - 1].priority(),
                order[i] + " must outrank " + order[i - 1]);
        }
    }

    @Test
    void realScanOverridesImportsButNotCapturedSources() {
        assertTrue(SampleSource.REAL_SCAN.overrides(SampleSource.UNKNOWN));
        assertTrue(SampleSource.REAL_SCAN.overrides(SampleSource.PREDICTED));
        assertTrue(SampleSource.REAL_SCAN.overrides(SampleSource.IMPORTED));
        assertTrue(SampleSource.REAL_SCAN.overrides(SampleSource.REAL_SCAN));
        assertFalse(SampleSource.REAL_SCAN.overrides(SampleSource.REAL_CACHED));
        assertFalse(SampleSource.REAL_SCAN.overrides(SampleSource.REAL_LIVE));
        assertFalse(SampleSource.IMPORTED.overrides(SampleSource.REAL_SCAN));
        assertTrue(SampleSource.REAL_CACHED.overrides(SampleSource.REAL_SCAN));
        assertTrue(SampleSource.REAL_LIVE.overrides(SampleSource.REAL_SCAN));
    }

    @Test
    void realScanCountsAsExplored() {
        assertFalse(SampleSource.UNKNOWN.isExplored());
        assertFalse(SampleSource.PREDICTED.isExplored());
        assertTrue(SampleSource.IMPORTED.isExplored());
        assertTrue(SampleSource.REAL_SCAN.isExplored());
        assertTrue(SampleSource.REAL_CACHED.isExplored());
        assertTrue(SampleSource.REAL_LIVE.isExplored());
    }

    @Test
    void realScanIsAppendedAsOrdinalFive() {
        // .cfr chunk tables persist ordinals: REAL_CACHED=3 and REAL_LIVE=4 are on disk
        // from older writes, so REAL_SCAN must stay the appended value.
        assertEquals(3, SampleSource.REAL_CACHED.ordinal());
        assertEquals(4, SampleSource.REAL_LIVE.ordinal());
        assertEquals(5, SampleSource.REAL_SCAN.ordinal());
        assertEquals(SampleSource.REAL_SCAN, SampleSource.byOrdinal(5));
    }

    @Test
    void byOrdinalFallsBackToUnknownOutsideTheTable() {
        assertEquals(SampleSource.UNKNOWN, SampleSource.byOrdinal(6));
        assertEquals(SampleSource.UNKNOWN, SampleSource.byOrdinal(-1));
    }
}
