package cn.net.rms.confluxmap.core.radar;

import cn.net.rms.confluxmap.core.model.DimensionId;

/** Vanilla item id used to label a highlighted player's destination dimension. */
public final class PlayerRadarDimensionIcon {
    private PlayerRadarDimensionIcon() {
    }

    public static String itemId(final DimensionId dimension) {
        if (DimensionId.OVERWORLD.equals(dimension)) {
            return "minecraft:grass_block";
        }
        if (DimensionId.NETHER.equals(dimension)) {
            return "minecraft:netherrack";
        }
        if (DimensionId.END.equals(dimension)) {
            return "minecraft:end_stone";
        }
        return "minecraft:compass";
    }
}
