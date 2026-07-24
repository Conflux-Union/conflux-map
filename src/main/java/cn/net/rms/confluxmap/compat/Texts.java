package cn.net.rms.confluxmap.compat;

//#if MC>=12105
//$$ import java.net.URI;
//#endif
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

//#if MC<11900
import net.minecraft.text.LiteralText;
import net.minecraft.text.TranslatableText;
//#endif

/**
 * The one place that knows how this Minecraft version builds a text component.
 *
 * <p>1.19 deleted {@code LiteralText}/{@code TranslatableText} in favour of the {@code Text}
 * static factories. That rename reaches ~135 call sites across the UI, chat and command code, so
 * routing every one of them through these two methods keeps the version fork to a single seam
 * instead of scattering preprocessor branches over sixteen files.
 */
public final class Texts {
    private Texts() {
    }

    /** A translated component for {@code key}, with optional format arguments. */
    public static MutableText translatable(final String key, final Object... args) {
        //#if MC>=11900
        //$$ return Text.translatable(key, args);
        //#else
        return new TranslatableText(key, args);
        //#endif
    }

    /** A literal, untranslated component. */
    public static MutableText literal(final String text) {
        //#if MC>=11900
        //$$ return Text.literal(text);
        //#else
        return new LiteralText(text);
        //#endif
    }

    public static ClickEvent copyToClipboard(final String value) {
        //#if MC>=12105
        //$$ return new ClickEvent.CopyToClipboard(value);
        //#else
        return new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, value);
        //#endif
    }

    public static ClickEvent openUrl(final String value) {
        //#if MC>=12105
        //$$ return new ClickEvent.OpenUrl(URI.create(value));
        //#else
        return new ClickEvent(ClickEvent.Action.OPEN_URL, value);
        //#endif
    }

    public static HoverEvent showText(final Text value) {
        //#if MC>=12105
        //$$ return new HoverEvent.ShowText(value);
        //#else
        return new HoverEvent(HoverEvent.Action.SHOW_TEXT, value);
        //#endif
    }

    /** String payload carried by a click event, or null for event variants without one. */
    public static String clickValue(final ClickEvent event) {
        //#if MC>=12105
        //$$ return event instanceof ClickEvent.CopyToClipboard copy ? copy.value() : null;
        //#else
        return event.getValue();
        //#endif
    }
}
