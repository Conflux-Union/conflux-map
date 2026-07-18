package cn.net.rms.confluxmap.nativepredict;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Detects the current platform, extracts the bundled cubiomes+shim native library into a
 * caller-provided directory, and loads it. Everything here degrades gracefully: any failure
 * (unsupported platform, missing bundled resource, extraction IO error, link failure, ABI
 * mismatch) just leaves {@link #available()} false rather than throwing - nothing downstream
 * may assume the native predictor exists.
 *
 * <p>{@link #init(Path)} is meant to be called exactly once per game session, by wiring added in
 * a later slice; nothing calls it yet in this slice. A genuine failure latches permanently (a
 * later {@link #init} short-circuits to {@code false} without retrying), so a broken native
 * build doesn't re-attempt extraction/linking on every world join. A successful load stays fully
 * re-callable though: {@link #init} always re-verifies the extracted file's hash and re-extracts
 * it if it went missing or got corrupted on disk since the last call.
 */
public final class NativeLib {
    private static final Logger LOGGER = LogManager.getLogger("ConfluxMap-Native");
    private static final String NATIVE_LIB_PROPERTY = "confluxmap.nativeLib";
    private static final String RESOURCE_ROOT = "natives";

    private static volatile boolean available;
    private static volatile boolean permanentlyFailed;

    private NativeLib() {
    }

    /** Whether the native predictor is loaded and ready to use. */
    public static boolean available() {
        return available;
    }

    /**
     * Extracts (if needed) and loads the native library under {@code baseDir}, unless {@code
     * -Dconfluxmap.nativeLib=<path>} is set, in which case that exact path is loaded instead of
     * anything bundled. Returns the new value of {@link #available()}.
     */
    public static synchronized boolean init(final Path baseDir) {
        if (permanentlyFailed) {
            return false;
        }
        try {
            final String override = System.getProperty(NATIVE_LIB_PROPERTY);
            final Path libraryPath = override != null ? Path.of(override) : extract(baseDir);
            System.load(libraryPath.toAbsolutePath().toString());

            final int abi = CubiomesNative.cfxAbi();
            if (abi != PredictorVersion.CFX_ABI) {
                LOGGER.error(
                    "native: library ABI {} does not match expected {}, disabling prediction permanently",
                    abi, PredictorVersion.CFX_ABI
                );
                permanentlyFailed = true;
                available = false;
                return false;
            }
            available = true;
            return true;
        } catch (final IOException | UnsatisfiedLinkError | SecurityException e) {
            LOGGER.warn("native: could not load prediction library, prediction disabled ({})", e.toString());
            permanentlyFailed = true;
            available = false;
            return false;
        }
    }

    /**
     * Test-only entry point: extracts under {@code java.io.tmpdir} (or loads the {@code
     * -Dconfluxmap.nativeLib} override directly) instead of a real per-world cache directory.
     */
    public static boolean initForTests() {
        return init(Path.of(System.getProperty("java.io.tmpdir"), "confluxmap-native-test"));
    }

    private static Path extract(final Path baseDir) throws IOException {
        final String target = detectTarget().orElseThrow(() -> new IOException(
            "unsupported platform: os.name=" + System.getProperty("os.name") + " os.arch=" + System.getProperty("os.arch")
        ));
        final String libName = libFileName(target);
        final byte[] resourceBytes = readResource(RESOURCE_ROOT + "/" + target + "/" + libName);
        final Path libFile = libraryPath(baseDir, libName, resourceBytes);

        if (Files.exists(libFile) && sha256Hex(Files.readAllBytes(libFile)).equals(sha256Hex(resourceBytes))) {
            return libFile;
        }
        Files.createDirectories(libFile.getParent());
        final Path tmp = libFile.resolveSibling(libFile.getFileName() + ".tmp");
        Files.write(tmp, resourceBytes);
        move(tmp, libFile);
        return libFile;
    }

    private static Path libraryPath(final Path baseDir, final String libName, final byte[] resourceBytes) {
        final String hash = sha256Hex(resourceBytes);
        return baseDir.resolve(RESOURCE_ROOT).resolve(hash.substring(0, 12)).resolve(libName);
    }

    /**
     * Test-only seam: resolves the deterministic path {@link #init} would extract {@code
     * baseDir}'s library to, without writing or loading anything. Exists so tests can pre-seed a
     * corrupt file at the right path <em>before</em> anything ever loads it - corrupting the
     * backing file of an already-{@code System.load}ed library out from under the running JVM
     * reliably SIGBUSes it (the OS still has the old mapping paged in), so a meaningful
     * corruption-recovery test has to use a base directory nothing has loaded from yet.
     */
    static Path resolveExtractedPathForTests(final Path baseDir) throws IOException {
        final String target = detectTarget().orElseThrow(() -> new IOException(
            "unsupported platform: os.name=" + System.getProperty("os.name") + " os.arch=" + System.getProperty("os.arch")
        ));
        final String libName = libFileName(target);
        final byte[] resourceBytes = readResource(RESOURCE_ROOT + "/" + target + "/" + libName);
        return libraryPath(baseDir, libName, resourceBytes);
    }

    private static byte[] readResource(final String resourcePath) throws IOException {
        try (InputStream in = NativeLib.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("no bundled native library at " + resourcePath);
            }
            return in.readAllBytes();
        }
    }

    private static void move(final Path tmp, final Path file) throws IOException {
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Manual hex encode - {@code java.util.HexFormat} is JDK 17+, this mod compiles at Java 16. */
    private static String sha256Hex(final byte[] bytes) {
        final byte[] hash;
        try {
            hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        final char[] digits = "0123456789abcdef".toCharArray();
        final char[] hex = new char[hash.length * 2];
        for (int i = 0; i < hash.length; i++) {
            final int v = hash[i] & 0xFF;
            hex[i * 2] = digits[v >>> 4];
            hex[i * 2 + 1] = digits[v & 0x0F];
        }
        return new String(hex);
    }

    private static Optional<String> detectTarget() {
        final String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        final String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        final String arch;
        if (osArch.equals("x86_64") || osArch.equals("amd64")) {
            arch = "x86_64";
        } else if (osArch.equals("aarch64") || osArch.equals("arm64")) {
            arch = "aarch64";
        } else {
            return Optional.empty();
        }
        if (osName.contains("win")) {
            return "x86_64".equals(arch) ? Optional.of("windows-x86_64") : Optional.empty();
        }
        if (osName.contains("mac") || osName.contains("darwin")) {
            return Optional.of("macos-" + arch);
        }
        if (osName.contains("linux")) {
            return Optional.of("linux-" + arch);
        }
        return Optional.empty();
    }

    private static String libFileName(final String target) {
        if (target.startsWith("windows")) {
            return "confluxnative.dll";
        }
        if (target.startsWith("macos")) {
            return "libconfluxnative.dylib";
        }
        return "libconfluxnative.so";
    }
}
