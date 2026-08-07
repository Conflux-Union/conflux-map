package cn.net.rms.confluxmap.mc.radar;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EntityIconManagerAtlasTest {
    private static final float EPSILON = 0.000001f;

    @Test
    void flipsTheCompleteFramebufferRowWhenSamplingTheAtlas() {
        assertEquals(1f, EntityIconManager.atlasTopV(0), EPSILON);
        assertEquals(31f / 32f, EntityIconManager.atlasBottomV(0), EPSILON);
        assertEquals(1f / 32f, EntityIconManager.atlasTopV(31), EPSILON);
        assertEquals(0f, EntityIconManager.atlasBottomV(31), EPSILON);
    }
}
