package cn.net.rms.confluxmap.mc.radar;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.compat.Ids;
import cn.net.rms.confluxmap.compat.Regs;
import cn.net.rms.confluxmap.core.radar.IconBakeCache;
import cn.net.rms.confluxmap.mixin.AgeableMobEntityRendererAccessor;
import cn.net.rms.confluxmap.mc.render.OffscreenCanvas;
import cn.net.rms.confluxmap.mc.render.RenderUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModel;
//#if MC>=12103
//$$ import net.minecraft.client.render.entity.state.LivingEntityRenderState;
//$$ import net.minecraft.entity.EntityPose;
//#endif
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
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
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.util.Identifier;

/**
 * Render-thread facade for radar portraits. Player faces remain direct skin crops; mobs covered
 * by the v0.1.3 face sheet reuse those hand-drawn icons, while newer or modded living entities are
 * lazily baked from their neutral-pose vanilla model into a persistent color atlas.
 */
public final class EntityIconManager implements AutoCloseable {
    private record FaceUv(float u0, float v0, float u1, float v1) {
    }

    private record CellIcon(FaceUv base, Map<String, FaceUv> variants, Function<Entity, String> variantKey) {
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

    private record AtlasSprite(int slot, float u0, float v0, float u1, float v1) {
    }

    /** One portrait region and its source kind. Dynamic portraits bind through this manager. */
    public record FaceIcon(
        Identifier texture, float u0, float v0, float u1, float v1,
        Identifier overlayTexture, float ou0, float ov0, float ou1, float ov1,
        boolean dynamic
    ) {
        public boolean hasOverlay() {
            return overlayTexture != null;
        }
    }

    private static final int SKIN_SIZE = 64;
    private static final float PLAYER_U0 = 8f / SKIN_SIZE;
    private static final float PLAYER_V0 = 8f / SKIN_SIZE;
    private static final float PLAYER_U1 = 16f / SKIN_SIZE;
    private static final float PLAYER_V1 = 16f / SKIN_SIZE;
    private static final float HAT_U0 = 40f / SKIN_SIZE;
    private static final float HAT_V0 = 8f / SKIN_SIZE;
    private static final float HAT_U1 = 48f / SKIN_SIZE;
    private static final float HAT_V1 = 16f / SKIN_SIZE;

    private static final Identifier BUNDLED_SHEET = Ids.of(
        "confluxmap", "textures/radar/entity_icons.png"
    );
    private static final int BUNDLED_SHEET_W = 208;
    private static final int BUNDLED_SHEET_H = 240;
    private static final int BUNDLED_CELL_PX = 16;
    private static final Map<String, CellIcon> BUNDLED_ICONS = buildBundledIcons();

    /** 1.17.1 integer cat variants; later versions resolve registry keys directly. */
    private static final String[] CAT_TYPE_NAMES = {
        "tabby", "black", "red", "siamese", "british_shorthair", "calico",
        "persian", "ragdoll", "white", "jellie", "all_black"
    };

    static final int CELL_PX = 32;
    static final int ATLAS_PX = 1024;
    private static final int CELLS_PER_ROW = ATLAS_PX / CELL_PX;
    private static final int SLOT_COUNT = CELLS_PER_ROW * CELLS_PER_ROW;
    private static final long REFRESH_TICKS = 200;

    private final OffscreenCanvas colorAtlas = new OffscreenCanvas();
    private final IconBakeCache<UUID, AtlasSprite> cache = new IconBakeCache<>(SLOT_COUNT, REFRESH_TICKS);
    private final Map<UUID, WeakReference<Entity>> liveEntities = new HashMap<>();

    private int nextSlot;
    private long clock;

    /** Registers the one-bake-per-client-tick queue drain. */
    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(final MinecraftClient client) {
        assert RenderSystem.isOnRenderThread() : "EntityIconManager.tick() must run on the render thread";
        clock++;
        if (client.world == null) {
            return;
        }
        cache.pollNext(clock).ifPresent(key -> bakeOne(client, key, 0f, clock));
    }

