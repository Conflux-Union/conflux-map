package cn.net.rms.confluxmap.mc.radar;

import cn.net.rms.confluxmap.compat.Ids;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.AxolotlEntity;
import net.minecraft.entity.passive.CatEntity;
import net.minecraft.entity.passive.FoxEntity;
import net.minecraft.entity.passive.LlamaEntity;
import net.minecraft.entity.passive.MooshroomEntity;
import net.minecraft.entity.passive.PandaEntity;
import net.minecraft.entity.passive.ParrotEntity;
import net.minecraft.entity.passive.RabbitEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.entity.passive.StriderEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.util.Identifier;

/**
 * Render-thread lookup from a live entity to its radar face icon: for players, a sub-UV crop of
 * the player's own skin (face + hat overlay), exactly as before; for vanilla mobs, one 16x16 cell
 * of a single bundled sprite sheet, replacing the previous approach of hand-picked UV crops into
 * each mob's live in-game texture.
 *
 * <p>The sheet is {@code assets/confluxmap/textures/radar/entity_icons.png}: a 208x240px, 13-col
 * x 15-row grid of 16x16 hand-drawn face icons, originally "Entity-Icons" by Simplexity-Development
 * (CC0-1.0 / public domain, see {@code THIRD_PARTY_NOTICES.md} and the license text bundled
 * alongside the sheet). Cell (row, col) are 1-based, row 1 at the top, col 1 at the left; pixel
 * origin of cell (r, c) is {@code x = (c-1)*16, y = (r-1)*16}. The species-to-cell map in
 * {@link #buildSheetTable()} is transcribed verbatim from the authoritative
 * {@code docs/reference-specs/entity-icon-cellmap.json} spec, which documents how each cell was
 * chosen and verified; that JSON is a build-time artifact and is not read at runtime.
 *
 * <p>Several species render different textures/models per instance (sheep wool, cat breed, llama
 * coat, etc). Rather than trying to bake that into the (single, shared) sheet layout, each such
 * species carries a small resolver ({@link CellIcon#variantKey}) that reads the cheap client-side
 * entity state driving that difference and maps it to an alternate cell; species whose variant
 * state isn't cheaply reachable, or whose resolver returns an unmapped key, fall back to the
 * species' base cell.
 *
 * <p>Species without a table entry (including every non-vanilla/modded entity) simply get no icon
 * here ({@link #iconFor} returns {@code null}); the caller keeps drawing the existing shaped-dot
 * marker for those, unchanged.
 */
public final class EntityIconManager {
    /** One species' fractional UV rect (0..1) into whichever texture it's paired with. */
    private record FaceUv(float u0, float v0, float u1, float v1) {
    }

    /**
     * The primary-layer (and, for players only, hat-overlay-layer) sub-UV region to draw as this
     * entity's radar icon. {@code overlayTexture} is {@code null} for every non-player entity.
     * {@code fromSheet} marks bundled-sheet icons, whose UV rect also addresses their baked
     * silhouette outline cell in {@link EntityIconOutlineTexture} (player skins have none - the
     * face crop is fully opaque, so a plain square frame already is its silhouette outline).
     */
    public record FaceIcon(
        Identifier texture, float u0, float v0, float u1, float v1,
        Identifier overlayTexture, float ou0, float ov0, float ou1, float ov1,
        boolean fromSheet
    ) {
        public boolean hasOverlay() {
            return overlayTexture != null;
        }
    }

    /** One species' base sheet cell, plus an optional per-instance variant resolver. */
    private record CellIcon(FaceUv base, Map<String, FaceUv> variants, Function<Entity, String> variantKey) {
        /** Render thread. {@code entity} is guaranteed to be of the species this table entry was registered under. */
        FaceUv resolve(final Entity entity) {
            if (variantKey != null) {
                final String key = variantKey.apply(entity);
                if (key != null) {
                    final FaceUv variant = variants.get(key);
                    if (variant != null) {
                        return variant;
                    }
                }
            }
            return base;
        }
    }

