package cn.net.rms.confluxmap.core.waypoint.chat;

import cn.net.rms.confluxmap.core.model.DimensionId;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formats waypoints for chat and extracts one explicitly labelled coordinate candidate from an
 * incoming chat line. This class deliberately has no dependency on Minecraft chat classes so the
 * protocol and its input validation can be tested in isolation.
 */
public final class WaypointChatCodec {
    public static final int MAX_CHAT_LENGTH = 256;
    public static final double MAX_HORIZONTAL_COORDINATE = 30_000_000.0;
    public static final double MIN_VERTICAL_COORDINATE = -2_048.0;
    public static final double MAX_VERTICAL_COORDINATE = 2_048.0;

    private static final String MARKER = "[Conflux Map]";
    private static final String PREFIX = MARKER + " ";
    private static final String XAERO_MARKER = "xaero-waypoint:";
    private static final int XAERO_MAX_NAME_LENGTH = 32;
    private static final int[] XAERO_COLORS = {
        0x000000, 0x0000AA, 0x00AA00, 0x00AAAA,
        0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
        0x555555, 0x5555FF, 0x55FF55, 0x55FFFF,
        0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
    };
    private static final String DIMENSION_ID = "[a-z0-9_.-]+:[a-z0-9/._-]+";
    private static final String NUMBER = "[+-]?(?:(?:\\d+(?:\\.\\d*)?)|(?:\\.\\d+))(?:[eE][+-]?\\d+)?";
    private static final String SEPARATOR = "(?:\\s*,\\s*|\\s+)";

    private static final Pattern DIMENSION_ID_PATTERN = Pattern.compile(DIMENSION_ID);
    private static final Pattern CONFLUX_MESSAGE_PATTERN = Pattern.compile(
        "^.*?\\Q" + PREFIX + "\\E(.*) \\| (" + DIMENSION_ID + ") \\| (.*)$"
    );
    private static final Pattern COORDINATE_PATTERN = Pattern.compile(
        "(?<![A-Za-z0-9_])[xX]\\s*[:=]\\s*(" + NUMBER + ")" + SEPARATOR
            + "[yY]\\s*[:=]\\s*(" + NUMBER + ")" + SEPARATOR
            + "[zZ]\\s*[:=]\\s*(" + NUMBER + ")\\s*$"
    );
    private static final Pattern COORDINATE_LABEL_PATTERN = Pattern.compile(
        "(?<![A-Za-z0-9_])([xXyYzZ])\\s*[:=]"
    );
    private static final Pattern XAERO_MESSAGE_PATTERN = Pattern.compile(
        "^.*?\\Q" + XAERO_MARKER + "\\E([^:\\r\\n]{1,32}):([^:\\r\\n]{1,2}):"
            + "([+-]?\\d+):([+-]?\\d+):([+-]?\\d+):([+-]?\\d+):"
            + "(true|false):(" + NUMBER + ")(?::([^:\\r\\n]+))?$",
        Pattern.CASE_INSENSITIVE
    );

    private WaypointChatCodec() {
    }

    /**
     * Produces the stable Conflux Map chat representation. Coordinates must be finite and within
     * the same bounds accepted by {@link #parse(String, DimensionId)}. If the fixed protocol fields
     * alone cannot fit in a chat message, the input is rejected rather than emitting a lossy
     * dimension id or coordinate.
     */
    public static String format(
        final String name,
        final DimensionId dimensionId,
        final double x,
        final double y,
        final double z
    ) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(dimensionId, "dimensionId");
        requireValidCoordinates(x, y, z);

        final String dimension = dimensionId.toString();
        if (!DIMENSION_ID_PATTERN.matcher(dimension).matches()) {
            throw new IllegalArgumentException("Invalid dimension id: " + dimension);
        }

        final String suffix = " | " + dimension + " | X: " + formatCoordinate(x)
            + ", Y: " + formatCoordinate(y) + ", Z: " + formatCoordinate(z);
        final int availableNameLength = MAX_CHAT_LENGTH - PREFIX.length() - suffix.length();
        if (availableNameLength < 0) {
            throw new IllegalArgumentException("Waypoint fields exceed the chat message limit");
        }

