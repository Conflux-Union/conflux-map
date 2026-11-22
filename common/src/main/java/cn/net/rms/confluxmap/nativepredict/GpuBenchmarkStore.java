package cn.net.rms.confluxmap.nativepredict;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import org.apache.logging.log4j.Logger;

/**
 * Disk cache for the AUTO backend benchmark's verdict, keyed on everything whose change
 * can flip which engine is faster: the benchmark workload itself ({@link
 * #WORKLOAD_VERSION}), the CPU (brand + logical processors), the OpenCL device, and the
 * prediction library identity. A hit needs every field equal; a miss re-runs the
 * benchmark. Writes are atomic and a corrupt file is quarantined as {@code .bad} and read
 * as a miss, mirroring {@code ConfigIo}, so a broken edit never wedges startup.
 */
public final class GpuBenchmarkStore {
    /**
     * Bump when the benchmark load or leg orchestration changes in a way that invalidates
     * old numbers; every cache written by a previous version then misses.
     */
    public static final int WORKLOAD_VERSION = 1;

    /**
     * The identity a verdict is cached under; a hit needs every field equal. Plain
     * mutable fields (not a record): the game's bundled Gson predates record
     * deserialization on older versions like 1.17.1, and the reflective no-arg path is
     * the only one that works across the whole supported matrix.
     */
    public static final class Key {
        public int workloadVersion;
        public String cpuBrand;
        public int logicalProcessors;
        public String gpuDevice;
        public int cfxAbi;
        public int baselineAlgo;

        public Key() {
        }

        public Key(final int workloadVersion, final String cpuBrand, final int logicalProcessors,
                   final String gpuDevice, final int cfxAbi, final int baselineAlgo) {
            this.workloadVersion = workloadVersion;
            this.cpuBrand = cpuBrand;
            this.logicalProcessors = logicalProcessors;
            this.gpuDevice = gpuDevice;
            this.cfxAbi = cfxAbi;
            this.baselineAlgo = baselineAlgo;
        }

        @Override
        public boolean equals(final Object other) {
            if (!(other instanceof Key)) {
                return false;
            }
            final Key that = (Key) other;
            return workloadVersion == that.workloadVersion
                && logicalProcessors == that.logicalProcessors
                && cfxAbi == that.cfxAbi
                && baselineAlgo == that.baselineAlgo
                && java.util.Objects.equals(cpuBrand, that.cpuBrand)
                && java.util.Objects.equals(gpuDevice, that.gpuDevice);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(workloadVersion, cpuBrand, logicalProcessors,
                gpuDevice, cfxAbi, baselineAlgo);
        }

        @Override
        public String toString() {
            return "Key[workload=" + workloadVersion + ", cpu=" + cpuBrand + '/'
                + logicalProcessors + ", gpu=" + gpuDevice + ", abi=" + cfxAbi
                + ", algo=" + baselineAlgo + ']';
        }
    }

    /** One measured verdict plus the per-tile sampling costs that produced it (ms/tile). */
    public static final class Entry {
        public Key key;
        public PredictionBackend winner;
        public double cpuMillisPerTile;
        public double gpuMillisPerTile;
        public long timestampMillis;

        public Entry() {
        }

        public Entry(final Key key, final PredictionBackend winner, final double cpuMillisPerTile,
                     final double gpuMillisPerTile, final long timestampMillis) {
            this.key = key;
            this.winner = winner;
            this.cpuMillisPerTile = cpuMillisPerTile;
            this.gpuMillisPerTile = gpuMillisPerTile;
            this.timestampMillis = timestampMillis;
        }

        public Key key() {
            return key;
        }

        public PredictionBackend winner() {
            return winner;
        }

        public double cpuMillisPerTile() {
            return cpuMillisPerTile;
        }

        public double gpuMillisPerTile() {
            return gpuMillisPerTile;
        }

        public long timestampMillis() {
            return timestampMillis;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Logger logger;

    public GpuBenchmarkStore(final Path file, final Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    /** The cached entry when the file exists, parses, and its key matches; else empty. */
    public Optional<Entry> load(final Key key) {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            final Entry entry = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Entry.class);
            if (entry == null || entry.winner() == null || !key.equals(entry.key())) {
                return Optional.empty();
            }
            return Optional.of(entry);
        } catch (final IOException | JsonParseException unreadable) {
            logger.warn("Benchmark cache {} unreadable ({}), quarantining", file, unreadable.toString());
            quarantine();
            return Optional.empty();
        }
    }

    /**
     * Pre-warmup read for the startup chain: a fresh CPU verdict whose key matches in every
     * field except {@code gpuDevice} (the OFF engine never probes a device to compare
     * against, so this boot cannot know it) applies before the warmup decision and skips
     * the OpenCL JIT entirely. GPU verdicts never prewarm-apply - those boots warm up and
     * the benchmark thread re-validates the full key, device included. Stale entries miss
     * so the next boot re-measures.
     */
    public Optional<Entry> loadPrewarmVerdict(final Key keyWithDeviceIgnored, final long maxAgeMillis) {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            final Entry entry = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Entry.class);
            if (entry == null || entry.winner() != PredictionBackend.CPU || entry.key() == null) {
                return Optional.empty();
            }
            final Key stored = entry.key();
            final boolean matches = stored.workloadVersion == keyWithDeviceIgnored.workloadVersion
                && java.util.Objects.equals(stored.cpuBrand, keyWithDeviceIgnored.cpuBrand)
                && stored.logicalProcessors == keyWithDeviceIgnored.logicalProcessors
                && stored.cfxAbi == keyWithDeviceIgnored.cfxAbi
                && stored.baselineAlgo == keyWithDeviceIgnored.baselineAlgo;
            if (!matches || entry.timestampMillis() <= 0) {
                return Optional.empty();
            }
            final long now = System.currentTimeMillis();
            if (now - entry.timestampMillis() > maxAgeMillis || now + 1_000_000L < entry.timestampMillis()) {
                return Optional.empty();
            }
            return Optional.of(entry);
        } catch (final IOException | JsonParseException unreadable) {
            logger.warn("Benchmark cache {} unreadable ({}), quarantining", file, unreadable.toString());
            quarantine();
            return Optional.empty();
        }
    }

    /** Atomically writes the entry (tmp + move); a failed write logs and leaves the old file. */
    public void save(final Entry entry) {
        try {
            Files.createDirectories(file.getParent());
            final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(entry), StandardCharsets.UTF_8);
            move(tmp);
        } catch (final IOException e) {
            logger.error("Failed to save benchmark cache to {}", file, e);
        }
    }

    private void move(final Path tmp) throws IOException {
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void quarantine() {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException e) {
            logger.warn("Could not quarantine {}", file, e);
        }
    }
}
