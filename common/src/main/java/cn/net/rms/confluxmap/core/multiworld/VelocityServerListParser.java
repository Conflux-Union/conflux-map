package cn.net.rms.confluxmap.core.multiworld;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Extracts Velocity's current registered server from its styled no-argument /server list. */
public final class VelocityServerListParser {
    private static final int GREEN = 0x55FF55;
    private static final int GRAY = 0xAAAAAA;
    private static final int YELLOW = 0xFFFF55;
    private static final String SERVER_COMMAND_PREFIX = "/server ";
    private static final int MAX_SERVER_NAME_LENGTH = 256;

    private VelocityServerListParser() {
    }

    /**
     * The localized prefix is deliberately ignored. Velocity identifies the current server with
     * green text and every other server with a gray /server click action.
     */
    public static Optional<String> parse(final List<Segment> segments) {
        if (segments == null || segments.isEmpty()) {
            return Optional.empty();
        }
        String current = null;
        boolean velocityPrefix = false;
        for (final Segment segment : segments) {
            if (segment == null || segment.text() == null || segment.text().isBlank()) {
                continue;
            }
            final String text;
            try {
                text = normalizeServerName(segment.text());
            } catch (final IllegalArgumentException ignored) {
                if (segment.runCommand() != null || Integer.valueOf(GREEN).equals(segment.colorRgb())) {
                    return Optional.empty();
                }
                continue;
            }
            if (Integer.valueOf(YELLOW).equals(segment.colorRgb()) && segment.runCommand() == null) {
                velocityPrefix = true;
                continue;
            }
            if (Integer.valueOf(GREEN).equals(segment.colorRgb()) && segment.runCommand() == null) {
                if (current != null) {
                    return Optional.empty();
                }
                current = text;
                continue;
            }
            if (segment.runCommand() != null) {
                if (!Integer.valueOf(GRAY).equals(segment.colorRgb())) {
                    return Optional.empty();
                }
                final Optional<String> target = serverCommandTarget(segment.runCommand());
                if (target.isEmpty() || !target.get().equals(text)) {
                    return Optional.empty();
                }
            }
        }
        return velocityPrefix && current != null ? Optional.of(current) : Optional.empty();
    }

    static String normalizeServerName(final String value) {
        if (value == null) {
            throw new IllegalArgumentException("Velocity server name must not be null");
        }
        final String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.length() > MAX_SERVER_NAME_LENGTH
            || normalized.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new IllegalArgumentException("Velocity server name is invalid");
        }
        return normalized;
    }

    private static Optional<String> serverCommandTarget(final String command) {
        if (command == null || !command.regionMatches(true, 0, SERVER_COMMAND_PREFIX, 0, SERVER_COMMAND_PREFIX.length())) {
            return Optional.empty();
        }
        try {
            return Optional.of(normalizeServerName(command.substring(SERVER_COMMAND_PREFIX.length())));
        } catch (final IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    /** One flattened text fragment with its effective RGB color and optional run-command action. */
    public record Segment(String text, Integer colorRgb, String runCommand) {
    }
}