        final String safeName = truncateUtf16(sanitizeText(name), availableNameLength);
        return PREFIX + safeName + suffix;
    }

    /**
     * Produces the Xaero chat-sharing representation. Xaero stores waypoint positions as block
     * coordinates, so fractional values are floored in the same way as Minecraft block positions.
     * Unknown dimensions omit Xaero's optional world id and are therefore imported into the
     * receiver's current dimension instead of inventing an incompatible identifier.
     */
    public static String formatXaero(
        final String name,
        final DimensionId dimensionId,
        final double x,
        final double y,
        final double z,
        final int colorArgb
    ) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(dimensionId, "dimensionId");
        requireValidCoordinates(x, y, z);

        String safeName = sanitizeText(name).replace(':', '_');
        safeName = truncateUtf16(safeName, XAERO_MAX_NAME_LENGTH);
        if (safeName.isEmpty()) {
            safeName = "Waypoint";
        }
        final int firstCodePointLength = Character.charCount(safeName.codePointAt(0));
        final String marker = safeName.substring(0, firstCodePointLength);
        final String dimension = xaeroDimension(dimensionId);
        final String message = XAERO_MARKER + safeName + ":" + marker
            + ":" + floorCoordinate(x) + ":" + floorCoordinate(y) + ":" + floorCoordinate(z)
            + ":" + nearestXaeroColor(colorArgb) + ":false:0"
            + (dimension.isEmpty() ? "" : ":" + dimension);
        if (message.length() > MAX_CHAT_LENGTH) {
            throw new IllegalArgumentException("Xaero waypoint exceeds the chat message limit");
        }
        return message;
    }

    /**
     * Parses either a Conflux Map message or a strict X/Y/Z labelled coordinate suffix. A regular
     * labelled message is associated with the dimension in which it was received and intentionally
     * has an empty name so the create-waypoint UI can require the user to supply one.
     */
    public static Optional<Candidate> parse(final String message, final DimensionId receivedDimension) {
        Objects.requireNonNull(receivedDimension, "receivedDimension");
        if (message == null || message.isEmpty()) {
            return Optional.empty();
        }

        final String safeMessage = sanitizeText(message);
        if (safeMessage.contains(MARKER)) {
            return parseConfluxMessage(safeMessage);
        }
        if (safeMessage.contains(XAERO_MARKER)) {
            return parseXaeroMessage(safeMessage, receivedDimension);
        }

        final ParsedCoordinates coordinates = parseCoordinates(safeMessage, false);
        if (coordinates == null) {
            return Optional.empty();
        }
        return Optional.of(new Candidate(
            "", receivedDimension, coordinates.x, coordinates.y, coordinates.z, false
        ));
    }

    private static Optional<Candidate> parseConfluxMessage(final String message) {
        final Matcher messageMatcher = CONFLUX_MESSAGE_PATTERN.matcher(message);
        if (!messageMatcher.matches()) {
            return Optional.empty();
        }

        final ParsedCoordinates coordinates = parseCoordinates(messageMatcher.group(3), true);
        if (coordinates == null) {
            return Optional.empty();
        }

        final DimensionId dimension = DimensionId.parse(messageMatcher.group(2));
        return Optional.of(new Candidate(
            sanitizeText(messageMatcher.group(1)), dimension,
            coordinates.x, coordinates.y, coordinates.z, true
        ));
    }

    private static Optional<Candidate> parseXaeroMessage(
        final String message,
        final DimensionId receivedDimension
    ) {
        final Matcher matcher = XAERO_MESSAGE_PATTERN.matcher(message);
        if (!matcher.matches()) {
            return Optional.empty();
        }

        try {
            final double x = Double.parseDouble(matcher.group(3));
            final double y = Double.parseDouble(matcher.group(4));
            final double z = Double.parseDouble(matcher.group(5));
            final int color = Integer.parseInt(matcher.group(6));
            final double yaw = Double.parseDouble(matcher.group(8));
            if (!validCoordinates(x, y, z) || color < 0 || color >= XAERO_COLORS.length
                || !Double.isFinite(yaw)) {
                return Optional.empty();
            }

            final Optional<DimensionId> dimension = parseXaeroDimension(
                matcher.group(9), receivedDimension
            );
            if (!dimension.isPresent()) {
                return Optional.empty();
            }
            return Optional.of(new Candidate(
                matcher.group(1), dimension.get(), x, y, z, false
            ));
        } catch (final NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<DimensionId> parseXaeroDimension(
        final String value,
        final DimensionId receivedDimension
    ) {
        if (value == null || value.isEmpty()) {
            return Optional.of(receivedDimension);
        }
        final String normalized = value.toLowerCase(java.util.Locale.ROOT);
        switch (normalized) {
            case "internal-overworld":
            case "internal-overworld-waypoints":
                return Optional.of(DimensionId.OVERWORLD);
            case "internal-the-nether":
            case "internal-the-nether-waypoints":
                return Optional.of(DimensionId.NETHER);
            case "internal-the-end":
            case "internal-the-end-waypoints":
                return Optional.of(DimensionId.END);
            default:
                return Optional.empty();
        }
    }

    private static String xaeroDimension(final DimensionId dimensionId) {
        if (DimensionId.OVERWORLD.equals(dimensionId)) {
            return "Internal-overworld-waypoints";
        }
        if (DimensionId.NETHER.equals(dimensionId)) {
            return "Internal-the-nether-waypoints";
        }
        if (DimensionId.END.equals(dimensionId)) {
            return "Internal-the-end-waypoints";
        }
        return "";
    }

    private static long floorCoordinate(final double value) {
        return (long) Math.floor(value);
    }

    private static int nearestXaeroColor(final int colorArgb) {
        final int red = colorArgb >>> 16 & 0xFF;
        final int green = colorArgb >>> 8 & 0xFF;
        final int blue = colorArgb & 0xFF;
        int nearest = 0;
        long nearestDistance = Long.MAX_VALUE;
        for (int i = 0; i < XAERO_COLORS.length; i++) {
            final int candidate = XAERO_COLORS[i];
            final int redDelta = red - (candidate >>> 16 & 0xFF);
            final int greenDelta = green - (candidate >>> 8 & 0xFF);
            final int blueDelta = blue - (candidate & 0xFF);
            final long distance = (long) redDelta * redDelta
                + (long) greenDelta * greenDelta
                + (long) blueDelta * blueDelta;
            if (distance < nearestDistance) {
                nearest = i;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private static ParsedCoordinates parseCoordinates(final String text, final boolean mustStartAtBeginning) {
        final Matcher coordinateMatcher = COORDINATE_PATTERN.matcher(text);
        if (!coordinateMatcher.find() || (mustStartAtBeginning && coordinateMatcher.start() != 0)) {
            return null;
        }
        if (!containsExactlyOneOfEachCoordinateLabel(text)) {
            return null;
        }

        try {
            final double x = Double.parseDouble(coordinateMatcher.group(1));
            final double y = Double.parseDouble(coordinateMatcher.group(2));
            final double z = Double.parseDouble(coordinateMatcher.group(3));
            return validCoordinates(x, y, z) ? new ParsedCoordinates(x, y, z) : null;
        } catch (final NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean containsExactlyOneOfEachCoordinateLabel(final String text) {
        int xLabels = 0;
        int yLabels = 0;
        int zLabels = 0;
        final Matcher matcher = COORDINATE_LABEL_PATTERN.matcher(text);
        while (matcher.find()) {
            switch (Character.toLowerCase(matcher.group(1).charAt(0))) {
                case 'x': xLabels++; break;
                case 'y': yLabels++; break;
                case 'z': zLabels++; break;
                default: throw new IllegalStateException("Unexpected coordinate label");
            }
        }
        return xLabels == 1 && yLabels == 1 && zLabels == 1;
    }

    private static void requireValidCoordinates(final double x, final double y, final double z) {
        if (!validCoordinates(x, y, z)) {
            throw new IllegalArgumentException("Coordinates must be finite and within supported bounds");
        }
    }

    private static boolean validCoordinates(final double x, final double y, final double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
            && Math.abs(x) <= MAX_HORIZONTAL_COORDINATE
            && y >= MIN_VERTICAL_COORDINATE && y <= MAX_VERTICAL_COORDINATE
            && Math.abs(z) <= MAX_HORIZONTAL_COORDINATE;
    }

    private static String formatCoordinate(final double value) {
        if (value == 0.0) {
            return "0";
        }
        final String plain = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        if (plain.length() <= 24) {
            return plain;
        }

        final String scientific = Double.toString(value);
        final int redundantDecimal = scientific.indexOf(".0E");
        return redundantDecimal < 0
            ? scientific
            : scientific.substring(0, redundantDecimal) + scientific.substring(redundantDecimal + 2);
    }

    private static String sanitizeText(final String input) {
        final StringBuilder safe = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            final char current = input.charAt(i);
            if (current == '\u00a7') {
                if (i + 1 < input.length() && isLegacyFormattingCode(input.charAt(i + 1))) {
                    i++;
                }
                continue;
            }
            if (current <= '\u001f' || (current >= '\u007f' && current <= '\u009f')) {
                continue;
            }
            if (Character.isHighSurrogate(current)) {
                if (i + 1 < input.length() && Character.isLowSurrogate(input.charAt(i + 1))) {
                    safe.append(current).append(input.charAt(++i));
                }
                continue;
            }
            if (!Character.isLowSurrogate(current)) {
                safe.append(current);
            }
        }
        return safe.toString();
    }

    private static boolean isLegacyFormattingCode(final char value) {
        final char lower = Character.toLowerCase(value);
        return (lower >= '0' && lower <= '9') || (lower >= 'a' && lower <= 'f')
            || (lower >= 'k' && lower <= 'o') || lower == 'r' || lower == 'x';
    }

    private static String truncateUtf16(final String value, final int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        int end = maxLength;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    /** One validated coordinate candidate, immutable and usable on Java 8 and later. */
    public static final class Candidate {
        private final String name;
        private final DimensionId dimensionId;
        private final double x;
        private final double y;
        private final double z;
        private final boolean confluxFormat;

        private Candidate(
            final String name,
            final DimensionId dimensionId,
            final double x,
            final double y,
            final double z,
            final boolean confluxFormat
        ) {
            this.name = Objects.requireNonNull(name, "name");
            this.dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
            requireValidCoordinates(x, y, z);
            this.x = x;
            this.y = y;
            this.z = z;
            this.confluxFormat = confluxFormat;
        }

        public String name() {
            return name;
        }

        public DimensionId dimensionId() {
            return dimensionId;
        }

        public double x() {
            return x;
        }

        public double y() {
            return y;
        }

        public double z() {
            return z;
        }

        public boolean confluxFormat() {
            return confluxFormat;
        }
    }

    private static final class ParsedCoordinates {
        private final double x;
        private final double y;
        private final double z;

        private ParsedCoordinates(final double x, final double y, final double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
