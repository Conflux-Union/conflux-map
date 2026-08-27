package cn.net.rms.confluxmap.mc.radar;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class EntityIconManagerAtlasTest {
    private static final float EPSILON = 0.000001f;
    private static final IntBinaryOperator OPAQUE = (x, y) -> 255;

    @Test
    void flipsTheCompleteFramebufferRowWhenSamplingTheAtlas() {
        assertEquals(1f, EntityIconManager.atlasTopV(0), EPSILON);
        assertEquals(31f / 32f, EntityIconManager.atlasBottomV(0), EPSILON);
        assertEquals(1f / 32f, EntityIconManager.atlasTopV(31), EPSILON);
        assertEquals(0f, EntityIconManager.atlasBottomV(31), EPSILON);
    }

    @Test
    void cropsProjectedQuadsToVisibleTextureAlpha() {
        final float[] quad = {
            0f, 0f, 0f, 0f, 0f,
            32f, 0f, 0f, 1f, 0f,
            32f, 32f, 0f, 1f, 1f,
            0f, 32f, 0f, 0f, 1f
        };

        assertArrayEquals(
            new int[] {8, 4, 24, 28},
            EntityIconManager.visibleBounds(
                quad, 32, 32,
                (x, y) -> x >= 8 && x < 24 && y >= 4 && y < 28 ? 255 : 0
            )
        );
    }

    @Test
    void countsTheVisibleSilhouetteAreaOncePerAtlasPixel() {
        final float[] overlappingQuads = {
            0f, 0f, 0f, 0f, 0f,
            32f, 0f, 0f, 1f, 0f,
            32f, 32f, 0f, 1f, 1f,
            0f, 32f, 0f, 0f, 1f,
            0f, 0f, 0f, 0f, 0f,
            32f, 0f, 0f, 1f, 0f,
            32f, 32f, 0f, 1f, 1f,
            0f, 32f, 0f, 0f, 1f
        };

        final EntityIconManager.VisiblePixels visible = EntityIconManager.visiblePixels(
            overlappingQuads, 32, 32,
            (x, y) -> x >= 8 && x < 24 && y >= 4 && y < 28 ? 255 : 0
        );

        assertArrayEquals(new int[] {8, 4, 24, 28}, visible.bounds());
        assertEquals(16 * 24, visible.area());
    }

    @Test
    void scalesTheVisibleSilhouetteToAFullCellAreaWithoutChangingAspectRatio() {
        final EntityIconManager.DisplayScale horse = EntityIconManager.displayScale(
            new int[] {0, 1, 32, 31}, 453
        );
        final EntityIconManager.DisplayScale square = EntityIconManager.displayScale(
            new int[] {0, 0, 32, 32}, 1024
        );
        final EntityIconManager.DisplayScale croppedSquare = EntityIconManager.displayScale(
            new int[] {8, 8, 24, 24}, 256
        );

        assertEquals(32f / 30f, horse.width() / horse.height(), EPSILON);
        assertEquals(
            1f, 453f * horse.width() * horse.height() / (32f * 30f), EPSILON
        );
        assertEquals(new EntityIconManager.DisplayScale(1f, 1f), square);
        assertEquals(new EntityIconManager.DisplayScale(1f, 1f), croppedSquare);
    }

    @Test
    void visibleBoundsDoesNotAllocatePerCoveredPixel() {
        final float[] quad = {
            0f, 0f, 0f, 0f, 0f,
            32f, 0f, 0f, 1f, 0f,
            32f, 32f, 0f, 1f, 1f,
            0f, 32f, 0f, 0f, 1f
        };
        final java.lang.management.ThreadMXBean platformMemory =
            ManagementFactory.getThreadMXBean();
        Assumptions.assumeTrue(platformMemory instanceof ThreadMXBean);
        final ThreadMXBean memory = (ThreadMXBean) platformMemory;
        Assumptions.assumeTrue(memory.isThreadAllocatedMemorySupported());
        memory.setThreadAllocatedMemoryEnabled(true);
        for (int i = 0; i < 100; i++) {
            EntityIconManager.visibleBounds(quad, 32, 32, OPAQUE);
        }

        final long thread = Thread.currentThread().getId();
        final long before = memory.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 100; i++) {
            EntityIconManager.visibleBounds(quad, 32, 32, OPAQUE);
        }
        final long allocated = memory.getThreadAllocatedBytes(thread) - before;

        assertTrue(allocated < 16_000L, "visible-bounds scan allocated " + allocated + " bytes");
    }
}
