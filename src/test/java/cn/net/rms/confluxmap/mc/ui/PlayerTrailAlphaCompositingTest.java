package cn.net.rms.confluxmap.mc.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
//#if MC>=12105
//$$ import cn.net.rms.confluxmap.mc.render.RenderUtil;
//$$ import com.mojang.blaze3d.pipeline.RenderPipeline;
//$$ import java.lang.reflect.Field;
//#endif
import org.junit.jupiter.api.Test;

final class PlayerTrailAlphaCompositingTest {

    @Test
    void trailRendererSelectsDestinationAlphaPreservation() throws IOException {
        final Path projectRoot = findProjectRoot();
        final String renderer = Files.readString(projectRoot.resolve(
            "src/main/java/cn/net/rms/confluxmap/mc/ui/PlayerTrailRenderer.java"
        ));
        final String renderUtil = Files.readString(projectRoot.resolve(
            "src/main/java/cn/net/rms/confluxmap/mc/render/RenderUtil.java"
        ));

        assertTrue(
            renderer.contains("RenderUtil.fillRectsPreservingDestinationAlpha(matrices, dots);"),
            "trail dots must not replace the off-screen minimap's destination alpha"
        );

        final String publicHelper = methodBody(
            renderUtil,
            "public static void fillRectsPreservingDestinationAlpha("
        );
        assertTrue(
            publicHelper.contains("fillRects(matrices, rects, true);"),
            "the trail helper must select destination-alpha preservation"
        );
    }

    //#if MC>=12105
    //$$ @Test
    //$$ void trailPipelineWritesFadingRgbButPreservesDestinationAlpha() throws Exception {
    //$$     final Field field = RenderUtil.class.getDeclaredField("GUI_PRESERVE_DESTINATION_ALPHA");
    //$$     field.setAccessible(true);
    //$$     final RenderPipeline pipeline = (RenderPipeline) field.get(null);
    //#if MC>=260100
    //$$     assertTrue(pipeline.getColorTargetState().writeRed());
    //$$     assertTrue(pipeline.getColorTargetState().writeGreen());
    //$$     assertTrue(pipeline.getColorTargetState().writeBlue());
    //$$     assertFalse(pipeline.getColorTargetState().writeAlpha());
    //#else
    //$$     assertTrue(pipeline.isWriteColor());
    //$$     assertFalse(pipeline.isWriteAlpha());
    //#endif
    //$$ }
    //#else
    @Test
    void legacyTrailBlendWritesFadingRgbButPreservesDestinationAlpha() throws IOException {
        final Path projectRoot = findProjectRoot();
        final String renderUtil = Files.readString(projectRoot.resolve(
            "src/main/java/cn/net/rms/confluxmap/mc/render/RenderUtil.java"
        ));
        final String fillMethod = methodBody(
            renderUtil,
            "private static void fillRects("
        );
        final String activeLegacySource = fillMethod.lines()
            .filter(line -> !line.trim().startsWith("//$$"))
            .reduce("", (left, right) -> left + right + '\n');
        final int preserveBlend = activeLegacySource.indexOf("RenderSystem.blendFuncSeparate(");
        final int sourceAlpha = activeLegacySource.indexOf("SrcFactor.ZERO", preserveBlend);
        final int destinationAlpha = activeLegacySource.indexOf("DstFactor.ONE", sourceAlpha);
        final int draw = activeLegacySource.indexOf("mesh.draw();", destinationAlpha);
        final int restore = activeLegacySource.indexOf("RenderSystem.defaultBlendFunc();", draw);

        assertTrue(
            preserveBlend >= 0 && sourceAlpha > preserveBlend && destinationAlpha > sourceAlpha,
            "the trail blend must keep the destination alpha instead of writing the fading dot alpha"
        );
        assertTrue(
            draw > destinationAlpha && restore > draw,
            "the destination-preserving blend must cover the dot draw and then restore normal blending"
        );
    }
    //#endif

    private static String methodBody(final String source, final String signature) {
        final int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing render helper " + signature);
        final int nextPublicMethod = source.indexOf("\n    public static ", start + signature.length());
        final int nextPrivateMethod = source.indexOf("\n    private static ", start + signature.length());
        final int nextMethod;
        if (nextPublicMethod < 0) {
            nextMethod = nextPrivateMethod;
        } else if (nextPrivateMethod < 0) {
            nextMethod = nextPublicMethod;
        } else {
            nextMethod = Math.min(nextPublicMethod, nextPrivateMethod);
        }
        return source.substring(start, nextMethod < 0 ? source.length() : nextMethod);
    }

    private static Path findProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("common.gradle"))
                && Files.isDirectory(current.resolve("src/main/java"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate the Conflux Map project root");
    }
}