    /** Returns a ready portrait or null while its first bake is queued/failed. */
    public FaceIcon iconFor(final Entity entity) {
        if (entity instanceof AbstractClientPlayerEntity) {
            return playerIcon((AbstractClientPlayerEntity) entity);
        }
        final FaceIcon bundled = bundledIcon(entity);
        if (bundled != null) {
            return bundled;
        }
        if (!(entity instanceof LivingEntity)) {
            return null;
        }
        final UUID key = entity.getUuid();
        liveEntities.put(key, new WeakReference<>(entity));
        final AtlasSprite sprite = cache.request(key, clock).orElse(null);
        return sprite == null ? null : dynamicIcon(sprite);
    }

    static boolean hasBundledIcon(final String entityType) {
        return entityType != null && BUNDLED_ICONS.containsKey(entityType);
    }

    public boolean bindDynamicColor() {
        if (colorAtlas.size() == 0) {
            return false;
        }
        colorAtlas.bindTexture();
        return true;
    }

    /** Resource reload invalidates model-derived portraits. */
    public void invalidateTextures() {
        assert RenderSystem.isOnRenderThread() : "EntityIconManager.invalidateTextures() must run on render thread";
        resetDynamic();
    }

    /** World/session change: entity UUIDs and their live references no longer belong to this atlas. */
    public void onSessionChanged() {
        assert RenderSystem.isOnRenderThread() : "EntityIconManager.onSessionChanged() must run on render thread";
        resetDynamic();
    }

    @Override
    public void close() {
        assert RenderSystem.isOnRenderThread() : "EntityIconManager.close() must run on render thread";
        resetDynamic();
    }

