package cn.net.rms.confluxmap.mc.world;

import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.core.multiworld.VelocityServerListParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;

/** Adapts Minecraft's rendered text tree to the platform-neutral Velocity list parser. */
public final class VelocityServerTextParser {
    private VelocityServerTextParser() {
    }

    public static Optional<String> parse(final Text message) {
        final List<VelocityServerListParser.Segment> segments = new ArrayList<>();
        message.visit((style, text) -> {
            segments.add(new VelocityServerListParser.Segment(
                text,
                color(style),
                Texts.runCommandValue(style.getClickEvent())
            ));
            return Optional.empty();
        }, Style.EMPTY);
        return VelocityServerListParser.parse(segments);
    }

    private static Integer color(final Style style) {
        final TextColor color = style.getColor();
        return color == null ? null : color.getRgb();
    }
}
