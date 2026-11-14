package cn.net.rms.confluxmap.compat;

import net.minecraft.client.MinecraftClient;

/**
 * Grabs the current framebuffer content as ARGB pixels. On 1.21.4 and older
 * the read is synchronous on the caller thread; from 1.21.5 the game's GPU
 * copy pipeline delivers the image through a callback on the render thread.
 * Callers must never block the render thread waiting for the consumer.
 */
public final class Screenshots {
    @FunctionalInterface
    public interface FrameConsumer {
        /** Width, height, and tightly packed ARGB pixels of the grabbed frame. */
        void accept(int width, int height, int[] argb);
    }

    private Screenshots() {
    }

    public static void grabFrame(final MinecraftClient client, final FrameConsumer consumer) {
        //#if MC>=260200
        //$$ net.minecraft.client.Screenshot.takeScreenshot(
        //$$     client.gameRenderer.mainRenderTarget(),
        //$$     image -> deliver(image, consumer)
        //$$ );
        //#elseif MC>=260100
        //$$ net.minecraft.client.Screenshot.takeScreenshot(
        //$$     client.getMainRenderTarget(),
        //$$     image -> deliver(image, consumer)
        //$$ );
        //#elseif MC>=12105
        //$$ net.minecraft.client.util.ScreenshotRecorder.takeScreenshot(
        //$$     client.getFramebuffer(),
        //$$     image -> deliver(image, consumer)
        //$$ );
        //#else
        deliver(
            net.minecraft.client.util.ScreenshotRecorder.takeScreenshot(client.getFramebuffer()),
            consumer
        );
        //#endif
    }

    private static void deliver(
        final net.minecraft.client.texture.NativeImage image,
        final FrameConsumer consumer
    ) {
        if (image == null) {
            return;
        }
        final int width = image.getWidth();
        final int height = image.getHeight();
        final int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                argb[y * width + x] = NativeImages.getArgb(image, x, y);
            }
        }
        image.close();
        consumer.accept(width, height, argb);
    }
}
