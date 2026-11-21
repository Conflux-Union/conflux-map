package cn.net.rms.confluxmap.mc.ui.screen;

/**
 * Pure row geometry for the waypoint edit form, kept free of Minecraft types
 * so the spacing invariants are unit-testable. The form is a stack of labeled
 * control rows (name, coordinates, an optional set/group cycler, the color
 * swatch grid, an options row, and an optional marker row) that must clear a
 * footer pinned at {@code height - 32}.
 *
 * <p>The row gap is a single value derived from the GUI height. The full form
 * holds 165px of controls and captions plus five row gaps, so at the 320x240
 * minimum the uniform gap bottoms out at 1px; from 285px up it reaches 8px,
 * and it caps at 12px. Every height gets the same overlap-free rhythm instead
 * of per-row offsets, which previously stacked the color caption onto the set
 * button and the marker caption flush against the options row.
 */
final class WaypointEditLayout {
    /** Caption line height: the text renderer's 9px advance including descenders. */
    static final int LABEL_HEIGHT = 9;
    /** Captions sit one pixel above their control: captions read as attached, rows as separate. */
    static final int LABEL_CONTROL_GAP = 1;
    static final int FIELD_HEIGHT = 20;
    static final int SWATCH_SIZE = 20;
    static final int SWATCH_GAP = 6;
    /**
     * Width of the centered form column. Every row spans exactly this width - the name field,
     * the set/group cycler, the coordinate triple, the swatch grid, the options row, the marker
     * row, and both footer layouts - so the form reads as one column. The value is pinned by
     * {@code everyRowSpansTheSameFormWidth}; change it there too, or the test fails.
     */
    static final int FORM_WIDTH = 228;

    static final int TITLE_Y = 8;
    /** "Notes" corner button: 40x20, tucked into the title row's right margin. */
    static final int NOTES_BUTTON_WIDTH = 40;
    static final int NOTES_BUTTON_HEIGHT = 20;

    /**
     * Bounds of the title-row "Notes" button: right-aligned inside the 320px
     * minimum screen width with a 6px margin, vertically on the title line.
     */
    static NotesButtonBounds notesButtonBounds(final int screenWidth) {
        final int left = screenWidth / 2 + FORM_WIDTH / 2 - NOTES_BUTTON_WIDTH - 6;
        return new NotesButtonBounds(left, TITLE_Y, NOTES_BUTTON_WIDTH, NOTES_BUTTON_HEIGHT);
    }

    record NotesButtonBounds(int x, int y, int width, int height) {
    }
    /** First caption top: keeps the same clearance below the title as the title has to the edge. */
    static final int CONTENT_TOP = 24;

    /** Footer buttons sit at {@code height - 32} and are FIELD_HEIGHT tall. */
    static final int FOOTER_MARGIN = 32;
    static final int FOOTER_CLEARANCE = 8;
    /** Editing footer: delete / done / cancel (72*3 + 6*2 = FORM_WIDTH). */
    static final int FOOTER_EDIT_WIDTH = 72;
    static final int FOOTER_GAP = 6;
    /** Creating footer: done / cancel, split by a centered 8px gap (110*2 + 8 = FORM_WIDTH). */
    static final int FOOTER_CREATE_WIDTH = 110;
    static final int FOOTER_CREATE_GAP = 8;

    /** Room reserved between the form and the footer for the validation error line. */
    static final int STATUS_GAP = 2;
    private static final int STATUS_RESERVED = STATUS_GAP + LABEL_HEIGHT;
    /** On tall screens the error line anchors three pixels above the footer top. */
    private static final int STATUS_FOOTER_OFFSET = 12;

    static final int MIN_ROW_GAP = 1;
    static final int MAX_ROW_GAP = 12;

    /** Coordinate triple: 72*3 + 6*2 = FORM_WIDTH. */
    static final int COORD_WIDTH = 72;
    static final int COORD_GAP = 6;

    /** Marker row: mode cycler on the left half, label field or icon pair on the right. */
    static final int MARKER_MODE_WIDTH = 94;
    static final int MARKER_GAP = 4;
    static final int MARKER_CONTROL_WIDTH = 130;
    static final int MARKER_ICON_WIDTH = 94;
    static final int MARKER_CLEAR_WIDTH = 32;

