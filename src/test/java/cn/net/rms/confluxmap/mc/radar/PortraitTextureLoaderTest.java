package cn.net.rms.confluxmap.mc.radar;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import org.junit.jupiter.api.Test;

class PortraitTextureLoaderTest {
    @Test
    void decodesAndClosesSourceImageBeforePublishingBounds() {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        final FakeImage image = new FakeImage();
        final PortraitTextureLoader<String> loader = new PortraitTextureLoader<>(tasks::add);
        final float[] quad = {
            0f, 0f, 0f, 0f, 0f,
            32f, 0f, 0f, 1f, 0f,
            32f, 32f, 0f, 1f, 1f,
            0f, 32f, 0f, 0f, 1f
        };

        assertTrue(loader.request("portrait", quad, () -> image));
        assertFalse(loader.request("portrait", quad, () -> image));
        assertTrue(loader.poll().isEmpty());

        tasks.remove().run();

        assertTrue(image.closed);
        final PortraitTextureLoader.Result<String> result = loader.poll().orElseThrow();
        assertTrue(result.success());
        assertArrayEquals(new int[] {8, 4, 24, 28}, result.visibleBounds());
    }

    private static final class FakeImage implements PortraitTextureLoader.SourceImage {
        private boolean closed;

        @Override
        public int width() {
            return 32;
        }

        @Override
        public int height() {
            return 32;
        }

        @Override
        public int alphaAt(final int x, final int y) {
            return x >= 8 && x < 24 && y >= 4 && y < 28 ? 255 : 0;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
