package cn.net.rms.confluxmap.core.xaeroimport;

/**
 * Save-format version of an external world-map region stream, encoded as
 * {@code (major << 16) | minor}. The major selects identity encoding (NBT key
 * spelling, biome palettes), the minor selects framing (tile trailers, height
 * bit position, overlay opacity). See {@code docs/reference-specs/
 * xaero-world-map-region-format.md} for the format this models.
 */
public record XaeroSaveFormat(int major, int minor) {
    /** Version marker byte that precedes the encoded version in every current stream. */
    public static final int VERSION_MARKER = 0xFF;

    public static XaeroSaveFormat ofEncoded(final int encoded) {
        return new XaeroSaveFormat(encoded >>> 16, encoded & 0xFFFF);
    }

    public int encoded() {
        return (major << 16) | minor;
    }

    /**
     * Whether this importer decodes the stream. Majors below 5 carry 1.15-era
     * numeric/legacy encodings outside our Minecraft range, majors above 8 are
     * newer than this spec; the minor side of the window is open because minor
     * only adds framing features.
     */
    public boolean supported() {
        return major >= 5 && major <= 8 && minor <= 8;
    }

    /** Major 8 (MC 26.3) renamed the inline state NBT keys. */
    public boolean renamedStateKeys() {
        return major >= 8;
    }

    /** Majors 4+ palette-compress biomes; every supported major is 4+. */
    public boolean biomePalettes() {
        return major >= 4;
    }

    /** Minors 4+ store the tile interpretation byte and put height high bits at 25..28. */
    public boolean modernFraming() {
        return minor >= 4;
    }

    /** Minors 6+ store the tile cave-start field. */
    public boolean hasCaveStart() {
        return minor >= 6;
    }

    /** Minors 7+ store the tile cave-depth byte. */
    public boolean hasCaveDepth() {
        return minor >= 7;
    }

    /** Minors 8+ carry overlay opacity in a params nibble instead of an explicit int. */
    public boolean overlayOpacityNibble() {
        return minor >= 8;
    }

    /** Legacy custom-color payloads exist only before minor 5. */
    public boolean usesLegacyColors() {
        return minor < 5;
    }
}