    static final int CUSTOM_COLOR_WIDTH = 68;
    /** Wide enough for "Cross-Dim: On/Off" plus button padding; the long form truncated. */
    static final int CROSS_DIMENSION_WIDTH = 108;
    static final int CROSS_DIMENSION_Y_WIDTH = 40;
    static final int OPTION_GAP = 6;

    /**
     * The local set row splits into cycler plus temporary toggle because a new
     * row would not clear the 240px GUI floor; the screen trims long set names
     * to the narrower cycler.
     */
    static final int TEMPORARY_WIDTH = 68;
    static final int SET_SELECTOR_WIDTH = FORM_WIDTH - OPTION_GAP - TEMPORARY_WIDTH;

    private static final int LABELED_ROW_HEIGHT = LABEL_HEIGHT + LABEL_CONTROL_GAP + FIELD_HEIGHT;

    private WaypointEditLayout() {
    }

    /** Vertical placement of every form row for one GUI height. */
    record Rows(
        int nameFieldY,
        int coordsY,
        int groupY,
        int swatchesY,
        int optionsY,
        int markerY,
        int statusY,
        int gap
    ) {
    }

    /**
     * @param fullForm false for the chat target, which drops the set/group
     *     cycler and the marker row; the remaining rows tighten instead of
     *     leaving holes at the dropped positions.
     */
    static Rows rows(final int screenHeight, final boolean fullForm) {
        final int labeledRows = fullForm ? 5 : 3;
        final int blockHeight = labeledRows * LABELED_ROW_HEIGHT + FIELD_HEIGHT;
        final int gapCount = labeledRows;
        final int available =
            screenHeight - FOOTER_MARGIN - FOOTER_CLEARANCE - STATUS_RESERVED - CONTENT_TOP;
        final int gap = Math.max(MIN_ROW_GAP, Math.min(MAX_ROW_GAP, (available - blockHeight) / gapCount));

        int y = CONTENT_TOP + LABEL_HEIGHT + LABEL_CONTROL_GAP;
        final int nameFieldY = y;
        y += LABELED_ROW_HEIGHT + gap;
        final int coordsY = y;
        y += LABELED_ROW_HEIGHT + gap;
        // The chat form never shows the group or marker rows. Their widgets are
        // parked in the next slot and stay invisible (visibility also gates
        // interaction), so the visible rows close up over the dropped ones.
        final int groupY = y;
        if (fullForm) {
            y += LABELED_ROW_HEIGHT + gap;
        }
        final int swatchesY = y;
        // The options row carries no caption, so it follows the grid at the plain row gap.
        y += FIELD_HEIGHT + gap;
        final int optionsY = y;
        y += FIELD_HEIGHT + gap;
        y += LABEL_HEIGHT + LABEL_CONTROL_GAP;
        final int markerY = y;
        final int contentBottom = fullForm ? markerY + FIELD_HEIGHT : optionsY + FIELD_HEIGHT;
        // At the 240px floor the form consumes the reserved status slot; the line
        // then follows the last row and may touch the footer top edge instead of
        // hiding behind widgets, which is where the old pre-widget draw put it.
        final int statusY = Math.max(contentBottom + STATUS_GAP, footerY(screenHeight) - STATUS_FOOTER_OFFSET);
        return new Rows(nameFieldY, coordsY, groupY, swatchesY, optionsY, markerY, statusY, gap);
    }

    static int footerY(final int screenHeight) {
        return screenHeight - FOOTER_MARGIN;
    }

    /** Caption top for a control row. */
    static int labelY(final int controlY) {
        return controlY - LABEL_HEIGHT - LABEL_CONTROL_GAP;
    }

    /** Swatch grid width: the preset cells plus the custom color cell as the last column. */
    static int swatchRowWidth(final int presetCount) {
        return (presetCount + 1) * SWATCH_SIZE + presetCount * SWATCH_GAP;
    }

    /**
     * Options row width: the custom color button, and on the full form the
     * cross-dimension toggle with its Y override field. Both dimension controls
     * occupy fixed slots so flipping the toggle never reflows the form.
     */
    static int optionsRowWidth(final boolean fullForm) {
        return fullForm
            ? CUSTOM_COLOR_WIDTH + OPTION_GAP + CROSS_DIMENSION_WIDTH + OPTION_GAP + CROSS_DIMENSION_Y_WIDTH
            : CUSTOM_COLOR_WIDTH;
    }
}
