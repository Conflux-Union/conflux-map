package cn.net.rms.confluxmap.mc.ui.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.waypoint.WaypointDescription;
import org.junit.jupiter.api.Test;

class MultilineTextFieldStateTest {
    private final MultilineTextField.State state = new MultilineTextField.State();

    @Test
    void resetSplitsLinesAndPlacesTheCursorAtHome() {
        state.reset("a\nbc\n");
        assertEquals(3, state.lines().size());
        assertEquals("a", state.lines().get(0));
        assertEquals("", state.lines().get(2));
        assertEquals(0, state.cursorLine());
        assertEquals(0, state.cursorColumn());
    }

    @Test
    void typingAdvancesTheCursorAndNewlinesSplit() {
        state.insert('a', WaypointDescription.MAX_CODE_POINTS);
        state.insert('b', WaypointDescription.MAX_CODE_POINTS);
        state.insert('\n', WaypointDescription.MAX_CODE_POINTS);
        state.insert('c', WaypointDescription.MAX_CODE_POINTS);
        assertEquals("ab\nc", state.text());
        assertEquals(1, state.cursorLine());
        assertEquals(1, state.cursorColumn());
    }

    @Test
    void lineCapRejectsAdditionalNewlinesButKeepsEditing() {
        for (int i = 0; i < WaypointDescription.MAX_LINES - 1; i++) {
            state.insert('\n', WaypointDescription.MAX_CODE_POINTS);
        }
        assertEquals(WaypointDescription.MAX_LINES, state.lines().size());
        state.insert('\n', WaypointDescription.MAX_CODE_POINTS);
        assertEquals(WaypointDescription.MAX_LINES, state.lines().size());
        state.backspace();
        state.insert('x', WaypointDescription.MAX_CODE_POINTS);
        assertTrue(state.text().endsWith("x"));
    }

    @Test
    void codePointCapRejectsFurtherCharacters() {
        final int cap = WaypointDescription.MAX_CODE_POINTS;
        for (int i = 0; i < cap; i++) {
            state.insert('a', cap);
        }
        assertEquals(cap, state.text().codePointCount(0, state.text().length()));
        state.insert('b', cap);
        assertEquals(cap, state.text().codePointCount(0, state.text().length()));
    }

    @Test
    void backspaceAtLineStartMergesWithThePreviousLine() {
        state.reset("ab\ncd");
        state.setCursor(1, 0);
        state.backspace();
        assertEquals("abcd", state.text());
        assertEquals(0, state.cursorLine());
        assertEquals(2, state.cursorColumn());
    }

    @Test
    void cursorMovementStaysInsideBounds() {
        state.reset("ab\ncd");
        state.setCursor(0, 2);
        // End-of-line moveRight continues onto the next line (mirrors moveLeft).
        state.moveRight();
        assertEquals(1, state.cursorLine());
        assertEquals(0, state.cursorColumn());
        // Vertical moves carry the current column, clamped to the target line.
        state.moveUp();
        assertEquals(0, state.cursorLine());
        assertEquals(0, state.cursorColumn());
        state.endOfLine();
        state.moveDown();
        assertEquals(1, state.cursorLine());
        assertEquals(2, state.cursorColumn());
        state.moveUp();
        assertEquals(0, state.cursorLine());
        assertEquals(2, state.cursorColumn());
        // moveLeft at the very first character is a no-op.
        state.startOfLine();
        state.moveLeft();
        assertEquals(0, state.cursorLine());
        assertEquals(0, state.cursorColumn());
        // moveRight at the last character is a no-op; moveLeft steps back.
        state.moveDown();
        state.endOfLine();
        state.moveRight();
        assertEquals(1, state.cursorLine());
        assertEquals(2, state.cursorColumn());
        state.moveLeft();
        assertEquals(1, state.cursorLine());
        assertEquals(1, state.cursorColumn());
    }

    @Test
    void deleteAtLineEndAppendsTheNextLine() {
        state.reset("ab\ncd");
        state.setCursor(0, 2);
        state.delete();
        assertEquals("abcd", state.text());
        assertEquals(1, state.lines().size());
    }

    @Test
    void backspaceRemovesOneWholeCodePoint() {
        // Pre-12109 charTyped delivers a supplementary char as two inserts;
        // a split pair would be rejected by normalize at Done.
        state.insert(0xD83D, WaypointDescription.MAX_CODE_POINTS);
        state.insert(0xDE00, WaypointDescription.MAX_CODE_POINTS);
        assertEquals(1, state.text().codePointCount(0, state.text().length()));
        state.backspace();
        assertEquals("", state.text());
        assertEquals(0, state.cursorColumn());
    }

    @Test
    void forwardDeleteRemovesOneWholeCodePoint() {
        state.insert(0xD83D, WaypointDescription.MAX_CODE_POINTS);
        state.insert(0xDE00, WaypointDescription.MAX_CODE_POINTS);
        state.setCursor(0, 0);
        state.delete();
        assertEquals("", state.text());
    }
}
