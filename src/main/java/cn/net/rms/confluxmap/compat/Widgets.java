package cn.net.rms.confluxmap.compat;

import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/** Small construction and layout seam for widget API changes across Minecraft versions. */
public final class Widgets {
    private Widgets() {
    }

    public static ButtonWidget button(
        final int x,
        final int y,
        final int width,
        final int height,
        final Text message,
        final ButtonWidget.PressAction onPress
    ) {
        //#if MC>=11904
        //$$ return ButtonWidget.builder(message, onPress).dimensions(x, y, width, height).build();
        //#else
        return new ButtonWidget(x, y, width, height, message, onPress);
        //#endif
    }

    public static int x(final ClickableWidget widget) {
        //#if MC>=11904
        //$$ return widget.getX();
        //#else
        return widget.x;
        //#endif
    }

    public static int y(final ClickableWidget widget) {
        //#if MC>=11904
        //$$ return widget.getY();
        //#else
        return widget.y;
        //#endif
    }

    public static void tick(final TextFieldWidget field) {
        //#if MC<11904
        field.tick();
        //#endif
    }
}
