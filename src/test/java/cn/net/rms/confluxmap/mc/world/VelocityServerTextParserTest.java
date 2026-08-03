package cn.net.rms.confluxmap.mc.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.compat.Texts;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.util.Formatting;
import org.junit.jupiter.api.Test;

class VelocityServerTextParserTest {
    @Test
    void readsStylesFromTheRenderedMinecraftComponentInsteadOfItsLanguage() {
        final MutableText message = Texts.literal("当前可用的服务器：")
            .formatted(Formatting.YELLOW)
            .append(Texts.literal("survival").formatted(Formatting.GREEN))
            .append(Texts.literal(" "))
            .append(Texts.literal("creative").setStyle(Style.EMPTY
                .withColor(Formatting.GRAY)
                .withClickEvent(Texts.runCommand("/server creative"))));

        assertEquals("survival", VelocityServerTextParser.parse(message).orElseThrow());
    }

    @Test
    void ignoresTheLocalizedCurrentServerSentence() {
        assertTrue(VelocityServerTextParser.parse(
            Texts.literal("你当前连接到 survival。")
        ).isEmpty());
    }
}
