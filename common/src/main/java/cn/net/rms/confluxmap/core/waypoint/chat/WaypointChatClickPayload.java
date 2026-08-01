package cn.net.rms.confluxmap.core.waypoint.chat;

import cn.net.rms.confluxmap.core.model.DimensionId;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Private, non-command payload used by the chat import click action. The original visible message
 * is carried so it can be parsed and validated again when the user clicks it.
 */
public final class WaypointChatClickPayload {
    private static final String PREFIX = "confluxmap:waypoint-import:v1:";
    private static final int MAX_MESSAGE_BYTES = 4_096;
    private static final int MAX_DIMENSION_LENGTH = 256;
    private static final int MAX_DECODED_BYTES = MAX_DIMENSION_LENGTH + 1 + MAX_MESSAGE_BYTES;
    private static final int MAX_ENCODED_LENGTH = ((MAX_DECODED_BYTES + 2) / 3) * 4;
    private static final Pattern DIMENSION_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final Pattern BASE64_URL = Pattern.compile("[A-Za-z0-9_-]+");

    private WaypointChatClickPayload() {
    }

    /** Returns an encoded payload, or empty when the source is too large or not valid UTF-8. */
    public static Optional<String> encode(final String message, final DimensionId receivedDimension) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(receivedDimension, "receivedDimension");

        final String dimension = receivedDimension.toString();
        if (message.isEmpty() || message.length() > MAX_MESSAGE_BYTES
            || dimension.length() > MAX_DIMENSION_LENGTH
            || !DIMENSION_ID.matcher(dimension).matches()) {
            return Optional.empty();
        }

        final Optional<byte[]> messageBytes = encodeUtf8(message);
        if (!messageBytes.isPresent() || messageBytes.get().length > MAX_MESSAGE_BYTES) {
            return Optional.empty();
        }

        final byte[] dimensionBytes = dimension.getBytes(StandardCharsets.US_ASCII);
        final byte[] decoded = new byte[dimensionBytes.length + 1 + messageBytes.get().length];
        System.arraycopy(dimensionBytes, 0, decoded, 0, dimensionBytes.length);
        decoded[dimensionBytes.length] = (byte) '\n';
        System.arraycopy(messageBytes.get(), 0, decoded, dimensionBytes.length + 1, messageBytes.get().length);

        return Optional.of(PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(decoded));
    }

    /** True only for the reserved prefix; callers should consume malformed reserved payloads. */
    public static boolean hasPrivatePrefix(final String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /** Strictly decodes the reserved payload without interpreting it as a command or clipboard value. */
    public static Optional<Decoded> decode(final String value) {
        if (!hasPrivatePrefix(value)) {
            return Optional.empty();
        }

        final String encoded = value.substring(PREFIX.length());
        if (encoded.isEmpty() || encoded.length() > MAX_ENCODED_LENGTH
            || !BASE64_URL.matcher(encoded).matches()) {
            return Optional.empty();
        }

        final byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(encoded);
        } catch (final IllegalArgumentException ignored) {
            return Optional.empty();
        }
        if (decoded.length > MAX_DECODED_BYTES
            || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(encoded)) {
            return Optional.empty();
        }

        int separator = -1;
        for (int i = 0; i < decoded.length; i++) {
            if (decoded[i] == (byte) '\n') {
                separator = i;
                break;
            }
        }
        if (separator <= 0 || separator > MAX_DIMENSION_LENGTH
            || decoded.length - separator - 1 <= 0
            || decoded.length - separator - 1 > MAX_MESSAGE_BYTES) {
            return Optional.empty();
        }

        final String dimension = new String(decoded, 0, separator, StandardCharsets.US_ASCII);
        if (!DIMENSION_ID.matcher(dimension).matches()) {
            return Optional.empty();
        }

        final String message;
        try {
            message = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(decoded, separator + 1, decoded.length - separator - 1))
                .toString();
        } catch (final CharacterCodingException ignored) {
            return Optional.empty();
        }

        return Optional.of(new Decoded(message, DimensionId.parse(dimension)));
    }

    private static Optional<byte[]> encodeUtf8(final String value) {
        try {
            final ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(java.nio.CharBuffer.wrap(value));
            final byte[] result = new byte[encoded.remaining()];
            encoded.get(result);
            return Optional.of(result);
        } catch (final CharacterCodingException ignored) {
            return Optional.empty();
        }
    }

    public static final class Decoded {
        private final String message;
        private final DimensionId receivedDimension;

        private Decoded(final String message, final DimensionId receivedDimension) {
            this.message = Objects.requireNonNull(message, "message");
            this.receivedDimension = Objects.requireNonNull(receivedDimension, "receivedDimension");
        }

        public String message() {
            return message;
        }

        public DimensionId receivedDimension() {
            return receivedDimension;
        }
    }
}
