package cn.net.rms.confluxmap.mc.ui;

import cn.net.rms.confluxmap.compat.Ids;
import cn.net.rms.confluxmap.core.predict.StructureIndex;
import cn.net.rms.confluxmap.mc.render.RenderUtil;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

/** Vanilla runtime textures used as recognizable structure icons without bundling copied assets. */
public final class StructureIconCatalog {
    private static final Map<StructureIndex.StructureType, Identifier> ICONS = icons();

    private StructureIconCatalog() {
    }

    public static Identifier icon(final StructureIndex.StructureType type) {
        return icon(type, 0);
    }

    public static Identifier icon(final StructureIndex.StructureType type, final int variant) {
        final String variantTexture = switch (type) {
            case VILLAGE -> {
                if ((variant & 8) != 0) {
                    yield "item/rotten_flesh";
                }
                yield switch (variant & 7) {
                    case 1 -> "block/chiseled_sandstone";
                    case 2 -> "block/acacia_log";
                    case 3 -> "block/spruce_log";
                    case 4 -> "block/snow";
                    default -> null;
                };
            }
            case IGLOO -> variant == 1 ? "item/brewing_stand" : null;
            case SHIPWRECK -> variant == 1 ? "block/sand" : null;
            case BASTION_REMNANT -> switch (variant) {
                case 0 -> "block/polished_blackstone_bricks";
                case 1 -> "block/crimson_nylium";
                case 2 -> "block/gold_block";
                case 3 -> "block/blackstone";
                default -> null;
            };
            case RUINED_PORTAL, RUINED_PORTAL_NETHER ->
                variant == 1 ? "block/obsidian" : null;
            case END_CITY -> variant == 1 ? "item/elytra" : null;
            default -> null;
        };
        if (variantTexture != null) {
            return texture(variantTexture);
        }
        final Identifier icon = ICONS.get(type);
        if (icon == null) {
            throw new IllegalArgumentException("No structure icon for " + type.id());
        }
        return icon;
    }

    public static void draw(
        final GuiDraw draw,
        final StructureIndex.StructureType type,
        final float x,
        final float y,
        final float size,
        final int tint
    ) {
        draw(draw, type, 0, x, y, size, tint);
    }

    public static void draw(
        final GuiDraw draw,
        final StructureIndex.StructureType type,
        final int variant,
        final float x,
        final float y,
        final float size,
        final int tint
    ) {
        RenderUtil.bindTexture(MinecraftClient.getInstance(), icon(type, variant));
        RenderUtil.drawTintedQuad(draw.matrices(), x, y, size, size, 0f, 0f, 1f, 1f, tint);
    }

    private static Map<StructureIndex.StructureType, Identifier> icons() {
        final EnumMap<StructureIndex.StructureType, Identifier> icons =
            new EnumMap<>(StructureIndex.StructureType.class);
        put(icons, StructureIndex.StructureType.DESERT_PYRAMID, "block/chiseled_sandstone");
        put(icons, StructureIndex.StructureType.JUNGLE_TEMPLE, "block/mossy_cobblestone");
        put(icons, StructureIndex.StructureType.SWAMP_HUT, "item/cauldron");
        put(icons, StructureIndex.StructureType.IGLOO, "block/snow");
        put(icons, StructureIndex.StructureType.VILLAGE, "item/bell");
        put(icons, StructureIndex.StructureType.OCEAN_RUIN, "item/trident");
        put(icons, StructureIndex.StructureType.SHIPWRECK, "item/oak_boat");
        put(icons, StructureIndex.StructureType.OCEAN_MONUMENT, "block/prismarine");
        put(icons, StructureIndex.StructureType.WOODLAND_MANSION, "item/totem_of_undying");
        put(icons, StructureIndex.StructureType.PILLAGER_OUTPOST, "item/crossbow_standby");
        put(icons, StructureIndex.StructureType.RUINED_PORTAL, "block/crying_obsidian");
        put(icons, StructureIndex.StructureType.RUINED_PORTAL_NETHER, "block/crying_obsidian");
        put(icons, StructureIndex.StructureType.ANCIENT_CITY, "item/echo_shard");
        put(icons, StructureIndex.StructureType.BURIED_TREASURE, "item/heart_of_the_sea");
        put(icons, StructureIndex.StructureType.MINESHAFT, "item/minecart");
        put(icons, StructureIndex.StructureType.FORTRESS, "block/nether_bricks");
        put(icons, StructureIndex.StructureType.BASTION_REMNANT, "block/gilded_blackstone");
        put(icons, StructureIndex.StructureType.END_CITY, "item/shulker_shell");
        put(icons, StructureIndex.StructureType.TRAIL_RUINS, "item/brush");
        put(icons, StructureIndex.StructureType.TRIAL_CHAMBERS, "item/trial_key");
        put(icons, StructureIndex.StructureType.STRONGHOLD, "item/ender_eye");
        put(icons, StructureIndex.StructureType.NETHER_FOSSIL, "block/bone_block_side");
        return icons;
    }

    private static void put(
        final Map<StructureIndex.StructureType, Identifier> icons,
        final StructureIndex.StructureType type,
        final String texture
    ) {
        icons.put(type, texture(texture));
    }

    private static Identifier texture(final String path) {
        return Ids.of("minecraft", "textures/" + path + ".png");
    }
}
