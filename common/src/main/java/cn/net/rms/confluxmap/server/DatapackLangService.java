package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.net.Proto;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.logging.log4j.Logger;

/**
 * Serves biome display names that a world's data packs carry in their {@code assets/} half.
 *
 * <p>Vanilla never loads a data pack's {@code assets/} on either side: the client's language
 * table only knows resource packs and mods, and the server's resource manager indexes {@code data/}
 * alone. Packs distributed as one bundle that works as both halves are therefore unreadable
 * everywhere vanilla looks — this service reads them straight off disk and hands the merged
 * {@code biome.*} entries to the companion protocol (BIOME_LANG_REQ / BIOME_LANG_S2C), which
 * fills exactly the gap the client's own translation lookup leaves.
 *
 * <p>Deliberately dumb about pack state: every entry under the level's {@code datapacks/}
 * directory is read, without consulting {@code level.dat}'s enabled/disabled selection. A name
 * contributed by a disabled pack can only surface when some enabled pack also registers that
 * biome id, and the value shown is then merely cosmetic; parsing {@code level.dat} to prevent
 * that is not worth the coupling. Merge order (later wins) is pack-name alphabetical, then
 * {@code en_us} before the requested locale inside each pack.
 */
public final class DatapackLangService {
    private static final String BIOME_KEY_PREFIX = "biome.";
    private static final String LANG_DIRECTORY = "lang/";
    private static final String EN_US = "en_us.json";

    private final Supplier<Path> datapacksDir;
    private final Logger logger;
    /** Single-slot cache; the fingerprint guards the directory, the locale the file set. */
    private volatile CacheSlot cache = new CacheSlot("", "", Map.of());
    private final Object scanLock = new Object();
    private final ExecutorService worker;

    public DatapackLangService(final Supplier<Path> datapacksDir, final Logger logger) {
        this.datapacksDir = datapacksDir;
        this.logger = logger;
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "ConfluxMap Datapack Lang");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static final class CacheSlot {
        final String fingerprint;
        final String locale;
        final Map<String, String> names;

        CacheSlot(final String fingerprint, final String locale, final Map<String, String> names) {
            this.fingerprint = fingerprint;
            this.locale = locale;
            this.names = names;
        }
    }

    /**
     * Scans the data packs off-thread and hands the merged map to {@code onResult} on that worker
     * thread; callers marshal back to their own thread (the plugin-message send on both platforms
     * wants the server thread). A usable answer is always delivered, possibly empty.
     */
    public void requestBiomeNames(final String locale, final Consumer<Map<String, String>> onResult) {
        final String normalized = normalizeLocale(locale);
        worker.execute(() -> onResult.accept(biomeNames(normalized)));
    }

    public void shutdown() {
        worker.shutdownNow();
    }

    private Map<String, String> biomeNames(final String locale) {
        final Path dir = datapacksDir.get();
        if (dir == null) {
            return Map.of();
        }
        final String fingerprint = fingerprint(dir);
        final CacheSlot cached = cache;
        if (cached.locale.equals(locale) && cached.fingerprint.equals(fingerprint)) {
            return cached.names;
        }
        synchronized (scanLock) {
            final CacheSlot rescanning = cache;
            if (rescanning.locale.equals(locale) && rescanning.fingerprint.equals(fingerprint)) {
                return rescanning.names;
            }
            final Map<String, String> names = scan(dir, locale);
            cache = new CacheSlot(fingerprint, locale, names);
            return names;
        }
    }

    /** Cheap staleness check: top-level names, kinds, sizes and mtimes of the pack entries. */
    private String fingerprint(final Path dir) {
        final StringBuilder out = new StringBuilder();
        try (final Stream<Path> entries = Files.list(dir)) {
            entries.sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .forEach(path -> {
                    try {
                        out.append(path.getFileName()).append('|')
                            .append(Files.isDirectory(path) ? 'd' : 'f').append('|')
                            .append(Files.size(path)).append('|')
                            .append(Files.getLastModifiedTime(path).toMillis()).append(';');
                    } catch (final IOException e) {
                        out.append(path.getFileName()).append("|err;");
                    }
                });
        } catch (final IOException e) {
            logger.debug("datapack lang: cannot list {}: {}", dir, e.toString());
        }
        return out.toString();
    }

