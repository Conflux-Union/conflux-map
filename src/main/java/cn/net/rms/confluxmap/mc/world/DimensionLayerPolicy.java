package cn.net.rms.confluxmap.mc.world;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionType;

/**
 * The identity-first dimension-to-layer classification, deliberately free of client-only types:
 * the dedicated-server gametest ({@code LayerSelectorEndGameTest}) loads this seam directly, and
 * linking {@link LayerSelector} there fails against Fabric's client-class stripping because its
 * tick path passes a {@code ClientWorld} where a {@code World} is expected.
 *
 * <p>Classification itself needs nothing client-side: §1's three detection cases with vanilla
 * dimension identity taking precedence over metadata. {@code minecraft:the_end} is pinned to
 * {@link DimensionKind#NO_SKY_NO_CEILING} because vanilla flipped its has_skylight from false to
 * true in 1.21.9, which must not reclassify it as an ordinary sky-lit dimension.
 */
public final class DimensionLayerPolicy {
    private DimensionLayerPolicy() {
    }

    /** §1's three detection cases, generalized from "has_ceiling"/"has_sky_light" dimension metadata. */
    public enum DimensionKind {
        /** Case A: Nether-like (e.g. the Nether itself). */
        HAS_CEILING,
        /** Case B: no ceiling and no ambient sky light (e.g. the End). */
        NO_SKY_NO_CEILING,
        /** Case C: ordinary sky-lit dimension (e.g. the Overworld). */
        SKY_LIT
    }

    /** The core {@link DimensionId} of a world, from its registry key (e.g. "minecraft:the_end"). */
    public static DimensionId dimensionId(final World world) {
        return DimensionId.parse(world.getRegistryKey().getValue().toString());
    }

    /** Identity-first classification: the vanilla End stays the End whatever its metadata claims. */
    public static DimensionKind classify(final DimensionId dimension, final DimensionType type) {
        return classify(dimension, type.hasCeiling(), type.hasSkyLight());
    }

    /**
     * §1's classification, with vanilla dimension identity taking precedence over metadata:
     * has_ceiling, else no-sky-light, else ordinary sky-lit.
     */
    public static DimensionKind classify(
        final DimensionId dimension, final boolean hasCeiling, final boolean hasSkyLight
    ) {
        if (DimensionId.END.equals(dimension)) {
            return DimensionKind.NO_SKY_NO_CEILING;
        }
        if (hasCeiling) {
            return DimensionKind.HAS_CEILING;
        }
        return hasSkyLight ? DimensionKind.SKY_LIT : DimensionKind.NO_SKY_NO_CEILING;
    }

    /**
     * The layer a no-sky/no-ceiling dimension (the End) is always rendered as: §1.2/§4's plain
     * top-down surface, with no player-relative switching and no second layer for the override
     * cycle to reach. The other kinds resolve from player state in {@link LayerSelector#tick()}.
     */
    public static MapLayer layerFor(final DimensionKind kind) {
        if (kind != DimensionKind.NO_SKY_NO_CEILING) {
            throw new IllegalArgumentException("the layer for " + kind + " depends on player state");
        }
        return MapLayer.END_SURFACE;
    }
}
