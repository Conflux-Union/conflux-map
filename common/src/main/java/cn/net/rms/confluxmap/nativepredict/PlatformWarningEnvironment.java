package cn.net.rms.confluxmap.nativepredict;

/**
 * Selects the platform shown by the startup warning without changing native-library loading.
 */
public final class PlatformWarningEnvironment {
    public static final String PREVIEW_PROPERTY = "confluxmap.testPlatform";
    private static final String ANDROID_AARCH64_PREVIEW = "android-aarch64";

    private PlatformWarningEnvironment() {
    }

    public static Selection current() {
        return select(
            PlatformClassifier.current(),
            System.getProperty(PREVIEW_PROPERTY, "")
        );
    }

    static Selection select(
        final PlatformClassifier.Result actual,
        final String previewOverride
    ) {
        if (ANDROID_AARCH64_PREVIEW.equalsIgnoreCase(previewOverride.trim())) {
            return new Selection(
                PlatformClassifier.classify("Linux", "Android", "aarch64"),
                true
            );
        }
        return new Selection(actual, false);
    }

    public record Selection(PlatformClassifier.Result platform, boolean preview) {
    }
}
