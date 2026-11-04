package cn.net.rms.confluxmap.core.backfill;

import cn.net.rms.confluxmap.core.net.SummaryCodec;

/**
 * Renders companion-style summary columns into snapshot-ready render colors
 * using the running game's color model. Implemented on the Minecraft side (the
 * colors come from the live texture atlas and registry); the converter in this
 * package is game-free and testable against fakes. The column's kind, surface
 * Y, fluid depth, and light already arrive inside {@link SummaryCodec.Column};
 * the baker owns only the color planes, with water semantics mirroring live
 * capture: the surface color is the fluid itself (tint pre-multiplied, tint
 * channel white), the floor color rides in the overlay plane.
 */
public interface McaColumnBaker {
    /** Color planes for one column; kind/surface Y stay with the summary column. */
    record Baked(int baseArgb, int xaeroBaseArgb, int tintArgb, int overlayArgb, int xaeroOverlayArgb) {
        public static Baked empty() {
            return new Baked(0, 0, 0, 0, 0);
        }
    }

    /**
     * Bakes one column.
     *
     * @param column the scanned summary column; its {@code kind()} decides the
     *     fluid/land color layout
     * @param worldX block coordinate of the column, for position-detail profiles
     * @param worldZ block coordinate of the column
     * @param cubiomesBiomeId numeric biome id carried by the column
     */
    Baked bake(SummaryCodec.Column column, int worldX, int worldZ, int cubiomesBiomeId);
}
