package cn.net.rms.confluxmap.nativepredict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

final class PlatformClassifierTest {
    @ParameterizedTest
    @MethodSource("supportedAliases")
    void supportedOsAndArchitectureAliasesResolveToBundledTargets(
        final String osName,
        final String osArch,
        final String expectedTarget
    ) {
        final PlatformClassifier.Result result = PlatformClassifier.classify(osName, "", osArch);

        assertTrue(result.officiallySupported());
        assertEquals(expectedTarget, result.nativeTarget().orElseThrow());
    }

    private static Stream<Arguments> supportedAliases() {
        return Stream.of(
            Arguments.of("Windows 10", "amd64", "windows-x86_64"),
            Arguments.of("Windows 11", "x86_64", "windows-x86_64"),
            Arguments.of("Linux", "amd64", "linux-x86_64"),
            Arguments.of("GNU/Linux", "x86_64", "linux-x86_64"),
            Arguments.of("Linux", "aarch64", "linux-aarch64"),
            Arguments.of("Linux", "arm64", "linux-aarch64"),
            Arguments.of("Mac OS X", "x86_64", "macos-x86_64"),
            Arguments.of("Darwin", "amd64", "macos-x86_64"),
            Arguments.of("Mac OS X", "aarch64", "macos-aarch64"),
            Arguments.of("Darwin", "arm64", "macos-aarch64")
        );
    }

    @Test
    void androidVersionTakesPriorityOverLinuxNameReportedByFcl() {
        final PlatformClassifier.Result result =
            PlatformClassifier.classify("Linux", "Android-16", "aarch64");

        assertEquals("Android AArch64", result.displayName());
        assertFalse(result.officiallySupported());
        assertTrue(result.nativeTarget().isEmpty());
    }

    @Test
    void unknownArchitectureRemainsVisibleAndUnsupported() {
        final PlatformClassifier.Result result =
            PlatformClassifier.classify("Linux", "6.18", "riscv64");

        assertEquals("Linux riscv64", result.displayName());
        assertFalse(result.officiallySupported());
        assertTrue(result.nativeTarget().isEmpty());
    }

    @Test
    void windowsArm64IsNotInTheBundledTargetSet() {
        final PlatformClassifier.Result result =
            PlatformClassifier.classify("Windows 11", "", "arm64");

        assertEquals("Windows AArch64", result.displayName());
        assertFalse(result.officiallySupported());
        assertTrue(result.nativeTarget().isEmpty());
    }

    @Test
    void warningPolicyHonorsDismissalAndProcessOnceLimit() {
        final UnsupportedPlatformWarningPolicy dismissedPolicy =
            new UnsupportedPlatformWarningPolicy(false);
        assertFalse(dismissedPolicy.shouldShow(true));

        final UnsupportedPlatformWarningPolicy processPolicy =
            new UnsupportedPlatformWarningPolicy(false);
        assertTrue(processPolicy.shouldShow(false));
        processPolicy.markShown();
        assertFalse(processPolicy.shouldShow(false));

        final UnsupportedPlatformWarningPolicy supportedPolicy =
            new UnsupportedPlatformWarningPolicy(true);
        assertFalse(supportedPolicy.shouldShow(false));
    }

    @Test
    void androidPreviewOverrideForcesOnlyTheWarningPlatform() {
        final PlatformClassifier.Result actual =
            PlatformClassifier.classify("Linux", "", "x86_64");

        final PlatformWarningEnvironment.Selection selection =
            PlatformWarningEnvironment.select(actual, "android-aarch64");

        assertTrue(selection.preview());
        assertEquals("Android AArch64", selection.platform().displayName());
        assertFalse(selection.platform().officiallySupported());
        assertEquals("linux-x86_64", actual.nativeTarget().orElseThrow());
    }

    @Test
    void unknownPreviewOverrideFallsBackToTheActualPlatform() {
        final PlatformClassifier.Result actual =
            PlatformClassifier.classify("Linux", "", "x86_64");

        final PlatformWarningEnvironment.Selection selection =
            PlatformWarningEnvironment.select(actual, "typo");

        assertFalse(selection.preview());
        assertEquals(actual, selection.platform());
    }
}
