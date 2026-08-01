package cn.net.rms.confluxmap.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.cache.RegionFileCodec;
import cn.net.rms.confluxmap.core.net.ProtoException;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.ref.Cleaner;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies that codec calls do not leave native zlib handles pending GC-driven Cleaner reclamation. */
class CodecInflaterLifecycleTest {
    @Test
    void regionDecodeReleasesInflaterAfterSuccess() throws Exception {
        assertNoCleanerBacklog("region-success");
    }

    @Test
    void regionDecodeReleasesInflaterAfterFailure() throws Exception {
        assertNoCleanerBacklog("region-failure");
    }

    @Test
    void summaryDecodeReleasesInflaterAfterSuccess() throws Exception {
        assertNoCleanerBacklog("summary-success");
    }

    @Test
    void summaryDecodeReleasesInflaterAfterFailure() throws Exception {
        assertNoCleanerBacklog("summary-failure");
    }

    private static void assertNoCleanerBacklog(final String scenario) throws Exception {
        final String javaCommand = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        final String javaExecutable = Path.of(System.getProperty("java.home"), "bin", javaCommand).toString();
        final String testClasses = Path.of(
            CodecInflaterLifecycleProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI()
        ).toString();
        final String mainClasses = Path.of(
            SummaryCodec.class.getProtectionDomain().getCodeSource().getLocation().toURI()
        ).toString();
        final List<String> command = List.of(
            javaExecutable,
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+UseEpsilonGC",
            "-Xms64m",
            "-Xmx64m",
            "--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED",
            "--add-opens=java.base/java.lang.ref=ALL-UNNAMED",
            "--add-opens=java.base/jdk.internal.ref=ALL-UNNAMED",
            "-cp",
            testClasses + File.pathSeparator + mainClasses,
            CodecInflaterLifecycleProbe.class.getName(),
            scenario
        );
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, process.waitFor(), output);
    }
}

final class CodecInflaterLifecycleProbe {
    private CodecInflaterLifecycleProbe() {
    }

    public static void main(final String[] args) throws Exception {
        final RunnableWithException scenario = scenario(args[0]);
        scenario.run();
        final int before = commonCleanerRegistrationCount();
        scenario.run();
        final int after = commonCleanerRegistrationCount();
        if (after != before) {
            throw new AssertionError(
                args[0] + " left " + (after - before) + " native resource(s) pending Cleaner reclamation"
            );
        }
    }

    private static RunnableWithException scenario(final String name) throws Exception {
        return switch (name) {
            case "region-success" -> regionScenario(false);
            case "region-failure" -> regionScenario(true);
            case "summary-success" -> summaryScenario(false);
            case "summary-failure" -> summaryScenario(true);
            default -> throw new IllegalArgumentException("unknown scenario " + name);
        };
    }

    private static RunnableWithException regionScenario(final boolean corrupt) throws IOException {
        final ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        RegionFileCodec.encode(encoded, 0, emptyRegion());
        final byte[] input = encoded.toByteArray();
        if (corrupt) {
            input[RegionFileCodec.HEADER_SIZE + RegionFileCodec.CHUNK_TABLE_SIZE] ^= (byte) 0xFF;
        }
        return () -> {
            try {
                RegionFileCodec.decode(new ByteArrayInputStream(input), 0, 0, 0);
                if (corrupt) {
                    throw new AssertionError("corrupt region unexpectedly decoded");
                }
            } catch (final IOException expected) {
                if (!corrupt) {
                    throw expected;
                }
            }
        };
    }

    private static RegionFileCodec.RegionData emptyRegion() {
        return new RegionFileCodec.RegionData(
            0,
            0,
            0L,
            new byte[RegionFileCodec.CHUNK_TABLE_ENTRIES],
            new int[RegionFileCodec.CHUNK_TABLE_ENTRIES],
            new short[RegionFileCodec.COLUMN_COUNT],
            new byte[RegionFileCodec.COLUMN_COUNT],
            new byte[RegionFileCodec.COLUMN_COUNT],
            new String[RegionFileCodec.COLUMN_COUNT],
            new int[RegionFileCodec.COLUMN_COUNT],
            new int[RegionFileCodec.COLUMN_COUNT],
            new int[RegionFileCodec.COLUMN_COUNT],
            new byte[RegionFileCodec.COLUMN_COUNT]
        );
    }

    private static RunnableWithException summaryScenario(final boolean corrupt) {
        final SummaryCodec.Chunk[] chunks = new SummaryCodec.Chunk[SummaryCodec.CHUNKS];
        Arrays.fill(chunks, SummaryCodec.Chunk.empty());
        final byte[] input = SummaryCodec.encode(new SummaryCodec.Region(0, 0, 0L, chunks));
        if (corrupt) {
            final int headerBytes = 4 + 1 + 4 + 4 + 8 + SummaryCodec.CHUNKS * 9;
            input[headerBytes] ^= (byte) 0xFF;
        }
        return () -> {
            try {
                SummaryCodec.decode(input);
                if (corrupt) {
                    throw new AssertionError("corrupt summary unexpectedly decoded");
                }
            } catch (final ProtoException expected) {
                if (!corrupt) {
                    throw expected;
                }
            }
        };
    }

    private static int commonCleanerRegistrationCount() throws Exception {
        final Class<?> cleanerFactory = Class.forName("jdk.internal.ref.CleanerFactory");
        final Cleaner cleaner = (Cleaner) cleanerFactory.getMethod("cleaner").invoke(null);
        final Field implField = Cleaner.class.getDeclaredField("impl");
        implField.setAccessible(true);
        final Object cleanerImpl = implField.get(cleaner);
        try {
            return linkedCleanerRegistrationCount(cleanerImpl);
        } catch (final NoSuchFieldException ignored) {
            return arrayCleanerRegistrationCount(cleanerImpl);
        }
    }

    private static int linkedCleanerRegistrationCount(final Object cleanerImpl) throws Exception {
        final Field listField = cleanerImpl.getClass().getDeclaredField("phantomCleanableList");
        listField.setAccessible(true);
        final Object sentinel = listField.get(cleanerImpl);
        final Field nextField = Class.forName("jdk.internal.ref.PhantomCleanable").getDeclaredField("next");
        nextField.setAccessible(true);
        synchronized (sentinel) {
            int count = 0;
            Object current = nextField.get(sentinel);
            while (current != sentinel) {
                count++;
                current = nextField.get(current);
            }
            return count;
        }
    }

    private static int arrayCleanerRegistrationCount(final Object cleanerImpl) throws Exception {
        final Field listField = cleanerImpl.getClass().getDeclaredField("activeList");
        listField.setAccessible(true);
        final Object list = listField.get(cleanerImpl);
        final Field headField = list.getClass().getDeclaredField("head");
        headField.setAccessible(true);
        synchronized (list) {
            Object node = headField.get(list);
            if (node == null) {
                return 0;
            }
            final Field sizeField = node.getClass().getDeclaredField("size");
            final Field nextField = node.getClass().getDeclaredField("next");
            sizeField.setAccessible(true);
            nextField.setAccessible(true);
            int count = 0;
            while (node != null) {
                count += sizeField.getInt(node);
                node = nextField.get(node);
            }
            return count;
        }
    }

    @FunctionalInterface
    private interface RunnableWithException {
        void run() throws Exception;
    }
}
