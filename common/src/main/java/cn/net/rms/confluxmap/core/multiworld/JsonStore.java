package cn.net.rms.confluxmap.core.multiworld;

import cn.net.rms.confluxmap.core.util.AtomicFiles;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.apache.logging.log4j.Logger;

/**
 * Plain-JSON atomic persistence for a small registry file: a missing file
 * loads as a fresh instance, an empty or unparsable file is quarantined as
 * {@code *.bad} and replaced by a fresh instance, and every successful load
 * passes through the normalizer before use.
 *
 * @param <T> the registry type carried by the file
 */
public final class JsonStore<T> {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Logger logger;
    private final Class<T> type;
    private final Supplier<T> fresh;
    private final Consumer<T> normalizer;
    private final String emptyMessage;
    private final String unreadableMessage;
    private final String quarantineMessage;
    private final String saveFailureMessage;

    public JsonStore(
        final Path file,
        final Logger logger,
        final Class<T> type,
        final Supplier<T> fresh,
        final Consumer<T> normalizer,
        final String label
    ) {
        this.file = file;
        this.logger = logger;
        this.type = type;
        this.fresh = fresh;
        this.normalizer = normalizer;
        this.emptyMessage = "empty " + label;
        this.unreadableMessage = Character.toUpperCase(label.charAt(0)) + label.substring(1)
            + " {} unreadable ({}), quarantining";
        this.quarantineMessage = "Could not quarantine " + label + " {}";
        this.saveFailureMessage = "Failed to save " + label + " to {}";
    }

    /** The registry when the file exists, parses, and normalizes; otherwise a fresh instance. */
    public T load() {
        if (!Files.exists(file)) {
            return fresh.get();
        }
        try {
            final T registry = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), type);
            if (registry == null) {
                throw new JsonParseException(emptyMessage);
            }
            normalizer.accept(registry);
            return registry;
        } catch (final IOException | JsonParseException | IllegalArgumentException e) {
            logger.warn(unreadableMessage, file, e.toString());
            quarantine();
            return fresh.get();
        }
    }

    public void save(final T value) {
        AtomicFiles.writeStringAtomic(file, GSON.toJson(value), logger, saveFailureMessage);
    }

    private void quarantine() {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException e) {
            logger.warn(quarantineMessage, file, e);
        }
    }
}
