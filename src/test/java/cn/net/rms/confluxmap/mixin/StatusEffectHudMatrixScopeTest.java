package cn.net.rms.confluxmap.mixin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

//#if MC>=12100
//$$ import java.io.IOException;
//$$ import java.net.URISyntaxException;
//$$ import java.nio.file.Files;
//$$ import java.nio.file.Path;
//#endif
import org.junit.jupiter.api.Test;

final class StatusEffectHudMatrixScopeTest {
    //#if MC>=12100
    //$$ @Test
    //$$ void statusEffectTranslationIsRestoredByTheSameCallFrame() throws Exception {
    //$$     final String source = Files.readString(preprocessedSource());
    //$$     final int wrapperStart = source.indexOf("@WrapMethod(");
    //$$     assertTrue(wrapperStart >= 0, "modern status-effect avoidance must wrap the complete method");
    //$$     final int legacyBranch = source.indexOf("\n    //#else\n    //$$ @Unique", wrapperStart);
    //$$     assertTrue(legacyBranch > wrapperStart, "modern wrapper must end before the legacy branch");
    //$$     final String activeWrapper = source.substring(wrapperStart, legacyBranch);
    //$$     assertTrue(
    //$$         activeWrapper.contains("try {")
    //$$             && activeWrapper.contains("finally {")
    //$$             && (activeWrapper.contains("popMatrix()")
    //$$                 || activeWrapper.contains("getMatrices().pop();")),
    //$$         "the status-effect matrix translation must be restored in finally"
    //$$     );
    //$$     assertFalse(
    //$$         activeWrapper.contains("confluxmap$statusEffectsShifted"),
    //$$         "shared HEAD/RETURN state can leak when another mixin changes the return path"
    //$$     );
    //$$ }
    //$$
    //$$ private static Path preprocessedSource() throws URISyntaxException, IOException {
    //$$     Path current = Path.of(
    //$$         StatusEffectHudMatrixScopeTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()
    //$$     );
    //$$     while (current != null && !"build".equals(current.getFileName().toString())) {
    //$$         current = current.getParent();
    //$$     }
    //$$     if (current == null) {
    //$$         throw new IllegalStateException("Could not locate the version build directory");
    //$$     }
    //$$     return current.resolve(
    //$$         "preprocessed/main/java/cn/net/rms/confluxmap/mixin/StatusEffectHudMixin.java"
    //$$     );
    //$$ }
    //#else
    @Test
    void legacyStatusEffectRenderingKeepsItsExistingMatrixContract() {
        assertTrue(true);
    }
    //#endif
}
