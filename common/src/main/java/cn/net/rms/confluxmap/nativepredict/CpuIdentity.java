package cn.net.rms.confluxmap.nativepredict;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * CPU identity for the AUTO benchmark's hardware-keyed cache, as a degradation chain:
 * the native CPUID brand export first (exact part name, every x86 target), then
 * {@code /proc/cpuinfo}'s model line (Linux without the export), then the Windows
 * {@code PROCESSOR_IDENTIFIER} environment variable, then null - a null brand keys the
 * cache on the logical processor count alone, a weaker identity that still re-runs the
 * benchmark when the machine changes core count. Never throws: an older library raises
 * {@link UnsatisfiedLinkError}, which this chain treats as "no data from that source".
 */
public final class CpuIdentity {
    private CpuIdentity() {
    }

    /** Brand of the installed CPU, or null when no source reports one. */
    public static String cpuBrand() {
        return cpuBrand(
            CpuIdentity::nativeCpuBrand,
            CpuIdentity::procCpuInfoModel,
            () -> System.getenv("PROCESSOR_IDENTIFIER")
        );
    }

    /** Logical processors visible to this JVM; pairs with the brand in the cache key. */
    public static int logicalProcessors() {
        return Runtime.getRuntime().availableProcessors();
    }

    /**
     * Pure chain over injectable sources (tests drive the fall-through): first non-blank
     * answer wins, a source that throws {@link UnsatisfiedLinkError} counts as absent.
     */
    static String cpuBrand(
        final Supplier<String> nativeBrand,
        final Supplier<String> procModel,
        final Supplier<String> envIdentifier
    ) {
        String brand = readSource(nativeBrand);
        if (brand == null) {
            brand = readSource(procModel);
        }
        if (brand == null) {
            brand = readSource(envIdentifier);
        }
        return brand;
    }

    private static String readSource(final Supplier<String> source) {
        final String value;
        try {
            value = source.get();
        } catch (final UnsatisfiedLinkError staleLibrary) {
            return null;
        }
        if (value == null) {
            return null;
        }
        final String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String nativeCpuBrand() {
        return CubiomesNative.cfxCpuBrand();
    }

    /** First {@code model name} line of {@code /proc/cpuinfo}, or null off-Linux/unreadable. */
    private static String procCpuInfoModel() {
        try {
            for (final String line : Files.readAllLines(Path.of("/proc/cpuinfo"), StandardCharsets.UTF_8)) {
                if (line.startsWith("model name")) {
                    final int separator = line.indexOf(':');
                    return separator < 0 ? null : line.substring(separator + 1).strip();
                }
            }
        } catch (final IOException unreadable) {
            // No /proc on this platform or it is unreadable: the next chain link answers.
        }
        return null;
    }
}