    /** Player skins are always 64x64 in 1.17.1 (legacy 64x32 skins are upgraded on load). */
    private static final int SKIN_SIZE = 64;
    /** BipedEntityModel.getModelData: head = uv(0,0).cuboid(w=8,h=8,d=8). */
    private static final FaceUv PLAYER_FACE = px(8, 8, 16, 16, SKIN_SIZE, SKIN_SIZE);
    /** BipedEntityModel.getModelData: hat = uv(32,0).cuboid(w=8,h=8,d=8). */
    private static final FaceUv PLAYER_HAT = px(40, 8, 48, 16, SKIN_SIZE, SKIN_SIZE);

    private static final Identifier SHEET = Ids.of("confluxmap", "textures/radar/entity_icons.png");
    /** entity_icons.png is a 13-col x 15-row grid of 16x16 cells (docs/reference-specs/entity-icon-cellmap.json). */
    private static final int SHEET_W = 208;
    private static final int SHEET_H = 240;
    private static final int CELL_PX = 16;

    /**
     * 1.17.1 CatEntity.getCatType() int-to-breed order, verified against the vanilla class'
     * TABBY_TYPE..ALL_BLACK_TYPE constant values (0..10) via decompiled bytecode, not guessed.
     */
    private static final String[] CAT_TYPE_NAMES = {
        "tabby", "black", "red", "siamese", "british_shorthair", "calico", "persian", "ragdoll", "white", "jellie", "all_black"
    };

    private static final Map<EntityType<?>, CellIcon> SHEET_TABLE = buildSheetTable();

    private final EntityIconOutlineTexture outlineTexture = new EntityIconOutlineTexture(SHEET);

    public EntityIconManager() {
    }

    /** Render thread. GL id of the sheet's baked silhouette outline mask, or -1 if it can't bake. */
    public int outlineTextureGlId(final MinecraftClient client) {
        return outlineTexture.glId(client);
    }

    /** Render thread, resource reload: re-bake the outline mask from the (possibly overridden) sheet. */
    public void invalidateOutlineTexture() {
        outlineTexture.invalidate();
    }

    /**
     * Render thread. Returns the face icon for this live entity, or {@code null} if none is
     * available (unknown/modded species) - the caller should fall back to the shaped-dot marker
     * in that case.
     */
    public FaceIcon iconFor(final Entity entity) {
        if (entity instanceof AbstractClientPlayerEntity) {
            return playerIcon((AbstractClientPlayerEntity) entity);
        }
        return mobIcon(entity);
    }

    private FaceIcon playerIcon(final AbstractClientPlayerEntity player) {
        //#if MC>=12100
        //$$ final Identifier skin = player.getSkinTextures().texture();
        //#else
        final Identifier skin = player.getSkinTexture();
        //#endif
        return new FaceIcon(
            skin, PLAYER_FACE.u0(), PLAYER_FACE.v0(), PLAYER_FACE.u1(), PLAYER_FACE.v1(),
            skin, PLAYER_HAT.u0(), PLAYER_HAT.v0(), PLAYER_HAT.u1(), PLAYER_HAT.v1(),
            false
        );
    }

    private FaceIcon mobIcon(final Entity entity) {
        final CellIcon icon = SHEET_TABLE.get(entity.getType());
        if (icon == null) {
            return null;
        }
        final FaceUv uv = icon.resolve(entity);
        return new FaceIcon(SHEET, uv.u0(), uv.v0(), uv.u1(), uv.v1(), null, 0f, 0f, 0f, 0f, true);
    }

    private static FaceUv px(final int x0, final int y0, final int x1, final int y1, final int texW, final int texH) {
        return new FaceUv(x0 / (float) texW, y0 / (float) texH, x1 / (float) texW, y1 / (float) texH);
    }

    /** 1-based (row, col) sheet cell -> fractional UV rect, per the grid convention documented on the class. */
    private static FaceUv cell(final int row, final int col) {
        final int x0 = (col - 1) * CELL_PX;
        final int y0 = (row - 1) * CELL_PX;
        return px(x0, y0, x0 + CELL_PX, y0 + CELL_PX, SHEET_W, SHEET_H);
    }

    private static CellIcon icon(final int row, final int col) {
        return new CellIcon(cell(row, col), Map.of(), null);
    }

