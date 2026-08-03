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
    private static final String SERVER_SEPARATOR = ", ";
    private static final int MAX_SERVER_NAME_LENGTH = 256;

    private VelocityServerListParser() {
    }

    /** Extracts the current server from a list whose prefix was authenticated by the adapter. */
    public static Optional<String> parse(final List<Segment> segments) {
        if (segments == null || segments.isEmpty()) {
            return Optional.empty();
        }
        final List<Segment> visible = segments.stream()
            .filter(segment -> segment != null && segment.text() != null
                && !segment.text().isBlank())
            .toList();
        if (visible.size() < 2 || !isVelocityPrefix(visible.get(0))) {
            return Optional.empty();
        }
        String current = null;
        boolean expectServer = true;
        for (int index = 1; index < visible.size(); index++) {
            final Segment segment = visible.get(index);
            if (!expectServer) {
                if (!isServerSeparator(segment)) {
                    return Optional.empty();
                }
                expectServer = true;
                continue;
            }
            final String text;
            try {
                text = normalizeServerName(segment.text());
            } catch (final IllegalArgumentException ignored) {
                return Optional.empty();
            }
            if (Integer.valueOf(GREEN).equals(segment.colorRgb()) && segment.runCommand() == null) {
                if (current != null) {
                    return Optional.empty();
                }
                current = text;
            } else if (segment.runCommand() != null) {
                if (!Integer.valueOf(GRAY).equals(segment.colorRgb())) {
                    return Optional.empty();
                }
                final Optional<String> target = serverCommandTarget(segment.runCommand());
                if (target.isEmpty() || !target.get().equals(text)) {
                    return Optional.empty();
                }
            } else {
                return Optional.empty();
            }
            expectServer = false;
        }
        return !expectServer && current != null ? Optional.of(current) : Optional.empty();
    }

    private static boolean isVelocityPrefix(final Segment segment) {
        return segment.velocityAvailablePrefix()
            && Integer.valueOf(YELLOW).equals(segment.colorRgb())
            && segment.runCommand() == null;
    }

    private static boolean isServerSeparator(final Segment segment) {
        return SERVER_SEPARATOR.equals(segment.text())
            && Integer.valueOf(GRAY).equals(segment.colorRgb())
            && segment.runCommand() == null;
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

    /** One flattened text fragment with its effective style and authenticated prefix marker. */
    public record Segment(
        String text,
        Integer colorRgb,
        String runCommand,
        boolean velocityAvailablePrefix
    ) {
        public Segment(final String text, final Integer colorRgb, final String runCommand) {
            this(text, colorRgb, runCommand, false);
        }
    }
}
