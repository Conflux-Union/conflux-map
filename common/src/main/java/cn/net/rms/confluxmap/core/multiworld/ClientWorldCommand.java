package cn.net.rms.confluxmap.core.multiworld;

import java.util.Locale;
import java.util.Optional;

/** Normalizes explicitly configured client-world switch commands for exact matching. */
public final class ClientWorldCommand {
    private ClientWorldCommand() {
    }

    /**
     * Returns an exact-match key only for text that the player submitted as a command. The raw
     * content check deliberately happens before trimming so ordinary chat such as {@code " /hub"}
     * cannot select a map.
     */
    public static Optional<String> fromSubmittedText(final String rawText) {
        if (rawText == null || !rawText.startsWith("/")) {
            return Optional.empty();
        }
        try {
            return Optional.of(normalizeConfigured(rawText));
        } catch (final IllegalArgumentException ignored) {
            // Submitted chat is untrusted; invalid input is ignored while saved commands remain strict.
            return Optional.empty();
        }
    }

    /** Validates and normalizes a command configured for one client-world profile. */
    public static String normalizeConfigured(final String value) {
        if (value == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        final String normalized = value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        if (normalized.length() < 2 || !normalized.startsWith("/")) {
            throw new IllegalArgumentException("command must start with '/' and contain a command name");
        }
        return normalized;
    }
}