    private Map<String, String> scan(final Path dir, final String locale) {
        final Map<String, String> merged = new LinkedHashMap<>();
        final List<Path> packs = new ArrayList<>();
        try (final Stream<Path> entries = Files.list(dir)) {
            entries.forEach(packs::add);
        } catch (final IOException e) {
            logger.debug("datapack lang: cannot scan {}: {}", dir, e.toString());
            return Map.of();
        }
        packs.sort(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)));
        for (final Path pack : packs) {
            if (merged.size() >= Proto.MAX_BIOME_LANG_ENTRIES) {
                logger.warn("datapack lang: entry cap reached, ignoring remaining packs in {}", dir);
                break;
            }
            if (Files.isDirectory(pack)) {
                mergeFolderPack(merged, pack, locale);
            } else if (pack.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                mergeZipPack(merged, pack, locale);
            }
        }
        return Map.copyOf(merged);
    }

    private void mergeFolderPack(final Map<String, String> merged, final Path pack, final String locale) {
        final Path assets = pack.resolve("assets");
        if (!Files.isDirectory(assets)) {
            return;
        }
        final List<Path> namespaces = new ArrayList<>();
        try (final Stream<Path> entries = Files.list(assets)) {
            entries.filter(Files::isDirectory).forEach(namespaces::add);
        } catch (final IOException e) {
            logger.debug("datapack lang: cannot list {}: {}", assets, e.toString());
            return;
        }
        namespaces.sort(Comparator.comparing(path -> path.getFileName().toString()));
        for (final Path namespace : namespaces) {
            mergeLangFile(merged, namespace.resolve("lang").resolve(EN_US));
            mergeLangFile(merged, namespace.resolve("lang").resolve(locale + ".json"));
        }
    }

    private void mergeZipPack(final Map<String, String> merged, final Path pack, final String locale) {
        try (ZipFile zip = new ZipFile(pack.toFile(), StandardCharsets.UTF_8)) {
            mergeZipPass(merged, zip, EN_US);
            final String localeFile = locale + ".json";
            if (!localeFile.equals(EN_US)) {
                mergeZipPass(merged, zip, localeFile);
            }
        } catch (final IOException e) {
            logger.debug("datapack lang: cannot read {}: {}", pack, e.toString());
        }
    }

    private void mergeZipPass(final Map<String, String> merged, final ZipFile zip, final String fileName) {
        final List<? extends ZipEntry> entries = zip.stream()
            .filter(entry -> isLangEntry(entry, fileName))
            .sorted(Comparator.comparing(ZipEntry::getName))
            .toList();
        for (final ZipEntry entry : entries) {
            try (Reader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                mergeLangReader(merged, entry.getName(), reader);
            } catch (final IOException e) {
                logger.debug("datapack lang: cannot read {} in {}: {}", entry.getName(), zip.getName(), e.toString());
            }
        }
    }

    private static boolean isLangEntry(final ZipEntry entry, final String fileName) {
        final String name = entry.getName();
        return !entry.isDirectory()
            && name.startsWith("assets/")
            && name.endsWith(LANG_DIRECTORY + fileName);
    }

    private void mergeLangFile(final Map<String, String> merged, final Path file) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            mergeLangReader(merged, file.toString(), reader);
        } catch (final IOException e) {
            logger.debug("datapack lang: cannot read {}: {}", file, e.toString());
        }
    }

    private void mergeLangReader(final Map<String, String> merged, final String source, final Reader reader) {
        try {
            final JsonElement parsed = new JsonParser().parse(reader);
            if (!parsed.isJsonObject()) {
                return;
            }
            for (final Map.Entry<String, JsonElement> entry : parsed.getAsJsonObject().entrySet()) {
                if (merged.size() >= Proto.MAX_BIOME_LANG_ENTRIES) {
                    logger.warn("datapack lang: entry cap reached, ignoring the rest of {}", source);
                    return;
                }
                final String key = entry.getKey();
                final JsonElement value = entry.getValue();
                if (!key.startsWith(BIOME_KEY_PREFIX)
                    || !value.isJsonPrimitive()
                    || !value.getAsJsonPrimitive().isString()) {
                    continue;
                }
                final String name = value.getAsString();
                if (name.getBytes(StandardCharsets.UTF_8).length > Proto.MAX_UTF8_BYTES) {
                    logger.debug("datapack lang: skipping overlong value for {} in {}", key, source);
                    continue;
                }
                merged.put(key, name);
            }
        } catch (final Exception e) {
            logger.debug("datapack lang: cannot parse {}: {}", source, e.toString());
        }
    }

    /** Locale codes become file-name fragments on disk, so anything unexpected falls back to en_us. */
    static String normalizeLocale(final String locale) {
        if (locale == null) {
            return "en_us";
        }
        final String normalized = locale.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("[a-z0-9_]{2,16}") ? normalized : "en_us";
    }
}
