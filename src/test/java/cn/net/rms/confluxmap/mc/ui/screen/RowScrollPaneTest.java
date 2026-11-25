package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.compat.Texts;
//#if MC<260100
import net.minecraft.client.gui.widget.TextFieldWidget;
//#endif
import org.junit.jupiter.api.Test;

final class RowScrollPaneTest {
    private static final int CONTENT_HEIGHT = 1000;
    private static final int VIEWPORT_HEIGHT = 200;
    /** Both screens clamp the offset to {@code [0, max(0, content - viewport)]}. */
    private static final int MAX_SCROLL = CONTENT_HEIGHT - VIEWPORT_HEIGHT;

    /** A focus stand-in no screen would confuse with a text field. */
    private static final Object NOTHING_FOCUSED = null;

    //#if MC<260100
    private static Object focusedTextField() {
        // The constructor never dereferences the renderer; the headless test JVM has none.
        return new TextFieldWidget(null, 0, 0, 10, 10, Texts.literal(""));
    }
    //#else
    //$$ private static Object focusedTextField() throws Exception {
    //$$     // Allocated, not constructed: the headless JVM has no font to hand the constructor,
    //$$     // and the check under test only inspects the runtime type.
    //$$     final var theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
    //$$     theUnsafe.setAccessible(true);
    //$$     final var unsafe = theUnsafe.get(null);
    //$$     final var allocate = unsafe.getClass().getMethod("allocateInstance", Class.class);
    //$$     final Class<?> editBox = Class.forName("net.minecraft.client.gui.components.EditBox");
    //$$     return editBox.cast(allocate.invoke(unsafe, editBox));
    //$$ }
    //#endif

    @Test
    void scrollIsIgnoredWhileATextFieldHasFocus() throws Exception {
        final RowScrollPane pane = new RowScrollPane(ConfigScreen.ROW_HEIGHT);
        pane.scrollOffset(44);

        assertTrue(pane.blocksScroll(focusedTextField()));
        assertFalse(pane.blocksScroll(NOTHING_FOCUSED));
        assertEquals(44, pane.scrollOffset());
    }

    @Test
    void scrollIsIgnoredWhenTheContentFits() {
        final RowScrollPane pane = new RowScrollPane(ConfigScreen.ROW_HEIGHT);

        assertFalse(pane.scrollBy(-1, VIEWPORT_HEIGHT, VIEWPORT_HEIGHT));
        assertEquals(0, pane.scrollOffset());
        assertFalse(pane.scrollBy(1, VIEWPORT_HEIGHT, VIEWPORT_HEIGHT));
        assertEquals(0, pane.scrollOffset());
    }

    @Test
    void scrollMovesByTheSharedStepAndClampsAtBothEnds() {
        final RowScrollPane pane = new RowScrollPane(ConfigScreen.ROW_HEIGHT);

        // Clamped at the bottom edge before any row has scrolled past.
        assertFalse(pane.scrollBy(1, CONTENT_HEIGHT, VIEWPORT_HEIGHT));
        assertEquals(0, pane.scrollOffset());

        // One step per wheel notch, against the sign of the amount.
        assertTrue(pane.scrollBy(-1, CONTENT_HEIGHT, VIEWPORT_HEIGHT));
        assertEquals(ConfigScreen.ROW_HEIGHT, pane.scrollOffset());
        assertTrue(pane.scrollBy(1, CONTENT_HEIGHT, VIEWPORT_HEIGHT));
        assertEquals(0, pane.scrollOffset());

        pane.scrollOffset(MAX_SCROLL);
        // Clamped at the top edge once the last row is on screen.
        assertFalse(pane.scrollBy(-1, CONTENT_HEIGHT, VIEWPORT_HEIGHT));
        assertEquals(MAX_SCROLL, pane.scrollOffset());
        assertTrue(pane.scrollBy(1, CONTENT_HEIGHT, VIEWPORT_HEIGHT));
        assertEquals(MAX_SCROLL - ConfigScreen.ROW_HEIGHT, pane.scrollOffset());
    }

    @Test
    void offsetIsPreservedWhenThePaneIsAskedToScrollByZero() {
        final RowScrollPane pane = new RowScrollPane(ConfigScreen.ROW_HEIGHT);
        pane.scrollOffset(44);

        assertFalse(pane.scrollBy(0, CONTENT_HEIGHT, VIEWPORT_HEIGHT));
        assertEquals(44, pane.scrollOffset());
        // A zero wheel amount never consumes the event; the screen falls through to super.
        assertFalse(pane.consumesScroll(30, 30, 0, 20, 100, 10, 60));
    }
}
