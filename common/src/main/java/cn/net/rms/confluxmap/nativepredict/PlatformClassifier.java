package cn.net.rms.confluxmap.nativepredict;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Classifies the host against the exact set of native libraries bundled with Conflux Map. */
public final class PlatformClassifier {
    public static final List<String> OFFICIALLY_SUPPORTED_PLATFORMS = List.of(
        "Windows x86_64",
        "Linux x86_64 / AArch64",
        "macOS x86_64 / Apple Silicon"
    );

    private PlatformClassifier() {
    }

    public static Result current() {
        final String androidIndicators = System.getProperty("os.version", "")
            + " " + System.getProperty("java.runtime.name", "")
            + " " + System.getProperty("java.vm.name", "");
        return classify(
            System.getProperty("os.name", ""),
            androidIndicators,
            System.getProperty("os.arch", "")
        );
    }

    public static Result classify(
        final String osName,
        final String osVersion,
        final String osArch
    ) {
        final String normalizedName = normalize(osName);
        final String normalizedVersion = normalize(osVersion);
        final String normalizedArch = normalize(osArch);
        final String arch = canonicalArch(normalizedArch);
        final String displayArch = displayArch(arch, osArch);

        if (normalizedName.contains("android") || normalizedVersion.contains("android")) {
            return new Result("Android " + displayArch, Optional.empty());
        }
        if (normalizedName.contains("mac") || normalizedName.contains("darwin")) {
            return result("macOS", "macos", arch, displayArch, true);
        }
        if (normalizedName.contains("win")) {
            return result("Windows", "windows", arch, displayArch, "x86_64".equals(arch));
        }
        if (normalizedName.contains("linux")) {
            return result("Linux", "linux", arch, displayArch, isBundledArchitecture(arch));
        }

        final String displayOs = osName == null || osName.isBlank() ? "Unknown OS" : osName.trim();
        return new Result(displayOs + " " + displayArch, Optional.empty());
    }

    private static Result result(
        final String displayOs,
        final String targetOs,
        final String arch,
        final String displayArch,
        final boolean supported
    ) {
        return new Result(
            displayOs + " " + displayArch,
            supported && isBundledArchitecture(arch)
                ? Optional.of(targetOs + "-" + arch)
                : Optional.empty()
        );
    }

    private static boolean isBundledArchitecture(final String arch) {
        return "x86_64".equals(arch) || "aarch64".equals(arch);
    }

    private static String canonicalArch(final String arch) {
        if ("x86_64".equals(arch) || "amd64".equals(arch)) {
            return "x86_64";
        }
        if ("aarch64".equals(arch) || "arm64".equals(arch)) {
            return "aarch64";
        }
        return arch;
    }

    private static String displayArch(final String arch, final String rawArch) {
        if ("x86_64".equals(arch)) {
            return "x86_64";
        }
        if ("aarch64".equals(arch)) {
            return "AArch64";
        }
        return rawArch == null || rawArch.isBlank() ? "Unknown architecture" : rawArch.trim();
    }

    private static String normalize(final String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    public record Result(String displayName, Optional<String> nativeTarget) {
        public boolean officiallySupported() {
            return nativeTarget.isPresent();
        }
    }
}
