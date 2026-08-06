package cn.net.rms.confluxmap.server.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Small durable deny-list for players who opt out of the public radar. */
public final class WebMapPrivacyStore {
    private final Path file;
    private final Set<UUID> hidden = new HashSet<>();

    public WebMapPrivacyStore(final Path file) {
        this.file = file;
    }

    public synchronized void load() throws IOException {
        hidden.clear();
        if (!Files.exists(file)) return;
        for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            try {
                hidden.add(UUID.fromString(line.trim()));
            } catch (final IllegalArgumentException ignored) {
                // Preserve service availability when an operator hand-edits one bad line.
            }
        }
    }

    public synchronized boolean hidden(final UUID playerId) {
        return hidden.contains(playerId);
    }

    public synchronized boolean setHidden(final UUID playerId, final boolean value) throws IOException {
        final boolean changed = value ? hidden.add(playerId) : hidden.remove(playerId);
        if (!changed) return false;
        try {
            persist();
        } catch (final IOException | RuntimeException e) {
            if (value) hidden.remove(playerId); else hidden.add(playerId);
            throw e;
        }
        return true;
    }

    private void persist() throws IOException {
        Files.createDirectories(file.getParent());
        final Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        final StringBuilder body = new StringBuilder(hidden.size() * 37);
        hidden.stream().sorted().forEach(id -> body.append(id).append('\n'));
        Files.writeString(temporary, body, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
