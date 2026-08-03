package cn.net.rms.confluxmap.core.multiworld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class VelocityServerListParserTest {
    private static final int GREEN = 0x55FF55;
    private static final int GRAY = 0xAAAAAA;
    private static final int YELLOW = 0xFFFF55;

    @Test
    void identifiesTheCurrentServerWithoutReadingTheLocalizedPrefix() {
        assertEquals("survival", VelocityServerListParser.parse(List.of(
            prefix("可用的服务器："),
            segment("survival", GREEN, null),
            segment(", ", GRAY, null),
            segment("creative", GRAY, "/server creative")
        )).orElseThrow());

        assertEquals("survival", VelocityServerListParser.parse(List.of(
            prefix("Available servers:"),
            segment("survival", GREEN, null),
            segment(", ", GRAY, null),
            segment("creative", GRAY, "/server creative")
        )).orElseThrow());
    }

    @Test
    void acceptsAStandardListContainingOnlyTheCurrentServer() {
        assertEquals("lobby", VelocityServerListParser.parse(List.of(
            prefix("利用可能なサーバー:"),
            segment("lobby", GREEN, null)
        )).orElseThrow());
    }

    @Test
    void rejectsOrdinaryGreenChatAndMalformedServerActions() {
        assertTrue(VelocityServerListParser.parse(List.of(
            segment("survival", GREEN, null)
        )).isEmpty());
        assertTrue(VelocityServerListParser.parse(List.of(
            prefix("Available servers:"),
            segment("survival", GREEN, null),
            segment(", ", GRAY, null),
            segment("creative", GRAY, "/warp creative")
        )).isEmpty());
        assertTrue(VelocityServerListParser.parse(List.of(
            prefix("Available servers:"),
            segment("survival", GREEN, null),
            segment(", ", GRAY, null),
            segment("creative", GREEN, null)
        )).isEmpty());
    }

    @Test
    void rejectsAMotdThatOnlyResemblesTheVelocityServerList() {
        assertTrue(VelocityServerListParser.parse(List.of(
            segment("Welcome", YELLOW, null),
            segment("lobby", GREEN, null),
            segment("creative", GRAY, "/server creative")
        )).isEmpty());
    }

    @Test
    void rejectsAMotdWithTheOfficialPrefixButANonVelocityLayout() {
        assertTrue(VelocityServerListParser.parse(List.of(
            prefix("Available servers:"),
            segment("lobby", GREEN, null),
            segment(" | ", GRAY, null),
            segment("creative", GRAY, "/server creative")
        )).isEmpty());
    }

    private static VelocityServerListParser.Segment prefix(final String text) {
        return new VelocityServerListParser.Segment(text, YELLOW, null, true);
    }

    private static VelocityServerListParser.Segment segment(
        final String text,
        final Integer color,
        final String runCommand
    ) {
        return new VelocityServerListParser.Segment(text, color, runCommand);
    }
}