    @SafeVarargs
    private static CellIcon icon(
        final int row, final int col, final Function<Entity, String> variantKey, final Map.Entry<String, FaceUv>... variants
    ) {
        return new CellIcon(cell(row, col), Map.ofEntries(variants), variantKey);
    }

    private static Map.Entry<String, FaceUv> v(final String key, final int row, final int col) {
        return Map.entry(key, cell(row, col));
    }

    // -- Variant resolvers: one per variantBy value in entity-icon-cellmap.json. Each reads only
    // cheap client-side entity state that exists in 1.17.1, verified against the decompiled/mapped
    // vanilla classes before use (see per-method comments for what was checked).

    /** DyeColor.getName() (verified in mappings.tiny) already returns the "gray"/"light_gray" spelling the JSON uses. */
    private static String sheepVariant(final Entity entity) {
        return ((SheepEntity) entity).getColor().getName();
    }

    private static String mooshroomVariant(final Entity entity) {
        //#if MC>=12100
        //$$ return ((MooshroomEntity) entity).getVariant().asString();
        //#else
        return ((MooshroomEntity) entity).getMooshroomType().name().toLowerCase(Locale.ROOT);
        //#endif
    }

    /** See {@link #CAT_TYPE_NAMES} for the verified int-to-breed order. */
    private static String catVariant(final Entity entity) {
        //#if MC>=12100
        //$$ return ((CatEntity) entity).getVariant().getKey()
        //$$     .map(key -> key.getValue().getPath())
        //$$     .orElse(null);
        //#else
        final int type = ((CatEntity) entity).getCatType();
        return type >= 0 && type < CAT_TYPE_NAMES.length ? CAT_TYPE_NAMES[type] : null;
        //#endif
    }

    private static String foxVariant(final Entity entity) {
        //#if MC>=12100
        //$$ return ((FoxEntity) entity).getVariant().asString();
        //#else
        return ((FoxEntity) entity).getFoxType().name().toLowerCase(Locale.ROOT);
        //#endif
    }

    private static String parrotVariant(final Entity entity) {
        //#if MC>=12100
        //$$ return String.valueOf(((ParrotEntity) entity).getVariant().getId());
        //#else
        return String.valueOf(((ParrotEntity) entity).getVariant());
        //#endif
    }

    /** Shared by llama and trader_llama - TraderLlamaEntity extends LlamaEntity and inherits getVariant(). */
    private static String llamaVariant(final Entity entity) {
        //#if MC>=12100
        //$$ return String.valueOf(((LlamaEntity) entity).getVariant().ordinal());
        //#else
        return String.valueOf(((LlamaEntity) entity).getVariant());
        //#endif
    }

    private static String rabbitVariant(final Entity entity) {
        //#if MC>=12100
        //$$ return String.valueOf(((RabbitEntity) entity).getVariant().getId());
        //#else
        return String.valueOf(((RabbitEntity) entity).getRabbitType());
        //#endif
    }

    private static String axolotlVariant(final Entity entity) {
        return String.valueOf(((AxolotlEntity) entity).getVariant().getId());
    }

    /**
     * getProductGene() (as opposed to getMainGene()) is the gene that's actually expressed in this
     * panda's appearance - a recessive main gene only shows through if the hidden gene matches it,
     * otherwise the panda looks NORMAL despite carrying the rarer gene. NORMAL has no sheet entry,
     * so it naturally falls back to the base cell via CellIcon#resolve.
     */
    private static String pandaVariant(final Entity entity) {
        return ((PandaEntity) entity).getProductGene().name().toLowerCase(Locale.ROOT);
    }

    private static String striderVariant(final Entity entity) {
        return String.valueOf(((StriderEntity) entity).isCold());
    }

    /**
     * VillagerProfession.getId() (verified against the vanilla registration calls) returns the
     * plain registry path ("armorer", "butcher", ...) directly - no Registry lookup needed.
     */
    private static String villagerVariant(final Entity entity) {
        //#if MC>=12100
        //$$ return ((VillagerEntity) entity).getVillagerData().getProfession().id();
        //#else
        return ((VillagerEntity) entity).getVillagerData().getProfession().getId();
        //#endif
    }

