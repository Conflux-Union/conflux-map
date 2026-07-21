package cn.net.rms.confluxmap.core.waypoint.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WaypointChatCodecTest {
    @Test
    void formatsStableProtocolWithReadableNecessaryDecimals() {
        assertEquals(
            "[Conflux Map] \u5bb6 | minecraft:overworld | X: -12, Y: 64.5, Z: 0.0000001",
            WaypointChatCodec.format("\u5bb6", DimensionId.OVERWORLD, -12.0, 64.5, 0.0000001)
        );
    }

    @Test
    void stripsControlCharactersAndMinecraftFormattingFromName() {
        final String formatted = WaypointChatCodec.format(
            "Ho\u0000me\n\u00a7cRed\u0085\u00a7tail", DimensionId.OVERWORLD, 1.0, 2.0, 3.0
        );

        assertEquals(
            "[Conflux Map] HomeRedtail | minecraft:overworld | X: 1, Y: 2, Z: 3",
            formatted
        );
    }

    @Test
    void truncatesUnicodeNameToChatLimitWithoutSplittingSurrogatePair() {
        final String emptyNameMessage = WaypointChatCodec.format(
            "", DimensionId.OVERWORLD, 1.0, 2.0, 3.0
        );
        final int nameCapacity = WaypointChatCodec.MAX_CHAT_LENGTH - emptyNameMessage.length();
        final String namePrefix = repeat("\u5750", nameCapacity - 1);
        final String name = namePrefix + "\ud83d\ude00tail";

        final String formatted = WaypointChatCodec.format(
            name, DimensionId.OVERWORLD, 1.0, 2.0, 3.0
        );
        final WaypointChatCodec.Candidate parsed = WaypointChatCodec.parse(
            formatted, DimensionId.NETHER
        ).orElseThrow(AssertionError::new);

        assertTrue(formatted.length() <= WaypointChatCodec.MAX_CHAT_LENGTH);
        assertEquals(namePrefix, parsed.name());
        assertFalse(parsed.name().endsWith("\ud83d"));
    }

    @Test
    void rejectsInvalidCoordinatesWhileFormatting() {
        assertThrows(IllegalArgumentException.class, () -> WaypointChatCodec.format(
            "bad", DimensionId.OVERWORLD, Double.NaN, 64.0, 0.0
        ));
        assertThrows(IllegalArgumentException.class, () -> WaypointChatCodec.format(
            "bad", DimensionId.OVERWORLD, 30_000_001.0, 64.0, 0.0
        ));
        assertThrows(IllegalArgumentException.class, () -> WaypointChatCodec.format(
            "bad", DimensionId.OVERWORLD, 0.0, 2_049.0, 0.0
        ));
    }

    @Test
    void parsesConfluxMessageAfterSenderPrefixAndKeepsItsMetadata() {
        final String shared = WaypointChatCodec.format(
            "\u4e3b\u57ce | \u5317\u95e8", DimensionId.NETHER, -12.25, 70.0, 45.5
        );

        final WaypointChatCodec.Candidate candidate = WaypointChatCodec.parse(
            "<\u00a7aAlice\u00a7r> " + shared, DimensionId.OVERWORLD
        ).orElseThrow(AssertionError::new);

        assertEquals("\u4e3b\u57ce | \u5317\u95e8", candidate.name());
        assertEquals(DimensionId.NETHER, candidate.dimensionId());
        assertEquals(-12.25, candidate.x());
        assertEquals(70.0, candidate.y());
        assertEquals(45.5, candidate.z());
        assertTrue(candidate.confluxFormat());
    }

    @Test
    void parsesStrictGenericLabelsUsingReceiveTimeDimensionAndEmptyName() {
        final Optional<WaypointChatCodec.Candidate> parsed = WaypointChatCodec.parse(
            "Alice: x=-12.5 Y: 64, z=+3.25", DimensionId.END
        );

        assertTrue(parsed.isPresent());
        assertEquals("", parsed.get().name());
        assertEquals(DimensionId.END, parsed.get().dimensionId());
        assertEquals(-12.5, parsed.get().x());
        assertEquals(64.0, parsed.get().y());
        assertEquals(3.25, parsed.get().z());
        assertFalse(parsed.get().confluxFormat());
    }

    @Test
    void parsesXaeroMessageFromScreenshotAndKeepsItsMetadata() {
        final WaypointChatCodec.Candidate candidate = WaypointChatCodec.parse(
            "<Hurrybiu1016> xaero-waypoint:\u5bf9\u65b9\u7684:\u5bf9:-113:84:-118:7:false:0:Internal-overworld-waypoints",
            DimensionId.NETHER
        ).orElseThrow(AssertionError::new);

        assertEquals("\u5bf9\u65b9\u7684", candidate.name());
        assertEquals(DimensionId.OVERWORLD, candidate.dimensionId());
        assertEquals(-113.0, candidate.x());
        assertEquals(84.0, candidate.y());
        assertEquals(-118.0, candidate.z());
        assertFalse(candidate.confluxFormat());
    }

    @Test
    void parsesXaeroVanillaDimensionsAndFallsBackWhenWorldIdIsOmitted() {
        assertEquals(DimensionId.NETHER, parseXaeroDimension("Internal-the-nether-waypoints", DimensionId.END));
        assertEquals(DimensionId.END, parseXaeroDimension("Internal-the-end", DimensionId.OVERWORLD));
        assertEquals(DimensionId.END, parseXaeroDimension(null, DimensionId.END));
    }

    @Test
    void formatsXaeroMessageWithSafeFieldsBlockCoordinatesAndNearestColor() {
        assertEquals(
            "xaero-waypoint:Home_Base:H:-114:64:3:12:false:0:Internal-the-nether-waypoints",
            WaypointChatCodec.formatXaero(
                "Home:Base", DimensionId.NETHER, -113.1, 64.9, 3.0, 0xFFE74C3C
            )
        );
    }

    @Test
    void formatsUnknownXaeroDimensionWithoutInventingAWorldId() {
        assertEquals(
            "xaero-waypoint:Portal:P:1:2:3:3:false:0",
            WaypointChatCodec.formatXaero(
                "Portal", DimensionId.of("mod", "moon"), 1.0, 2.0, 3.0, 0xFF3498DB
            )
        );
    }

    @Test
    void rejectsMalformedXaeroMessagesWithoutGenericFallback() {
        assertRejected("xaero-waypoint:Home:H:1:2:3:16:false:0:Internal-overworld-waypoints");
        assertRejected("xaero-waypoint:Home:H:1:2:3:7:false:0:Unknown-world");
        assertRejected("xaero-waypoint:Home:H:1:2:3:7:false:NaN:Internal-overworld-waypoints");
        assertRejected("xaero-waypoint:Home:TOO:1:2:3:7:false:0:Internal-overworld-waypoints");
    }

    @Test
    void acceptsInclusiveCoordinateBoundaries() {
        final WaypointChatCodec.Candidate candidate = WaypointChatCodec.parse(
            "X: -30000000, Y: -2048, Z: 30000000", DimensionId.OVERWORLD
        ).orElseThrow(AssertionError::new);

        assertEquals(-30_000_000.0, candidate.x());
        assertEquals(-2_048.0, candidate.y());
        assertEquals(30_000_000.0, candidate.z());
        assertTrue(WaypointChatCodec.parse(
            "X: 0, Y: 2048, Z: 0", DimensionId.OVERWORLD
        ).isPresent());
    }

    @Test
    void rejectsNonFiniteAndOutOfBoundsCoordinates() {
        assertRejected("X: NaN, Y: 64, Z: 0");
        assertRejected("X: Infinity, Y: 64, Z: 0");
        assertRejected("X: 1e309, Y: 64, Z: 0");
        assertRejected("X: 30000000.01, Y: 64, Z: 0");
        assertRejected("X: 0, Y: -2048.01, Z: 0");
        assertRejected("X: 0, Y: 2048.01, Z: 0");
        assertRejected("X: 0, Y: 64, Z: -30000000.01");
    }

    @Test
    void rejectsDuplicateOrOutOfOrderLabels() {
        assertRejected("X: 1, X: 2, Y: 3, Z: 4");
        assertRejected("X: 1, Y: 2, Z: 3, Z: 4");
        assertRejected("Y: 2, X: 1, Z: 3");
        assertRejected("Z: 3, Y: 2, X: 1");
    }

    @Test
    void rejectsBareTriplesAndTrailingCoordinateGarbage() {
        assertRejected("Meet at -12.5, 64, 3.25");
        assertRejected("X: 1, Y: 2, Z: 3, W: 4");
        assertRejected("X: 1, Y: 2, Z: 3 4");
        assertRejected("X: 1, Y: 2, Z: 3oops");
        assertRejected("prefixX: 1, Y: 2, Z: 3");
    }

    @Test
    void malformedConfluxMarkerDoesNotFallBackToGenericParsing() {
        assertRejected("[Conflux Map] Home | INVALID DIMENSION | X: 1, Y: 2, Z: 3");
        assertRejected("[Conflux Map] Home | minecraft:overworld | X: 1, Y: 2, Z: 3 extra");
    }

    private static void assertRejected(final String message) {
        assertFalse(WaypointChatCodec.parse(message, DimensionId.OVERWORLD).isPresent(), message);
    }

    private static DimensionId parseXaeroDimension(
        final String dimension,
        final DimensionId receivedDimension
    ) {
        final String suffix = dimension == null ? "" : ":" + dimension;
        return WaypointChatCodec.parse(
            "xaero-waypoint:Home:H:1:2:3:7:false:0" + suffix,
            receivedDimension
        ).orElseThrow(AssertionError::new).dimensionId();
    }

    private static String repeat(final String value, final int count) {
        final StringBuilder repeated = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            repeated.append(value);
        }
        return repeated.toString();
    }
}
