package cn.net.rms.confluxmap.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ConfluxMapCompanionLifecycleTest {
    @Test
    void chunkLoadStatesCollectBeforeRuntimeActivation() throws IOException {
        final String source = Files.readString(
            findProjectRoot().resolve(
                "src/main/java/cn/net/rms/confluxmap/server/ConfluxMapCompanion.java"
            )
        );
        final String starting = between(source, "private void onServerStarting(", "private void onServerStarted(");
        final String activation = between(source, "private void activateIfNeeded(", "\n    }\n}");

        assertTrue(
            starting.contains("chunkLoadStates = config.enabled && config.shareChunkLoadState"),
            "chunk load states must start collecting before spawn chunks load"
        );
        assertFalse(
            activation.contains("chunkLoadStates = config.shareChunkLoadState ? new ChunkLoadStateService()"),
            "runtime activation must preserve chunk states collected during world startup"
        );
    }

    @Test
    void nativeLibraryIsLoadedFromTheGameDirectoryNotTheWorldSave() throws IOException {
        final String source = Files.readString(
            findProjectRoot().resolve(
                "src/main/java/cn/net/rms/confluxmap/server/ConfluxMapCompanion.java"
            )
        );
        final String activation = between(source, "private void activateIfNeeded(", "\n    }\n}");

        assertTrue(
            activation.contains("NativeLib.init(FabricLoader.getInstance().getGameDir()"),
            "the native library must be extracted under the game directory"
        );
        assertFalse(
            activation.contains("NativeLib.init(server.getSavePath("),
            "a loaded native library is locked on Windows; extracting it into the world save"
                + " breaks backup tools that restore the save by copying it back"
        );
        assertTrue(
            activation.contains("NativeLib.removeLegacyNativesUnder(server.getSavePath("),
            "world saves from older versions must have their legacy natives tree cleaned up"
        );
    }

    private static String between(final String source, final String start, final String end) {
        final int startIndex = source.indexOf(start);
        final int endIndex = source.indexOf(end, startIndex);
        assertTrue(startIndex >= 0 && endIndex > startIndex, "companion lifecycle method must be present");
        return source.substring(startIndex, endIndex);
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
