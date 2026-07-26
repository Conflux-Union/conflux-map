package cn.net.rms.confluxmap.mc.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.waypoint.chat.WaypointChatClickPayload;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import net.minecraft.text.Text;
import org.junit.jupiter.api.Test;

final class WaypointChatMessageRewriterTest {
    @Test
    void rewritesConfluxShareToCompactLocalizedClickablePresentation() {
        final String message = "<Alice> [Conflux Map] Home | minecraft:the_nether | "
            + "X: -12.25, Y: 70, Z: 45.5";
        final Text rewritten = WaypointChatMessageRewriter.rewrite(
            Texts.literal(message), DimensionId.OVERWORLD
        );

        // Unit tests run before the mod language resources are installed, so translatable
        // components expose their keys here. The resource assertions below lock their real text.
        assertEquals(
            "Home(-12.25,70,45.5,confluxmap.dimension.the_nether) "
                + "confluxmap.chat.waypoint.import",
            rewritten.getString()
        );
        assertEquals(2, rewritten.getSiblings().size());

        final Text action = rewritten.getSiblings().get(1);
        assertTrue(action.getStyle().isUnderlined());
        final String payload = Texts.clickValue(action.getStyle().getClickEvent());
        final WaypointChatClickPayload.Decoded decoded = WaypointChatClickPayload.decode(payload)
            .orElseThrow(AssertionError::new);
        assertEquals(message, decoded.message());
        assertEquals(DimensionId.OVERWORLD, decoded.receivedDimension());
    }

    @Test
    void leavesUnrecognizedChatTextUntouched() {
        final Text original = Texts.literal("<Alice> ordinary chat");

        assertSame(
            original,
            WaypointChatMessageRewriter.rewrite(original, DimensionId.OVERWORLD)
        );
    }

    @Test
    void languageResourcesUseTranslatedDimensionAndClickLabels() throws IOException {
        final String english = resource("/assets/confluxmap/lang/en_us.json");
        final String chinese = resource("/assets/confluxmap/lang/zh_cn.json");

        assertTrue(english.contains("\"confluxmap.dimension.the_nether\": \"The Nether\""));
        assertTrue(english.contains("\"confluxmap.chat.waypoint.import\": \"[Click to import]\""));
        assertTrue(chinese.contains("\"confluxmap.dimension.the_nether\": \"\u4e0b\u754c\""));
        assertTrue(chinese.contains("\"confluxmap.chat.waypoint.import\": \"[\u70b9\u51fb\u5bfc\u5165]\""));
    }

    private static String resource(final String path) throws IOException {
        try (InputStream stream = Objects.requireNonNull(
            WaypointChatMessageRewriterTest.class.getResourceAsStream(path), path
        )) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
