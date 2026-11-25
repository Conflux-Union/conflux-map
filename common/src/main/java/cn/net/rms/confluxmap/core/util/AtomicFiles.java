package cn.net.rms.confluxmap.core.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.apache.logging.log4j.Logger;

/**
 * The atomic-write idiom shared by every JSON store: write a sibling
 * {@code .tmp} file, move it into place, and quarantine a corrupt file as
 * {@code *.bad} so a broken edit never wedges the mod.
 */
public final class AtomicFiles {
    private AtomicFiles() {
    }

    /**
     * Moves {@code temporary} onto {@code file}, replacing any existing file.
     * The move is atomic where the filesystem supports it and plain otherwise.
     */
    public static void moveReplacing(final Path temporary, final Path file) throws IOException {
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Moves {@code file} aside as {@code *.bad}; only warns when even that fails. */
    public static void quarantine(final Path file, final Logger logger) {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException e) {
            logger.warn("Could not quarantine {}", file, e);
        }
    }

    /**
     * Writes {@code content} to {@code file} through a sibling {@code .tmp} file;
     * a failure logs {@code failureMessage} and leaves the previous file in place.
     */
    public static void writeStringAtomic(
        final Path file,
        final String content,
        final Logger logger,
        final String failureMessage
    ) {
        try {
            Files.createDirectories(file.getParent());
            final Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            moveReplacing(temporary, file);
        } catch (final IOException e) {
            logger.error(failureMessage, file, e);
        }
    }
}