    private void bakeOne(
        final MinecraftClient client,
        final UUID key,
        final float tickDelta,
        final long now
    ) {
        final WeakReference<Entity> reference = liveEntities.get(key);
        final Entity entity = reference == null ? null : reference.get();
        //#if MC>=12108 && MC<12109
        //$$ final boolean wrongWorld = entity == null || entity.getWorld() != client.world;
        //#else
        final boolean wrongWorld = entity == null || entity.getEntityWorld() != client.world;
        //#endif
        if (!(entity instanceof LivingEntity) || wrongWorld) {
            cache.fail(key, now);
            liveEntities.remove(key);
            return;
        }
        try {
            final AtlasSprite current = cache.value(key).orElse(null);
            final AtlasSprite baked = bake(client, (LivingEntity) entity, tickDelta, current);
            if (baked == null) {
                cache.fail(key, now);
                return;
            }
            cache.complete(key, baked, now);
        } catch (final RuntimeException e) {
            cache.fail(key, now);
            ConfluxMapMod.LOGGER.debug("Failed to bake radar portrait for {}", entity.getType(), e);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private AtlasSprite bake(
        final MinecraftClient client,
        final LivingEntity entity,
        final float tickDelta,
        final AtlasSprite current
    ) {
        final EntityRenderer renderer = client.getEntityRenderDispatcher().getRenderer(entity);
        if (!(renderer instanceof LivingEntityRenderer)) {
            ConfluxMapMod.LOGGER.debug("No living renderer for radar portrait {}", entity.getType());
            return null;
        }
        final LivingEntityRenderer livingRenderer = (LivingEntityRenderer) renderer;
        final EntityModel model;
        final Identifier texture;
        //#if MC>=12103
        //$$ final LivingEntityRenderState state = (LivingEntityRenderState) renderer.getAndUpdateRenderState(entity, tickDelta);
        //$$ neutralizePortraitPose(state);
        //$$ model = portraitModel(livingRenderer, state);
        //$$ model.setAngles(state);
        //$$ texture = livingRenderer.getTexture(state);
        //#else
        model = (EntityModel) livingRenderer.getModel();
        model.animateModel(entity, 0f, 0f, 0f);
        model.setAngles(entity, 0f, 0f, 0f, 0f, 0f);
        texture = renderer.getTexture(entity);
        //#endif
        if (texture == null) {
            ConfluxMapMod.LOGGER.debug("No texture for radar portrait {}", entity.getType());
            return null;
        }

        final float[] geometry = EntityHeadGeometry.project(
            model, Regs.entityTypeId(entity.getType()).toString(), 0, 0
        );
        if (geometry.length == 0) {
            ConfluxMapMod.LOGGER.debug("Empty geometry for radar portrait {}", entity.getType());
            return null;
        }
        final AtlasSprite sprite = current == null ? allocateSprite() : current;
        if (sprite == null) {
            return null;
        }
        final int column = sprite.slot() % CELLS_PER_ROW;
        final int row = sprite.slot() / CELLS_PER_ROW;
        translate(geometry, column * CELL_PX, row * CELL_PX);

        final MatrixStack matrices = new MatrixStack();
        colorAtlas.beginPreserving(ATLAS_PX);
        try {
            RenderUtil.clearTargetRect(matrices, column * CELL_PX, row * CELL_PX, CELL_PX, CELL_PX);
            RenderUtil.bindTexture(client, texture);
            RenderUtil.drawProjectedTexturedQuads(matrices, geometry);
        } finally {
            colorAtlas.end(client);
        }
        return sprite;
    }

    //#if MC>=12103
    //$$ private static EntityModel portraitModel(
    //$$     final LivingEntityRenderer renderer,
    //$$     final LivingEntityRenderState state
    //$$ ) {
    //$$     if (!(renderer instanceof AgeableMobEntityRendererAccessor)) {
    //$$         return (EntityModel) renderer.getModel();
    //$$     }
    //$$     final AgeableMobEntityRendererAccessor ageable = (AgeableMobEntityRendererAccessor) renderer;
    //#if MC>=260100
    //$$     return (EntityModel) (state.isBaby
    //#else
    //$$     return (EntityModel) (state.baby
    //#endif
    //$$         ? ageable.confluxmap$getBabyModel()
    //$$         : ageable.confluxmap$getAdultModel());
    //$$ }
    //#endif

    private static void translate(final float[] geometry, final float x, final float y) {
        for (int i = 0; i < geometry.length; i += 5) {
            geometry[i] += x;
            geometry[i + 1] += y;
        }
    }

    private AtlasSprite allocateSprite() {
        if (nextSlot >= SLOT_COUNT) {
            // The configured radar cap is below this, but a very long session can accumulate stale
            // UUIDs. Reset as one atomic generation instead of reusing dirty cells.
            resetDynamic();
        }
        final int slot = nextSlot++;
        final int column = slot % CELLS_PER_ROW;
        final int row = slot / CELLS_PER_ROW;
        final float u0 = column / (float) CELLS_PER_ROW;
        final float u1 = (column + 1) / (float) CELLS_PER_ROW;
        // Canvas Y grows downward, so row zero is written at the framebuffer texture's top
        // (V=1). Flip the complete atlas row, not only the orientation inside that row.
        final float v0 = atlasTopV(row);
        final float v1 = atlasBottomV(row);
        return new AtlasSprite(slot, u0, v0, u1, v1);
    }

    //#if MC>=12103
    //$$ /** Keeps model/material variants but removes the live entity's transient viewing pose. */
    //$$ static void neutralizePortraitPose(final LivingEntityRenderState state) {
    //$$     state.bodyYaw = 0f;
    //#if MC>=12105
    //$$     state.relativeHeadYaw = 0f;
    //$$     state.limbSwingAnimationProgress = 0f;
    //$$     state.limbSwingAmplitude = 0f;
    //#else
    //$$     state.yawDegrees = 0f;
    //$$     state.limbFrequency = 0f;
    //$$     state.limbAmplitudeMultiplier = 0f;
    //#endif
    //$$     state.pitch = 0f;
    //$$     state.age = 0f;
    //$$     state.deathTime = 0f;
    //$$     state.flipUpsideDown = false;
    //$$     state.sneaking = false;
    //$$     state.pose = EntityPose.STANDING;
    //$$ }
    //#endif

    static float atlasTopV(final int row) {
        return 1f - row / (float) CELLS_PER_ROW;
    }

    static float atlasBottomV(final int row) {
        return 1f - (row + 1) / (float) CELLS_PER_ROW;
    }

    private void resetDynamic() {
        cache.clear();
        liveEntities.clear();
        colorAtlas.close();
        nextSlot = 0;
    }

    private static FaceIcon dynamicIcon(final AtlasSprite sprite) {
        return new FaceIcon(
            null, sprite.u0(), sprite.v0(), sprite.u1(), sprite.v1(),
            null, 0f, 0f, 0f, 0f, true
        );
    }

    private static FaceIcon bundledIcon(final Entity entity) {
        final String entityType = Regs.entityTypeId(entity.getType()).toString();
        final CellIcon icon = BUNDLED_ICONS.get(entityType);
        if (icon == null) {
            return null;
        }
        final FaceUv uv = icon.resolve(entity);
        return new FaceIcon(
            BUNDLED_SHEET, uv.u0(), uv.v0(), uv.u1(), uv.v1(),
            null, 0f, 0f, 0f, 0f, false
        );
    }

    private static FaceUv bundledCell(final int row, final int column) {
        final int x = (column - 1) * BUNDLED_CELL_PX;
        final int y = (row - 1) * BUNDLED_CELL_PX;
        return new FaceUv(
            x / (float) BUNDLED_SHEET_W,
            y / (float) BUNDLED_SHEET_H,
            (x + BUNDLED_CELL_PX) / (float) BUNDLED_SHEET_W,
            (y + BUNDLED_CELL_PX) / (float) BUNDLED_SHEET_H
        );
    }

    private static void putBundled(
        final Map<String, CellIcon> icons,
        final String entity,
        final int row,
        final int column
    ) {
        icons.put("minecraft:" + entity, new CellIcon(bundledCell(row, column), Map.of(), null));
    }

    @SafeVarargs
    private static void putBundled(
        final Map<String, CellIcon> icons,
        final String entity,
        final int row,
        final int column,
        final Function<Entity, String> variantKey,
        final Map.Entry<String, FaceUv>... variants
    ) {
        icons.put(
            "minecraft:" + entity,
            new CellIcon(bundledCell(row, column), Map.ofEntries(variants), variantKey)
        );
    }

    private static Map.Entry<String, FaceUv> variant(
        final String key,
        final int row,
        final int column
    ) {
        return Map.entry(key, bundledCell(row, column));
    }

    private static String sheepVariant(final Entity entity) {
        return ((SheepEntity) entity).getColor().getName();
    }

    private static String mooshroomVariant(final Entity entity) {
        //#if MC>=12100
        //$$ return ((MooshroomEntity) entity).getVariant().asString();
        //#elseif MC>=11900
        //$$ return ((MooshroomEntity) entity).getVariant().asString();
        //#else
        return ((MooshroomEntity) entity).getMooshroomType().name().toLowerCase(Locale.ROOT);
        //#endif
    }

    private static String catVariant(final Entity entity) {
        //#if MC>=12100
        //$$ return ((CatEntity) entity).getVariant().getKey()
        //$$     .map(key -> key.getValue().getPath())
        //$$     .orElse(null);
        //#elseif MC>=11900
        //$$ final String path = ((CatEntity) entity).getVariant().texture().getPath();
        //$$ final int slash = path.lastIndexOf('/');
        //$$ final int dot = path.lastIndexOf('.');
        //$$ return path.substring(slash + 1, dot > slash ? dot : path.length());
        //#else
        final int type = ((CatEntity) entity).getCatType();
        return type >= 0 && type < CAT_TYPE_NAMES.length ? CAT_TYPE_NAMES[type] : null;
        //#endif
    }

    private static String foxVariant(final Entity entity) {
        //#if MC>=12100
        //$$ return ((FoxEntity) entity).getVariant().asString();
        //#elseif MC>=11900
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
        //#elseif MC>=11900
        //$$ return String.valueOf(((RabbitEntity) entity).getVariant().getId());
        //#else
        return String.valueOf(((RabbitEntity) entity).getRabbitType());
        //#endif
    }

    private static String axolotlVariant(final Entity entity) {
        return String.valueOf(((AxolotlEntity) entity).getVariant().getId());
    }

    private static String pandaVariant(final Entity entity) {
        return ((PandaEntity) entity).getProductGene().name().toLowerCase(Locale.ROOT);
    }

    private static String striderVariant(final Entity entity) {
        return String.valueOf(((StriderEntity) entity).isCold());
    }

    private static String wolfVariant(final Entity entity) {
        final WolfEntity wolf = (WolfEntity) entity;
        if (wolf.isTamed()) {
            return "tame";
        }
        return wolf.hasAngerTime() ? "angry" : null;
    }

    /** Base cells from the v0.1.3 sheet table; entities absent here use dynamic portraits. */
    private static Map<String, CellIcon> buildBundledIcons() {
        final Map<String, CellIcon> icons = new HashMap<>();
        putBundled(icons, "axolotl", 3, 9, EntityIconManager::axolotlVariant,
            variant("0", 3, 9), variant("1", 4, 3), variant("2", 6, 11),
            variant("3", 11, 11), variant("4", 12, 12));
        putBundled(icons, "bat", 7, 13);
        putBundled(icons, "bee", 6, 13);
        putBundled(icons, "blaze", 1, 13);
        putBundled(icons, "cat", 6, 6, EntityIconManager::catVariant,
            variant("all_black", 10, 13), variant("black", 5, 13),
            variant("british_shorthair", 12, 8), variant("calico", 10, 12),
            variant("jellie", 9, 10), variant("persian", 9, 1),
            variant("ragdoll", 8, 1), variant("red", 5, 8),
            variant("siamese", 7, 5), variant("tabby", 6, 6), variant("white", 5, 2));
        putBundled(icons, "cave_spider", 9, 12);
        putBundled(icons, "chicken", 7, 12);
        putBundled(icons, "cod", 6, 12);
        putBundled(icons, "cow", 4, 12);
        putBundled(icons, "creeper", 1, 12);
        putBundled(icons, "dolphin", 11, 8);
        putBundled(icons, "donkey", 11, 7);
        putBundled(icons, "drowned", 11, 6);
        putBundled(icons, "elder_guardian", 11, 5);
        putBundled(icons, "ender_dragon", 11, 4);
        putBundled(icons, "enderman", 11, 3);
        putBundled(icons, "endermite", 11, 2);
        putBundled(icons, "evoker", 11, 1);
        putBundled(icons, "fox", 10, 11, EntityIconManager::foxVariant,
            variant("red", 10, 11), variant("snow", 5, 7));
        putBundled(icons, "ghast", 9, 11);
        putBundled(icons, "glow_squid", 8, 11);
        putBundled(icons, "goat", 7, 11);
        putBundled(icons, "guardian", 10, 6);
        putBundled(icons, "hoglin", 10, 5);
        putBundled(icons, "horse", 10, 4);
        putBundled(icons, "husk", 10, 3);
        putBundled(icons, "illusioner", 10, 2);
        putBundled(icons, "iron_golem", 10, 1);
        putBundled(icons, "llama", 3, 12, EntityIconManager::llamaVariant,
            variant("0", 3, 12), variant("1", 5, 1),
            variant("2", 12, 7), variant("3", 1, 11));
        putBundled(icons, "magma_cube", 9, 8);
        putBundled(icons, "mooshroom", 9, 7, EntityIconManager::mooshroomVariant,
            variant("brown", 12, 6), variant("red", 9, 7));
        putBundled(icons, "mule", 9, 6);
        putBundled(icons, "ocelot", 9, 5);
        putBundled(icons, "panda", 9, 2, EntityIconManager::pandaVariant,
            variant("aggressive", 12, 13), variant("brown", 12, 5),
            variant("lazy", 8, 10), variant("playful", 8, 7),
            variant("weak", 5, 3), variant("worried", 3, 3));
        putBundled(icons, "parrot", 6, 8, EntityIconManager::parrotVariant,
            variant("0", 6, 8), variant("1", 12, 11), variant("2", 4, 11),
            variant("3", 3, 2), variant("4", 10, 10));
        putBundled(icons, "phantom", 8, 9);
        putBundled(icons, "pig", 7, 9);
        putBundled(icons, "piglin", 6, 9);
        putBundled(icons, "piglin_brute", 5, 9);
        putBundled(icons, "pillager", 4, 9);
        putBundled(icons, "polar_bear", 8, 6);
        putBundled(icons, "pufferfish", 8, 5);
        putBundled(icons, "rabbit", 12, 4, EntityIconManager::rabbitVariant,
            variant("0", 12, 4), variant("1", 4, 5), variant("2", 4, 13),
            variant("3", 1, 5), variant("4", 5, 11), variant("5", 1, 8),
            variant("99", 11, 12));
        putBundled(icons, "ravager", 7, 8);
        putBundled(icons, "salmon", 2, 8);
        putBundled(icons, "sheep", 3, 5, EntityIconManager::sheepVariant,
            variant("black", 3, 13), variant("blue", 12, 10), variant("brown", 12, 3),
            variant("cyan", 11, 10), variant("gray", 10, 9), variant("green", 3, 11),
            variant("light_blue", 7, 10), variant("light_gray", 5, 10),
            variant("lime", 3, 10), variant("magenta", 1, 10), variant("orange", 9, 4),
            variant("pink", 2, 9), variant("purple", 8, 3), variant("red", 4, 8),
            variant("white", 3, 5), variant("yellow", 3, 1));
        putBundled(icons, "shulker", 7, 6);
        putBundled(icons, "silverfish", 7, 4);
        putBundled(icons, "skeleton", 7, 3);
        putBundled(icons, "skeleton_horse", 7, 2);
        putBundled(icons, "slime", 6, 7);
        putBundled(icons, "snow_golem", 8, 4);
        putBundled(icons, "spider", 4, 7);
        putBundled(icons, "squid", 3, 7);
        putBundled(icons, "stray", 2, 7);
        putBundled(icons, "strider", 5, 4, EntityIconManager::striderVariant,
            variant("false", 5, 4), variant("true", 1, 7));
        putBundled(icons, "trader_llama", 2, 12, EntityIconManager::llamaVariant,
            variant("0", 2, 12), variant("1", 4, 4),
            variant("2", 12, 1), variant("3", 10, 7));
        putBundled(icons, "tropical_fish", 6, 1);
        putBundled(icons, "turtle", 5, 6);
        putBundled(icons, "vex", 4, 6);
        putBundled(icons, "villager", 8, 8);
        putBundled(icons, "vindicator", 3, 6);
        putBundled(icons, "wandering_trader", 2, 6);
        putBundled(icons, "witch", 4, 2);
        putBundled(icons, "wither", 4, 1);
        putBundled(icons, "wither_skeleton", 3, 4);
        putBundled(icons, "wolf", 1, 4, EntityIconManager::wolfVariant,
            variant("angry", 8, 13), variant("tame", 6, 4));
        putBundled(icons, "zoglin", 1, 2);
        putBundled(icons, "zombie", 1, 3);
        putBundled(icons, "zombie_horse", 2, 2);
        putBundled(icons, "zombie_villager", 2, 1);
        putBundled(icons, "zombified_piglin", 1, 1);
        return Map.copyOf(icons);
    }

    private static FaceIcon playerIcon(final AbstractClientPlayerEntity player) {
        //#if MC>=12109
        //$$ final Identifier skin = player.getSkin().body().texturePath();
        //#elseif MC>=12100
        //$$ final Identifier skin = player.getSkinTextures().texture();
        //#else
        final Identifier skin = player.getSkinTexture();
        //#endif
        return new FaceIcon(
            skin, PLAYER_U0, PLAYER_V0, PLAYER_U1, PLAYER_V1,
            skin, HAT_U0, HAT_V0, HAT_U1, HAT_V1,
            false
        );
    }
}
