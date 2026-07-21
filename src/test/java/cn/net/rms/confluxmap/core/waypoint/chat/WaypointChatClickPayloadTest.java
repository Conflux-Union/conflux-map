package cn.net.rms.confluxmap.core.waypoint.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class WaypointChatClickPayloadTest {
    @Test
    void roundTripsMessageAndReceiveTimeDimensionForRevalidation() {
        final String message = "<Alice> X: -12.5, Y: 64, Z: 3.25";
        final String payload = WaypointChatClickPayload.encode(message, DimensionId.END)
            .orElseThrow(AssertionError::new);

        assertTrue(WaypointChatClickPayload.hasPrivatePrefix(payload));
        final WaypointChatClickPayload.Decoded decoded = WaypointChatClickPayload.decode(payload)
            .orElseThrow(AssertionError::new);
        assertEquals(message, decoded.message());
        assertEquals(DimensionId.END, decoded.receivedDimension());

        final WaypointChatCodec.Candidate candidate = WaypointChatCodec.parse(
            decoded.message(), decoded.receivedDimension()
        ).orElseThrow(AssertionError::new);
        assertEquals(DimensionId.END, candidate.dimensionId());
        assertEquals(-12.5, candidate.x());
    }

    @Test
    void preservesUnicodeAndNewlinesWithoutUsingRawClipboardText() {
        final String message = "玩家：主城\nX: 1, Y: 70, Z: -2";
        final String payload = WaypointChatClickPayload.encode(message, DimensionId.NETHER)
            .orElseThrow(AssertionError::new);

        assertFalse(payload.contains(message));
        assertEquals(
            message,
            WaypointChatClickPayload.decode(payload).orElseThrow(AssertionError::new).message()
        );
    }

    @Test
    void rejectsMalformedReservedPayloads() {
        final String valid = WaypointChatClickPayload.encode(
            "X: 1, Y: 2, Z: 3", DimensionId.OVERWORLD
        ).orElseThrow(AssertionError::new);
        final String prefix = valid.substring(0, valid.lastIndexOf(':') + 1);

        assertTrue(WaypointChatClickPayload.hasPrivatePrefix(prefix + "not-base64!"));
        assertFalse(WaypointChatClickPayload.decode(prefix + "not-base64!").isPresent());
        assertFalse(WaypointChatClickPayload.decode(valid + "=").isPresent());
        assertFalse(WaypointChatClickPayload.decode(prefix + encodeRaw("invalid dimension\nX: 1, Y: 2, Z: 3"))
            .isPresent());
        assertFalse(WaypointChatClickPayload.decode(prefix + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(new byte[] {'m', ':', 'd', '\n', (byte) 0xC3, 0x28})).isPresent());
    }

    @Test
    void rejectsOversizedOrMalformedSourceText() {
        assertFalse(WaypointChatClickPayload.encode(
            repeat("a", 4_097), DimensionId.OVERWORLD
        ).isPresent());
        assertFalse(WaypointChatClickPayload.encode(
            "bad\uD800text", DimensionId.OVERWORLD
        ).isPresent());
    }

    private static String encodeRaw(final String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String repeat(final String value, final int count) {
        final StringBuilder repeated = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            repeated.append(value);
        }
        return repeated.toString();
    }
}
