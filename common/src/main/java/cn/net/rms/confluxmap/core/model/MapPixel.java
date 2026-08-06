package cn.net.rms.confluxmap.core.model;

/**
 * Canonical semantic value reconstructed for one map pixel.
 *
 * <p>{@code surfaceY/kind/mapColorId} describe the visible top. A water or ice pixel additionally
 * carries the depth to its floor and the floor's map colour, keeping submerged dirt or stone below
 * the fluid instead of flattening it into a land surface at the fluid Y. The same value shape is
 * used by server summaries and network corrections.
 */
public record MapPixel(
    int biomeId,
    int surfaceY,
    int kind,
    int mapColorId,
    int fluidDepth,
    int floorMapColorId,
    String materialId,
    String floorMaterialId
) {
    public static final int MAP_COLOR_NONE = 255;

    public MapPixel {
        if (biomeId < 0 || biomeId > 255 || kind < 0 || kind > 255
            || mapColorId < 0 || mapColorId > 255 || floorMapColorId < 0 || floorMapColorId > 255
            || fluidDepth < 0 || fluidDepth > 255
            || surfaceY < Short.MIN_VALUE || surfaceY > Short.MAX_VALUE) {
            throw new IllegalArgumentException("map pixel field outside wire range");
        }
        materialId = materialId == null ? "" : materialId;
        floorMaterialId = floorMaterialId == null ? "" : floorMaterialId;
        if (materialId.length() > 256 || floorMaterialId.length() > 256) {
            throw new IllegalArgumentException("map pixel material id is too long");
        }
    }

    public MapPixel(
        final int biomeId,
        final int surfaceY,
        final int kind,
        final int mapColorId,
        final int fluidDepth,
        final int floorMapColorId
    ) {
        this(
            biomeId, surfaceY, kind, mapColorId, fluidDepth, floorMapColorId, "", ""
        );
    }

    public MapPixel(
        final int biomeId,
        final int surfaceY,
        final int kind,
        final int mapColorId,
        final int fluidDepth
    ) {
        this(biomeId, surfaceY, kind, mapColorId, fluidDepth, MAP_COLOR_NONE, "", "");
    }

    /** Legacy residual equality; material identity affects color, not predicted terrain shape. */
    public boolean sameTerrainSemantics(final MapPixel other) {
        return other != null
            && biomeId == other.biomeId
            && surfaceY == other.surfaceY
            && kind == other.kind
            && mapColorId == other.mapColorId
            && fluidDepth == other.fluidDepth
            && floorMapColorId == other.floorMapColorId;
    }
}
