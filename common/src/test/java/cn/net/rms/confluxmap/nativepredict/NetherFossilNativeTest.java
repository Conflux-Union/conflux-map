package cn.net.rms.confluxmap.nativepredict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class NetherFossilNativeTest {
    private static final int FOSSIL = 26;

    record Sample(String version, long seed, String bits) {
        @Override
        public String toString() {
            return version + ":" + seed;
        }
    }

    static Stream<Sample> vanillaSamples() throws Exception {
        try (var input = NetherFossilNativeTest.class.getResourceAsStream("nether-fossils.csv")) {
            assertNotNull(input);
            try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                final List<String> lines = reader.lines().filter(line -> !line.startsWith("#")).toList();
                return lines.stream().map(line -> {
                    final String[] fields = line.split(",");
                    return new Sample(fields[0], Long.parseLong(fields[1]), fields[2]);
                });
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vanillaSamples")
    void singleBatchAndNearestQueriesMatchVanilla(final Sample sample) {
        Assumptions.assumeTrue(NativeLib.initForTests(), "native library unavailable");
        try (var context = CubiomesContext.create(
            McVersions.toCubiomes(sample.version()).orElseThrow(), sample.seed(), -1, 0
        )) {
            assertNotNull(context);
            assertEquals(256, sample.bits().length());
            final Set<Long> expected = new HashSet<>();
            for (int index = 0; index < 1_024; index++) {
                final int x = (index % 32 - 16) * 32;
                final int z = (index / 32 - 16) * 32;
                final boolean valid = (Character.digit(sample.bits().charAt(index / 4), 16)
                    & (1 << (index % 4))) != 0;
                assertEquals(valid, context.structureViable(FOSSIL, x, z), x + "," + z);
                if (valid) {
                    expected.add(pack(x, z));
                }
            }

            final long[] positions = new long[1_024];
            final int count = context.viableStructures(FOSSIL, -16, -16, 15, 15, positions);
            final Set<Long> actual = new HashSet<>();
            for (int index = 0; index < count; index++) {
                actual.add(positions[index]);
            }
            assertEquals(expected.size(), count);
            assertEquals(expected, actual);

            // This circle fits entirely inside the independently sampled square.
            final int radius = 480;
            final var nearestDistance = expected.stream().mapToLong(NetherFossilNativeTest::distanceSquared)
                .filter(distance -> distance <= (long) radius * radius).min();
            final long[] nearest = new long[1];
            assertEquals(nearestDistance.isPresent(), context.nearestStructure(FOSSIL, 0, 0, radius, nearest));
            if (nearestDistance.isPresent()) {
                assertTrue(expected.contains(nearest[0]));
                assertEquals(nearestDistance.getAsLong(), distanceSquared(nearest[0]));
            }
        }
    }

    private static long pack(final int x, final int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    private static long distanceSquared(final long position) {
        final long x = (int) (position >> 32);
        final long z = (int) position;
        return x * x + z * z;
    }
}
