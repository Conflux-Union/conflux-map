package cn.net.rms.confluxmap.mc.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.compat.Texts;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Properties;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.util.Formatting;
import org.junit.jupiter.api.Test;

class VelocityServerTextParserTest {
    @Test
    void readsStylesFromTheRenderedMinecraftComponentInsteadOfItsLanguage() {
        final MutableText message = Texts.literal("可用的服务器：")
            .formatted(Formatting.YELLOW)
            .append(Texts.literal("survival").formatted(Formatting.GREEN))
            .append(Texts.literal(", ").formatted(Formatting.GRAY))
            .append(Texts.literal("creative").setStyle(Style.EMPTY
                .withColor(Formatting.GRAY)
                .withClickEvent(Texts.runCommand("/server creative"))));

        assertEquals("survival", VelocityServerTextParser.parse(message).orElseThrow());
    }

    @Test
    void ignoresTheLocalizedCurrentServerSentence() {
        assertTrue(VelocityServerTextParser.parse(
            Texts.literal("您已连接至 survival。")
        ).isEmpty());
    }

    @Test
    void recognizesEveryBundledVelocityCurrentServerTemplate() throws IOException {
        final Properties templates = loadTemplates(
            "/assets/confluxmap/velocity_server_current.properties"
        );

        assertEquals(34, templates.size());
        for (final String template : templates.stringPropertyNames().stream()
            .map(templates::getProperty)
            .toList()) {
            assertTrue(VelocityServerTextParser.isCurrentServerNotice(
                Texts.literal(template.replace("<arg:0>", "survival"))
            ), template);
        }
    }

    @Test
    void recognizesEveryBundledVelocityAvailableServerPrefix() throws IOException {
        final Properties templates = loadTemplates(
            "/assets/confluxmap/velocity_server_available.properties"
        );

        assertEquals(34, templates.size());
        for (final String prefix : templates.stringPropertyNames().stream()
            .map(templates::getProperty)
            .toList()) {
            final MutableText message = Texts.literal(prefix).formatted(Formatting.YELLOW)
                .append(Texts.literal(" "))
                .append(Texts.literal("survival").formatted(Formatting.GREEN))
                .append(Texts.literal(", ").formatted(Formatting.GRAY))
                .append(Texts.literal("creative").setStyle(Style.EMPTY
                    .withColor(Formatting.GRAY)
                    .withClickEvent(Texts.runCommand("/server creative"))));
            assertEquals("survival", VelocityServerTextParser.parse(message).orElseThrow());
        }
    }

    @Test
    void rejectsTextThatIsNotAnExactVelocityCurrentServerNotice() {
        assertTrue(VelocityServerTextParser.isCurrentServerNotice(
            Texts.literal("您已连接至 survival。")
        ));
        assertTrue(VelocityServerTextParser.isCurrentServerNotice(
            Texts.literal("You are currently connected to creative.")
        ));
        assertTrue(VelocityServerTextParser.isCurrentServerNotice(
            Texts.literal("現在 lobby に接続しています。")
        ));

        assertFalse(VelocityServerTextParser.isCurrentServerNotice(
            Texts.literal("<Player> ordinary chat")
        ));
        assertFalse(VelocityServerTextParser.isCurrentServerNotice(
            Texts.literal("您已连接至 。")
        ));
        assertFalse(VelocityServerTextParser.isCurrentServerNotice(
            Texts.literal("您已连接至 survival。 extra")
        ));
    }

    private Properties loadTemplates(final String resource) throws IOException {
        final Properties templates = new Properties();
        try (InputStream stream = Objects.requireNonNull(
            getClass().getResourceAsStream(resource)
        ); InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            templates.load(reader);
        }
        return templates;
    }
}
