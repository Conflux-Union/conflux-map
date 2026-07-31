package cn.net.rms.confluxmap.core.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class MapExportSelectionTest {
    @Test
    void twoClicksProduceNormalizedInclusiveBounds() {
        final MapExportSelection selection = new MapExportSelection();

        assertTrue(selection.select(12, -3).isEmpty());
        assertTrue(selection.first().isPresent());
        assertEquals(
            MapExportBounds.between(-4, -3, 12, 20),
            selection.select(-4, 20).orElseThrow()
        );
        assertEquals(
            MapExportBounds.between(-4, -3, 12, 20),
            selection.bounds().orElseThrow()
        );
    }

    @Test
    void resetForgetsTheFirstCorner() {
        final MapExportSelection selection = new MapExportSelection();
        selection.select(1, 2);

        selection.reset();

        assertFalse(selection.first().isPresent());
        assertFalse(selection.bounds().isPresent());
    }
}
