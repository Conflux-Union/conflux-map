package cn.net.rms.confluxmap.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HudTransformTest {
    @Test
    void rebasingCancelsAnExternalTransformWrappedAroundThisModsPush() {
        final HudTransform wanted = new HudTransform(150f, 98f, 0.5f);
        final HudAmbient ambient = new HudAmbient(320f, 10f, 0.5f, 0.5f);

        final HudTransform pushed = wanted.rebased(ambient);

        assertEquals(new HudTransform(-20f, 186f, 0.5f), pushed);
        // Pushing inside the ambient transform must land where applying `wanted` on top would.
        assertEquals(
            wanted.scale() * ambient.applyX(48f) + wanted.translateX(),
            ambient.applyX(pushed.scale() * 48f + pushed.translateX()),
            1e-3f
        );
        assertEquals(
            wanted.scale() * ambient.applyY(64f) + wanted.translateY(),
            ambient.applyY(pushed.scale() * 64f + pushed.translateY()),
            1e-3f
        );
    }

    @Test
    void rebasingAPureShiftDividesOutTheExternalScale() {
        final HudAmbient halved = new HudAmbient(0f, 0f, 0.5f, 0.5f);

        assertEquals(-216f, HudTransform.ofHorizontalShift(-108f).rebased(halved).translateX(), 1e-3f);
        assertEquals(216f, HudTransform.ofVerticalShift(108f).rebased(halved).translateY(), 1e-3f);
    }

    @Test
    void rebasingIsANoOpWithoutAnExternalTransform() {
        final HudTransform wanted = new HudTransform(0f, 88f, 1f);

        assertEquals(wanted, wanted.rebased(HudAmbient.IDENTITY));
        assertEquals(wanted, wanted.rebased(null));
    }

    @Test
    void aZeroShiftIsTheIdentity() {
        assertEquals(HudTransform.IDENTITY, HudTransform.ofVerticalShift(0f));
        assertEquals(HudTransform.IDENTITY, HudTransform.ofHorizontalShift(0f));
    }
}