    /**
     * 1.17.1 WolfEntity has no coat variants - only "tame"/"angry" alternate cells exist. A tamed
     * wolf shows as tame regardless of anger; an untamed wolf shows as angry only while it has
     * anger time (Angerable#hasAngerTime, verified on WolfEntity's implemented interface); a plain
     * wild, non-angry wolf falls back to the base cell.
     */
    private static String wolfVariant(final Entity entity) {
        final WolfEntity wolf = (WolfEntity) entity;
        if (wolf.isTamed()) {
            return "tame";
        }
        if (wolf.hasAngerTime()) {
            return "angry";
        }
        return null;
    }

    private static Map<EntityType<?>, CellIcon> buildSheetTable() {
        final Map<EntityType<?>, CellIcon> map = new HashMap<>();

        map.put(EntityType.AXOLOTL, icon(3, 9, EntityIconManager::axolotlVariant,
            v("0", 3, 9), v("1", 4, 3), v("2", 6, 11), v("3", 11, 11), v("4", 12, 12)));
        map.put(EntityType.BAT, icon(7, 13));
        map.put(EntityType.BEE, icon(6, 13));
        map.put(EntityType.BLAZE, icon(1, 13));
        map.put(EntityType.CAT, icon(6, 6, EntityIconManager::catVariant,
            v("all_black", 10, 13), v("black", 5, 13), v("british_shorthair", 12, 8), v("calico", 10, 12),
            v("jellie", 9, 10), v("persian", 9, 1), v("ragdoll", 8, 1), v("red", 5, 8),
            v("siamese", 7, 5), v("tabby", 6, 6), v("white", 5, 2)));
        map.put(EntityType.CAVE_SPIDER, icon(9, 12));
        map.put(EntityType.CHICKEN, icon(7, 12));
        map.put(EntityType.COD, icon(6, 12));
        map.put(EntityType.COW, icon(4, 12));
        map.put(EntityType.CREEPER, icon(1, 12));
        map.put(EntityType.DOLPHIN, icon(11, 8));
        map.put(EntityType.DONKEY, icon(11, 7));
        map.put(EntityType.DROWNED, icon(11, 6));
        map.put(EntityType.ELDER_GUARDIAN, icon(11, 5));
        map.put(EntityType.ENDER_DRAGON, icon(11, 4));
        map.put(EntityType.ENDERMAN, icon(11, 3));
        map.put(EntityType.ENDERMITE, icon(11, 2));
        map.put(EntityType.EVOKER, icon(11, 1));
        map.put(EntityType.FOX, icon(10, 11, EntityIconManager::foxVariant,
            v("red", 10, 11), v("snow", 5, 7)));
        map.put(EntityType.GHAST, icon(9, 11));
        map.put(EntityType.GLOW_SQUID, icon(8, 11));
        map.put(EntityType.GOAT, icon(7, 11));
        map.put(EntityType.GUARDIAN, icon(10, 6));
        map.put(EntityType.HOGLIN, icon(10, 5));
        map.put(EntityType.HORSE, icon(10, 4));
        map.put(EntityType.HUSK, icon(10, 3));
        map.put(EntityType.ILLUSIONER, icon(10, 2));
        map.put(EntityType.IRON_GOLEM, icon(10, 1));
        map.put(EntityType.LLAMA, icon(3, 12, EntityIconManager::llamaVariant,
            v("0", 3, 12), v("1", 5, 1), v("2", 12, 7), v("3", 1, 11)));
        map.put(EntityType.MAGMA_CUBE, icon(9, 8));
        map.put(EntityType.MOOSHROOM, icon(9, 7, EntityIconManager::mooshroomVariant,
            v("brown", 12, 6), v("red", 9, 7)));
        map.put(EntityType.MULE, icon(9, 6));
        map.put(EntityType.OCELOT, icon(9, 5));
        map.put(EntityType.PANDA, icon(9, 2, EntityIconManager::pandaVariant,
            v("aggressive", 12, 13), v("brown", 12, 5), v("lazy", 8, 10),
            v("playful", 8, 7), v("weak", 5, 3), v("worried", 3, 3)));
        map.put(EntityType.PARROT, icon(6, 8, EntityIconManager::parrotVariant,
            v("0", 6, 8), v("1", 12, 11), v("2", 4, 11), v("3", 3, 2), v("4", 10, 10)));
        map.put(EntityType.PHANTOM, icon(8, 9));
        map.put(EntityType.PIG, icon(7, 9));
        map.put(EntityType.PIGLIN, icon(6, 9));
        map.put(EntityType.PIGLIN_BRUTE, icon(5, 9));
        map.put(EntityType.PILLAGER, icon(4, 9));
        map.put(EntityType.POLAR_BEAR, icon(8, 6));
        map.put(EntityType.PUFFERFISH, icon(8, 5));
        map.put(EntityType.RABBIT, icon(12, 4, EntityIconManager::rabbitVariant,
            v("0", 12, 4), v("1", 4, 5), v("2", 4, 13), v("3", 1, 5), v("4", 5, 11), v("5", 1, 8), v("99", 11, 12)));
        map.put(EntityType.RAVAGER, icon(7, 8));
        map.put(EntityType.SALMON, icon(2, 8));
        map.put(EntityType.SHEEP, icon(3, 5, EntityIconManager::sheepVariant,
            v("black", 3, 13), v("blue", 12, 10), v("brown", 12, 3), v("cyan", 11, 10),
            v("gray", 10, 9), v("green", 3, 11), v("light_blue", 7, 10), v("light_gray", 5, 10),
            v("lime", 3, 10), v("magenta", 1, 10), v("orange", 9, 4), v("pink", 2, 9),
            v("purple", 8, 3), v("red", 4, 8), v("white", 3, 5), v("yellow", 3, 1)));
        map.put(EntityType.SHULKER, icon(7, 6));
        map.put(EntityType.SILVERFISH, icon(7, 4));
        map.put(EntityType.SKELETON, icon(7, 3));
        map.put(EntityType.SKELETON_HORSE, icon(7, 2));
        map.put(EntityType.SLIME, icon(6, 7));
        map.put(EntityType.SNOW_GOLEM, icon(8, 4));
        map.put(EntityType.SPIDER, icon(4, 7));
        map.put(EntityType.SQUID, icon(3, 7));
        map.put(EntityType.STRAY, icon(2, 7));
        map.put(EntityType.STRIDER, icon(5, 4, EntityIconManager::striderVariant,
            v("false", 5, 4), v("true", 1, 7)));
        map.put(EntityType.TRADER_LLAMA, icon(2, 12, EntityIconManager::llamaVariant,
            v("0", 2, 12), v("1", 4, 4), v("2", 12, 1), v("3", 10, 7)));
        map.put(EntityType.TROPICAL_FISH, icon(6, 1));
        map.put(EntityType.TURTLE, icon(5, 6));
        map.put(EntityType.VEX, icon(4, 6));
        // The cellmap's villager_profession variant table is empty (no vanilla profession got its
        // own cell), so every villager currently renders its base cell regardless of profession;
        // the resolver is still wired up so a future non-empty table needs no code change here.
        map.put(EntityType.VILLAGER, icon(8, 8, EntityIconManager::villagerVariant));
        map.put(EntityType.VINDICATOR, icon(3, 6));
        map.put(EntityType.WANDERING_TRADER, icon(2, 6));
        map.put(EntityType.WITCH, icon(4, 2));
        map.put(EntityType.WITHER, icon(4, 1));
        map.put(EntityType.WITHER_SKELETON, icon(3, 4));
        map.put(EntityType.WOLF, icon(1, 4, EntityIconManager::wolfVariant,
            v("angry", 8, 13), v("tame", 6, 4)));
        map.put(EntityType.ZOGLIN, icon(1, 2));
        map.put(EntityType.ZOMBIE, icon(1, 3));
        map.put(EntityType.ZOMBIE_HORSE, icon(2, 2));
        map.put(EntityType.ZOMBIE_VILLAGER, icon(2, 1));
        map.put(EntityType.ZOMBIFIED_PIGLIN, icon(1, 1));

        return Map.copyOf(map);
    }
}
