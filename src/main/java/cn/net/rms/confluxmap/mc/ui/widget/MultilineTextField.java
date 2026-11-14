package cn.net.rms.confluxmap.mc.ui.widget;

import cn.net.rms.confluxmap.core.waypoint.WaypointDescription;
import cn.net.rms.confluxmap.mc.render.GuiShapes;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.util.math.MatrixStack;

/**
 * Fixed-height multiline editor with EditBox styling. The buffer is a list of
 * logical lines (one per {@code \n}); a logical line scrolls horizontally
 * instead of hard-wrapping, and the viewport scrolls vertically across lines.
 * All buffer/cursor state lives in {@link State}, a plain-logic object kept
 * separate so the edit invariants unit-test without Minecraft classes.
 */
public final class MultilineTextField {
    private static final int LINE_GAP = 2;
    private static final int INNER_PADDING = 4;

    private final State state = new State();
    private final TextRenderer font;
    private int x;
    private int y;
    private int width;
    private int height;
    private boolean focused;

    public MultilineTextField(final TextRenderer font) {
        this.font = font;
    }

    public State state() {
        return state;
    }

    public void setGeometry(final int x, final int y, final int width, final int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public void setText(final String value) {
        state.reset(value);
        clampScroll();
    }

    public String text() {
        return state.text();
    }

    public void setFocused(final boolean focused) {
        this.focused = focused;
    }

    public boolean focused() {
        return focused;
    }

    public boolean isHovered(final double mouseX, final double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    public boolean charTyped(final char chr, final int modifiers) {
        return insertString(String.valueOf(chr));
    }

    /** Inserts a string (surrogate-pair safe path for the newer event APIs). */
    public boolean insertString(final String value) {
        if (!focused) {
            return false;
        }
        if (value != null) {
            for (int offset = 0; offset < value.length();) {
                final int codePoint = value.codePointAt(offset);
                state.insert(codePoint, WaypointDescription.MAX_CODE_POINTS);
                offset += Character.charCount(codePoint);
            }
        }
        clampScroll();
        return true;
    }

    public boolean keyPressed(final int keyCode) {
        if (!focused) {
            return false;
        }
        switch (keyCode) {
            case 259 -> state.backspace();      // backspace
            case 261 -> state.delete();         // delete
            case 263 -> state.moveLeft();
            case 262 -> state.moveRight();
            case 264 -> state.moveDown();
            case 265 -> state.moveUp();
            case 268 -> state.startOfLine();    // home
            case 269 -> state.endOfLine();      // end
            case 257, 335 -> state.insert('\n', WaypointDescription.MAX_CODE_POINTS);
            default -> {
                return false;
            }
        }
        clampScroll();
        return true;
    }

    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        if (button != 0 || !isHovered(mouseX, mouseY)) {
            return false;
        }
        setFocused(true);
        final int clickLine = (int) ((Math.round(mouseY) - y - INNER_PADDING) / lineHeight());
        final int line = Math.max(0, Math.min(state.lines().size() - 1, state.scrollLine + clickLine));
        state.setCursor(line, columnForPixel(state.lines().get(line), (int) Math.round(mouseX) - x - INNER_PADDING));
        clampScroll();
        return true;
    }

    public boolean mouseScrolled(final double mouseX, final double mouseY, final double amount) {
        if (!isHovered(mouseX, mouseY) || amount == 0) {
            return false;
        }
        state.scrollLine -= (int) Math.signum(amount);
        clampScroll();
        return true;
    }

    public void render(final GuiDraw draw, final MatrixStack matrices, final int textColor) {
        final int lineCount = state.lines().size();
        final int lineHeight = lineHeight();
        // Background and border follow the EditBox look.
        GuiShapes.fillRect(matrices, x, y, width, height, 0xFF8B8B8B);
        GuiShapes.fillRect(
            matrices, x + 1, y + 1, width - 2, height - 2,
            focused ? 0xFF000000 : 0xFFC6C6C6
        );
        final int contentTop = y + INNER_PADDING - state.scrollLine * lineHeight;
        final int clipBottom = y + height;
        for (int line = state.scrollLine; line < lineCount; line++) {
            final int lineY = contentTop + line * lineHeight;
            if (lineY + lineHeight <= y + INNER_PADDING) {
                continue;
            }
            if (lineY >= clipBottom) {
                break;
            }
            final String text = state.lines().get(line);
            int scrollX = 0;
            final int innerWidth = width - INNER_PADDING * 2;
            if (state.cursorLine() == line) {
                final int cursorWidth = font.getWidth(text.substring(0, state.cursorColumn()));
                if (cursorWidth - scrollX > innerWidth) {
                    scrollX = cursorWidth - innerWidth;
                }
            } else if (font.getWidth(text) > innerWidth && state.cursorLine() != line) {
                scrollX = 0;
            }
            final int drawX = x + INNER_PADDING - scrollX;
            if (!text.isEmpty()) {
                draw.drawTextWithShadow(font, text, drawX, lineY, textColor);
            }
            if (state.cursorLine() == line && focused) {
                final int cursorX = drawX + font.getWidth(text.substring(0, state.cursorColumn()));
                if (cursorX >= x + INNER_PADDING && cursorX <= x + width - INNER_PADDING
                    && lineY + lineHeight <= clipBottom) {
                    GuiShapes.fillRect(matrices, cursorX, lineY, 1, lineHeight - LINE_GAP, textColor);
                }
            }
        }
    }

    private int columnForPixel(final String line, final int pixelX) {
        if (pixelX <= 0) {
            return 0;
        }
        for (int column = 0; column < line.length(); column++) {
            if (font.getWidth(line.substring(0, column + 1)) > pixelX) {
                return column;
            }
        }
        return line.length();
    }

    private int lineHeight() {
        return font.fontHeight + LINE_GAP;
    }

    private int visibleLineCount() {
        return Math.max(1, (height - INNER_PADDING * 2) / lineHeight());
    }

    private void clampScroll() {
        final int visible = visibleLineCount();
        final int last = Math.max(0, state.lines().size() - visible);
        state.scrollLine = Math.max(0, Math.min(last, state.scrollLine));
        if (state.cursorLine() < state.scrollLine) {
            state.scrollLine = state.cursorLine();
        }
        if (state.cursorLine() >= state.scrollLine + visible) {
            state.scrollLine = state.cursorLine() - visible + 1;
        }
    }

    /**
     * Logical-line buffer with a (line, column) cursor. Plain Java on purpose:
     * no Minecraft classes, so the edit invariants unit-test directly.
     */
    public static final class State {
        private final List<String> lines = new ArrayList<>(List.of(""));
        private int cursorLine;
        private int cursorColumn;
        int scrollLine;

        public void reset(final String value) {
            lines.clear();
            if (value == null || value.isEmpty()) {
                lines.add("");
            } else {
                for (final String line : value.split("\n", -1)) {
                    lines.add(line);
                }
            }
            cursorLine = 0;
            cursorColumn = 0;
            scrollLine = 0;
        }

        public List<String> lines() {
            return List.copyOf(lines);
        }

        public String text() {
            return String.join("\n", lines);
        }

        public int cursorLine() {
            return cursorLine;
        }

        public int cursorColumn() {
            return cursorColumn;
        }

        void setCursor(final int line, final int column) {
            cursorLine = Math.max(0, Math.min(lines.size() - 1, line));
            cursorColumn = Math.max(0, Math.min(lines.get(cursorLine).length(), column));
        }

        /** Inserts one character or newline; input beyond the caps is dropped. */
        public void insert(final int codePoint, final int maxCodePoints) {
            if (codePoint == '\n') {
                insertNewline(maxCodePoints);
                return;
            }
            if (codePoints(text()) >= maxCodePoints) {
                return;
            }
            final String piece = new String(Character.toChars(codePoint));
            final String line = lines.get(cursorLine);
            lines.set(cursorLine,
                line.substring(0, cursorColumn) + piece + line.substring(cursorColumn));
            cursorColumn += piece.length();
        }

        public void insert(final char chr, final int maxCodePoints) {
            insert((int) chr, maxCodePoints);
        }

        private void insertNewline(final int maxCodePoints) {
            if (lines.size() >= WaypointDescription.MAX_LINES) {
                return;
            }
            final String line = lines.get(cursorLine);
            lines.set(cursorLine, line.substring(0, cursorColumn));
            lines.add(cursorLine + 1, line.substring(cursorColumn));
            cursorLine++;
            cursorColumn = 0;
        }

        public void backspace() {
            if (cursorColumn > 0) {
                final String line = lines.get(cursorLine);
                final int removed = codePointWidthBefore(line, cursorColumn);
                lines.set(cursorLine,
                    line.substring(0, cursorColumn - removed) + line.substring(cursorColumn));
                cursorColumn -= removed;
            } else if (cursorLine > 0) {
                final String current = lines.remove(cursorLine);
                cursorLine--;
                cursorColumn = lines.get(cursorLine).length();
                lines.set(cursorLine, lines.get(cursorLine) + current);
            }
        }

        public void delete() {
            final String line = lines.get(cursorLine);
            if (cursorColumn < line.length()) {
                final int removed = codePointWidthAt(line, cursorColumn);
                lines.set(cursorLine,
                    line.substring(0, cursorColumn) + line.substring(cursorColumn + removed));
            } else if (cursorLine < lines.size() - 1) {
                final String next = lines.remove(cursorLine + 1);
                lines.set(cursorLine, line + next);
            }
        }

        private static int codePointWidthBefore(final String line, final int column) {
            if (column >= 2
                && Character.isHighSurrogate(line.charAt(column - 2))
                && Character.isLowSurrogate(line.charAt(column - 1))) {
                return 2;
            }
            return 1;
        }

        private static int codePointWidthAt(final String line, final int column) {
            if (column + 2 <= line.length()
                && Character.isHighSurrogate(line.charAt(column))
                && Character.isLowSurrogate(line.charAt(column + 1))) {
                return 2;
            }
            return 1;
        }

        public void moveLeft() {
            if (cursorColumn > 0) {
                cursorColumn--;
            } else if (cursorLine > 0) {
                cursorLine--;
                cursorColumn = lines.get(cursorLine).length();
            }
        }

        public void moveRight() {
            if (cursorColumn < lines.get(cursorLine).length()) {
                cursorColumn++;
            } else if (cursorLine < lines.size() - 1) {
                cursorLine++;
                cursorColumn = 0;
            }
        }

        public void moveUp() {
            if (cursorLine > 0) {
                cursorLine--;
                cursorColumn = Math.min(cursorColumn, lines.get(cursorLine).length());
            }
        }

        public void moveDown() {
            if (cursorLine < lines.size() - 1) {
                cursorLine++;
                cursorColumn = Math.min(cursorColumn, lines.get(cursorLine).length());
            }
        }

        public void startOfLine() {
            cursorColumn = 0;
        }

        public void endOfLine() {
            cursorColumn = lines.get(cursorLine).length();
        }

        private static int codePoints(final String value) {
            return value.codePointCount(0, value.length());
        }
    }
}
