package cn.net.rms.confluxmap.compat;

import net.minecraft.text.MutableText;

//#if MC>=11900
//$$ import net.minecraft.text.Text;
//#else
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
}
