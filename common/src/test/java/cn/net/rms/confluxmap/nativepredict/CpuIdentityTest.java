package cn.net.rms.confluxmap.nativepredict;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pins the CPU-brand degradation chain: the native CPUID export first, then
 * /proc/cpuinfo, then the Windows environment variable, then null. Each stage's
 * absence (null or blank, or the native symbol throwing {@link
 * UnsatisfiedLinkError} on an older library) falls through to the next.
 */
class CpuIdentityTest {
    @Test
    void nativeBrandWinsWhenPresent() {
        assertEquals(
            Optional.of("AMD Ryzen 9 7950X"),
            Optional.ofNullable(CpuIdentity.cpuBrand(
                () -> "AMD Ryzen 9 7950X", () -> "proc model", () -> "env id"))
        );
    }

    @Test
    void blankNativeFallsThroughToProcCpuInfo() {
        assertEquals(
            Optional.of("proc model"),
            Optional.ofNullable(CpuIdentity.cpuBrand(
                () -> "  ", () -> "proc model", () -> "env id"))
        );
    }

    @Test
    void staleLibraryUnsatisfiedLinkFallsThrough() {
        assertEquals(
            Optional.of("env id"),
            Optional.ofNullable(CpuIdentity.cpuBrand(
                () -> {
                    throw new UnsatisfiedLinkError("stale library");
                },
                () -> null, () -> "env id"))
        );
    }

    @Test
    void allSourcesEmptyYieldsNull() {
        assertEquals(
            Optional.empty(),
            Optional.ofNullable(CpuIdentity.cpuBrand(() -> null, () -> null, () -> null))
        );
    }
}
