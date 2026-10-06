package cn.net.rms.confluxmap.nativepredict;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NativeLibTest {
    @Test
    void removeLegacyNativesUnderDeletesOnlyTheNativesTree(@TempDir final Path confluxRoot)
        throws IOException {
        final Path legacyLibrary = confluxRoot.resolve("natives/b01b760a0838/confluxnative.dll");
        Files.createDirectories(legacyLibrary.getParent());
        Files.write(legacyLibrary, new byte[] {1, 2, 3});
        final Path worldData = confluxRoot.resolve("webmap-hidden.txt");
        Files.write(worldData, new byte[] {4});

        NativeLib.removeLegacyNativesUnder(confluxRoot);

        assertFalse(Files.exists(confluxRoot.resolve("natives")), "legacy natives tree must be gone");
        assertTrue(Files.exists(worldData), "sibling world data must be preserved");
    }

    @Test
    void removeLegacyNativesUnderToleratesAMissingDirectory(@TempDir final Path runDir) {
        NativeLib.removeLegacyNativesUnder(runDir.resolve("confluxmap"));
    }
}
