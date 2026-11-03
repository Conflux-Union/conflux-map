package cn.net.rms.confluxmap.mc.ui.anim;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Pins the source shape that keeps GuiDraw's context pose balanced on 1.21.8+, where GuiDraw
 * owns its own MatrixStack while the vanilla DrawContext owns a JOML Matrix3x2fStack. An
 * unbalanced fold/pop consumed one context-pose level per push/pop pair until JOML threw at
 * the bottom of the stack, and the per-frame waypoint-label caller turned that into a crash,
 * so the shape is pinned here. The code under test is preprocessor-gated and compiles only
 * per version, which is why the raw source structure is asserted instead.
 */
final class GuiDrawPoseBalanceTest {
    @Test
    void pushTransformPushesTheContextPoseBeforeFolding() throws IOException {
        final String source = read("src/main/java/cn/net/rms/confluxmap/mc/ui/GuiDraw.java");
        final String push = between(source, "public void pushTransform", "public void translate");

        assertTrue(
            push.contains("context.getMatrices().pushMatrix();"),
            "pushTransform must push a context pose level for popTransform to pop"
        );
        assertTrue(
            push.indexOf("context.getMatrices().pushMatrix();")
                < push.indexOf("foldContextPose();"),
            "the context pose push must precede the fold so the fold rewrites the pushed level"
        );
        assertTrue(
            push.contains("foldContextPose();"),
            "pushTransform must fold the pose so context-routed draws follow the transform"
        );
    }

    @Test
    void midScopeMutationsReachContextRoutedDraws() throws IOException {
        final String source = read("src/main/java/cn/net/rms/confluxmap/mc/ui/GuiDraw.java");
        final String translate = between(
            source,
            "public void translate(final",
            "public void scale"
        );
        final String scale = between(
            source,
            "public void scale(final",
            "public void popTransform"
        );

        assertTrue(
            translate.contains("foldContextPose();"),
            "translate must re-fold the pose so mid-scope translations reach context-routed draws"
        );
        assertTrue(
            scale.contains("foldContextPose();"),
            "scale must re-fold the pose so mid-scope scales reach context-routed draws"
        );
    }

    @Test
    void popTransformPopsThePushedContextPoseLevel() throws IOException {
        final String source = read("src/main/java/cn/net/rms/confluxmap/mc/ui/GuiDraw.java");
        final String pop = between(
            source,
            "public void popTransform",
            "public void beginScreenTransform"
        );

        assertTrue(
            pop.contains("popMatrix();"),
            "popTransform must pop the pushed level, or every push/pop pair drains the JOML stack"
        );
    }

    @Test
    void revealFrameFoldsTheContextPoseAroundTheScreenTransform() throws IOException {
        final String source = read(
            "src/main/java/cn/net/rms/confluxmap/mc/ui/screen/ConfluxScreen.java"
        );
        final String begin = between(
            source,
            "private boolean beginRevealFrame",
            "private void endRevealFrame"
        );
        // holdingClosedPose: the section end must anchor on a member every revision keeps.
        final String end = between(
            source,
            "private void endRevealFrame",
            "private boolean holdingClosedPose"
        );

        assertTrue(
            begin.indexOf("applyToContextPose()") < begin.indexOf("beginScreenTransform("),
            "the pose fold must push its level before the screen transform re-folds onto it"
        );
        assertTrue(
            end.indexOf("restoreContextPose()") < end.indexOf("endScreenTransform()"),
            "the context pose level must be popped before the screen transform's stack level is"
        );
    }

    private static String between(final String source, final String start, final String end) {
        final int startIndex = source.indexOf(start);
        final int endIndex = source.indexOf(end, startIndex);
        assertTrue(startIndex >= 0 && endIndex > startIndex, "expected source section must exist");
        return source.substring(startIndex, endIndex);
    }

    private static String read(final String path) throws IOException {
        return Files.readString(projectRoot().resolve(path)).replace("\r\n", "\n");
    }

    private static Path projectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("common.gradle"))
                && Files.isDirectory(current.resolve("src/main/java"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("project root not found");
    }
}
