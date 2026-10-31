package cn.net.rms.confluxmap.core.xaeroimport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Decodes real region files harvested from Xaero installations when they are
 * available on the machine (maintainer corpora live outside the repository and
 * are never committed). Skips silently otherwise, like the native tests.
 */
class XaeroRealSamplesTest {
    private static final String SAMPLES_PROPERTY = "confluxmap.xaero.samples";
    private static final Path DEFAULT_CORPUS =
        Path.of("/home/Trirrin/Desktop/Project/_reference/XaeroWorldMapSamples");

    private static Path corpus() {
        final String override = System.getProperty(SAMPLES_PROPERTY);
        final Path dir = override != null ? Path.of(override) : DEFAULT_CORPUS;
        return Files.isDirectory(dir) ? dir : null;
    }

    @Test
    void decodesEveryRealRegionFile() throws IOException {
        final Path corpusDir = corpus();
        if (corpusDir == null) {
            System.out.println("XaeroRealSamplesTest: no sample corpus found, skipping");
            return;
        }
        int files = 0;
        final AtomicInteger skipped = new AtomicInteger();
        try (var eras = Files.list(corpusDir)) {
            for (final Path eraDir : eras.filter(Files::isDirectory).toList()) {
                try (var eraFiles = Files.walk(eraDir)) {
                    for (final Path file : eraFiles.filter(Files::isRegularFile).toList()) {
                        final XaeroRegionFile region = XaeroRegionFile.parse(file);
                        if (region == null) {
                            continue;
                        }
                        files++;
                        try (InputStream stream = region.open()) {
                            final XaeroRegionReader.Result result = XaeroRegionReader.read(
                                stream, region.regionX(), region.regionZ(), (chunkX, chunkZ, tile) -> {});
                            if (result instanceof XaeroRegionReader.Result.Skipped skip) {
                                skipped.incrementAndGet();
                                System.out.println("skipped " + file + ": " + skip.reason());
                            } else {
                                final XaeroRegionReader.Result.Decoded decoded =
                                    assertInstanceOf(XaeroRegionReader.Result.Decoded.class, result);
                                assertTrue(decoded.chunks() > 0, file + " decoded empty");
                            }
                        }
                    }
                }
            }
        }
        assertTrue(files > 0, "corpus contained no region files");
        System.out.println("XaeroRealSamplesTest: decoded " + files + " regions, " + skipped + " skipped by design");
        assertEquals(0, skipped.get(), "all corpus files should be in the supported window");
    }
}
