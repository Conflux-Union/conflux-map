package cn.net.rms.confluxmap.mc.ui.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class WaypointItemHudRendererTest {
    @Test
    void publishesAnImmutableFrameSnapshot() {
        final WaypointItemHudRenderer renderer = new WaypointItemHudRenderer(null, null);
        final List<WaypointItemHudRenderer.Label> source = new ArrayList<>();
        source.add(label());

        renderer.publish(source);
        source.clear();

        assertEquals(1, renderer.snapshot().size());
        assertThrows(
            UnsupportedOperationException.class,
            () -> renderer.snapshot().add(label())
        );
    }

    @Test
    void publishingANewFrameReplacesTheOldOne() {
        final WaypointItemHudRenderer renderer = new WaypointItemHudRenderer(null, null);
        renderer.publish(List.of(label()));

        renderer.publish(List.of());

        assertEquals(List.of(), renderer.snapshot());
    }

    @Test
    void playerLabelsCarryThePortraitIdentity() {
        final UUID playerId = UUID.randomUUID();
        final WaypointItemHudRenderer.Label base = label();

        final WaypointItemHudRenderer.Label player = new WaypointItemHudRenderer.Label(
            base.waypoint(),
            base.distance3d(),
            base.projectionDistance(),
            base.animationProgress(),
            base.visibilityAlpha(),
            base.selected(),
            playerId
        );

        assertEquals(playerId, player.playerId());
    }

    @Test
    void plateRingStrictlyContainsTheInnerSquareAtEverySubPixelPosition() {
        final float[] unitScales = {0.3f, 0.501f, 0.75f, 1f, 1.4f, 2.7f};
        final float[] halfSizes = {9f, 4.2f, 0.75f};
        final float[] centers = {250f, 250.5f, 94.37f, -3.2f};
        for (final float unitScale : unitScales) {
            for (final float halfSize : halfSizes) {
                final WaypointItemHudRenderer.PlateFrame plate =
                    WaypointItemHudRenderer.plateFrame(halfSize, unitScale);
                assertTrue(plate.left() < plate.right(), "inner square must not degenerate");
                assertTrue(plate.top() < plate.bottom(), "inner square must not degenerate");
                assertTrue(plate.border() > 0f);
                assertTrue(plate.border() * unitScale >= 1f, "ring must be at least one GUI pixel");

                final float outerLeft = plate.left() - plate.border();
                final float outerTop = plate.top() - plate.border();
                final float outerRight = plate.right() + plate.border();
                final float outerBottom = plate.bottom() + plate.border();
                final WaypointItemHudRenderer.Quad[] quads = WaypointItemHudRenderer.ringQuads(plate);
                for (final WaypointItemHudRenderer.Quad quad : quads) {
                    assertTrue(quad.x0() < quad.x1() && quad.y0() < quad.y1());
                    assertTrue(quad.x0() >= outerLeft && quad.y0() >= outerTop);
                    assertTrue(quad.x1() <= outerRight && quad.y1() <= outerBottom);
                    // No ring quad may reach into the open inner square.
                    assertTrue(
                        quad.x1() <= plate.left() || quad.x0() >= plate.right()
                            || quad.y1() <= plate.top() || quad.y0() >= plate.bottom()
                    );
                }

                // The four quads tile the whole band: every band point covered, every interior
                // point untouched.
                final int steps = 48;
                final float stepX = (outerRight - outerLeft) / steps;
                final float stepY = (outerBottom - outerTop) / steps;
                // Skip the outer boundary: grid arithmetic can land a sample one ulp outside.
                for (int iy = 1; iy < steps; iy++) {
                    for (int ix = 1; ix < steps; ix++) {
                        final float x = outerLeft + ix * stepX;
                        final float y = outerTop + iy * stepY;
                        final boolean insideInner =
                            x > plate.left() && x < plate.right()
                                && y > plate.top() && y < plate.bottom();
                        boolean covered = false;
                        for (final WaypointItemHudRenderer.Quad quad : quads) {
                            if (x >= quad.x0() && x <= quad.x1()
                                && y >= quad.y0() && y <= quad.y1()) {
                                covered = true;
                                break;
                            }
                        }
                        if (insideInner) {
                            assertFalse(covered, "ring covers the inner square at " + x + "," + y);
                        } else {
                            assertTrue(covered, "band gap at " + x + "," + y);
                        }
                    }
                }

                // Strict containment must survive the label transform at fractional centers.
                for (final float centerX : centers) {
                    for (final float centerY : centers) {
                        assertTrue(centerX + outerLeft * unitScale < centerX + plate.left() * unitScale);
                        assertTrue(centerY + outerTop * unitScale < centerY + plate.top() * unitScale);
                        assertTrue(
                            centerX + plate.right() * unitScale < centerX + outerRight * unitScale
                        );
                        assertTrue(
                            centerY + plate.bottom() * unitScale < centerY + outerBottom * unitScale
                        );
                    }
                }
            }
        }
    }

    private static WaypointItemHudRenderer.Label label() {
        return new WaypointItemHudRenderer.Label(
            new WaypointRenderEntry(
                UUID.randomUUID(), "diamond", DimensionId.OVERWORLD,
                0.0, 64.0, 0.0, 0xFFFFFFFF,
                "minecraft:diamond", "", Waypoint.Type.NORMAL,
                WaypointRenderEntry.Source.LOCAL, false
            ),
            10.0,
            100.0,
            0f,
            1f,
            false
        );
    }

}
