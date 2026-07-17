package cn.net.rms.confluxmap.mc.radar;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.util.Identifier;

/**
 * Render-thread lookup from a live entity to a small sub-region ("face") of that entity's
 * already-loaded skin/mob texture, for drawing VoxelMap-style head icons on the radar instead
 * of the plain shaped dots (docs/reference-specs/radar-icons.md sec 2 documents the reference
 * implementation's much heavier runtime model-baking approach; that is out of scope here). This
 * is deliberately a "sub-UV" approach: no offscreen render, no pixel readback, no
 * {@code NativeImageBackedTexture} baking - just binding a texture Minecraft already has
 * resident on the GPU and drawing a small quad with hand-picked UV coordinates, exactly like
 * the vanilla tab-list player-head icons do.
 *
 * <p><b>Players</b> use the real equipped skin: {@link AbstractClientPlayerEntity#getSkinTexture()}
 * (always returns a usable identifier - the client's default per-UUID skin if the real one
 * hasn't finished downloading yet) sub-UV'd to the 8x8 face region at (8,8)-(16,16) of the 64x64
 * skin, with the separate 8x8 hat-layer overlay at (40,8)-(48,16) composited on top - both
 * verified directly against {@code BipedEntityModel.getModelData} (head/hat cuboids), since the
 * player model and the skin layout are one and the same box-UV scheme.
 *
 * <p><b>Mobs</b> use {@code EntityRenderDispatcher#getRenderer(entity).getTexture(entity)} to
 * resolve the correct texture identifier <em>per instance</em> - this automatically handles
 * per-entity texture variants (horse coat color, cat/ocelot breed, etc.) exactly like the
 * vanilla renderer does, without this class needing to know about any of them. Paired with that
 * is a static {@link EntityType}-to-face-rectangle table below: the face position within the
 * texture is a property of the shared *model*, not of which specific texture file a given
 * instance resolves to, so one fractional UV rect per species is enough regardless of variant.
 *
 * <p>Every rectangle in {@link #UV_TABLE} was read directly off the decompiled vanilla 1.17.1
 * entity model classes (each species' {@code getTexturedModelData()}/{@code getModelData()}
 * head cuboid: {@code uv(u, v).cuboid(x, y, z, w, h, d)}), not guessed - see the per-group
 * comments below for the source cuboid. The box-to-UV formula used throughout is Minecraft's
 * standard cuboid unwrap: for a box of size (w, h, d) at texture offset (u, v), the box's
 * front/north face lands at pixel rect {@code (u+d, v+d)} to {@code (u+d+w, v+d+h)}.
 *
 * <p>Species without a table entry (including every non-vanilla/modded entity) simply get no
 * icon here ({@link #iconFor} returns {@code null}); the caller keeps drawing the existing
 * shaped-dot marker for those, unchanged.
 */
public final class EntityIconManager {
    /** One species' pre-computed fractional face UV rect (0..1), independent of which specific per-instance texture file is bound. */
    private record FaceUv(float u0, float v0, float u1, float v1) {
    }

    /**
     * The primary-layer (and, for players only, hat-overlay-layer) sub-UV region to draw as this
     * entity's radar icon. {@code overlayTexture} is {@code null} for every non-player entity.
     */
    public record FaceIcon(
        Identifier texture, float u0, float v0, float u1, float v1,
        Identifier overlayTexture, float ou0, float ov0, float ou1, float ov1
    ) {
        public boolean hasOverlay() {
            return overlayTexture != null;
        }
    }

    /** Player skins are always 64x64 in 1.17.1 (legacy 64x32 skins are upgraded on load). */
    private static final int SKIN_SIZE = 64;
    /** BipedEntityModel.getModelData: head = uv(0,0).cuboid(w=8,h=8,d=8). */
    private static final FaceUv PLAYER_FACE = px(8, 8, 16, 16, SKIN_SIZE, SKIN_SIZE);
    /** BipedEntityModel.getModelData: hat = uv(32,0).cuboid(w=8,h=8,d=8). */
    private static final FaceUv PLAYER_HAT = px(40, 8, 48, 16, SKIN_SIZE, SKIN_SIZE);

    private static final Map<EntityType<?>, FaceUv> UV_TABLE = buildUvTable();

    private final MinecraftClient client;

    public EntityIconManager(final MinecraftClient client) {
        this.client = client;
    }

    /**
     * Render thread. Returns the face icon for this live entity, or {@code null} if none is
     * available (unknown/modded species, or the entity's renderer/texture lookup failed) - the
     * caller should fall back to the shaped-dot marker in that case.
     */
    public FaceIcon iconFor(final Entity entity) {
        if (entity instanceof AbstractClientPlayerEntity) {
            return playerIcon((AbstractClientPlayerEntity) entity);
        }
        return mobIcon(entity);
    }

    private FaceIcon playerIcon(final AbstractClientPlayerEntity player) {
        final Identifier skin = player.getSkinTexture();
        return new FaceIcon(
            skin, PLAYER_FACE.u0(), PLAYER_FACE.v0(), PLAYER_FACE.u1(), PLAYER_FACE.v1(),
            skin, PLAYER_HAT.u0(), PLAYER_HAT.v0(), PLAYER_HAT.u1(), PLAYER_HAT.v1()
        );
    }

    private FaceIcon mobIcon(final Entity entity) {
        final FaceUv uv = UV_TABLE.get(entity.getType());
        if (uv == null) {
            return null;
        }
        final EntityRenderer<? super Entity> renderer = client.getEntityRenderDispatcher().getRenderer(entity);
        if (renderer == null) {
            return null;
        }
        final Identifier texture = renderer.getTexture(entity);
        if (texture == null) {
            return null;
        }
        return new FaceIcon(texture, uv.u0(), uv.v0(), uv.u1(), uv.v1(), null, 0f, 0f, 0f, 0f);
    }

    private static FaceUv px(final int x0, final int y0, final int x1, final int y1, final int texW, final int texH) {
        return new FaceUv(x0 / (float) texW, y0 / (float) texH, x1 / (float) texW, y1 / (float) texH);
    }

    /** Standard Minecraft cuboid-to-UV unwrap: front face of a (w,h,d) box at texture offset (u,v). */
    private static FaceUv headBox(final int u, final int v, final int w, final int h, final int d, final int texW, final int texH) {
        final int x0 = u + d;
        final int y0 = v + d;
        return px(x0, y0, x0 + w, y0 + h, texW, texH);
    }

    private static Map<EntityType<?>, FaceUv> buildUvTable() {
        final Map<EntityType<?>, FaceUv> map = new HashMap<>();

        // Standard humanoid head box (0,0) cuboid(8,8,8), shared verbatim by BipedEntityModel and
        // every mob model that doesn't override "head" (ZombieEntityModel/AbstractZombieModel,
        // SkeletonEntityModel), plus CreeperEntityModel/EndermanEntityModel/PigEntityModel which
        // independently use the exact same (0,0,8,8,8) box, and SlimeEntityModel's outer-shell
        // "cube" (the translucent overlay mesh - picked over the smaller inner body+eyes because
        // a single UV rect can't reproduce the eyes, which are separate overlapping cuboids).
        final FaceUv biped64x64 = headBox(0, 0, 8, 8, 8, 64, 64);
        map.put(EntityType.ZOMBIE, biped64x64);
        map.put(EntityType.HUSK, biped64x64);
        map.put(EntityType.DROWNED, biped64x64);
        final FaceUv biped64x32 = headBox(0, 0, 8, 8, 8, 64, 32);
        map.put(EntityType.SKELETON, biped64x32);
        map.put(EntityType.STRAY, biped64x32);
        map.put(EntityType.WITHER_SKELETON, biped64x32);
        map.put(EntityType.CREEPER, biped64x32);
        map.put(EntityType.ENDERMAN, biped64x32);
        map.put(EntityType.SLIME, biped64x32);
        map.put(EntityType.MAGMA_CUBE, biped64x32);
        map.put(EntityType.PIG, biped64x32);

        // VillagerResemblingModel head = uv(0,0).cuboid(8,10,8), shared verbatim by
        // VillagerEntityModel, ZombieVillagerEntityModel and WitchEntityModel.
        // IronGolemEntityModel independently uses the same (0,0,8,10,8) box on its 128x128 texture.
        map.put(EntityType.VILLAGER, headBox(0, 0, 8, 10, 8, 64, 64));
        map.put(EntityType.ZOMBIE_VILLAGER, headBox(0, 0, 8, 10, 8, 64, 64));
        map.put(EntityType.WITCH, headBox(0, 0, 8, 10, 8, 64, 128));
        map.put(EntityType.IRON_GOLEM, headBox(0, 0, 8, 10, 8, 128, 128));

        // SpiderEntityModel head = uv(32,4).cuboid(8,8,8); CaveSpiderEntity reuses SpiderEntityModel.
        final FaceUv spiderHead = headBox(32, 4, 8, 8, 8, 64, 32);
        map.put(EntityType.SPIDER, spiderHead);
        map.put(EntityType.CAVE_SPIDER, spiderHead);

        // Species-specific quadruped head boxes (all on 64x32 textures), read from
        // CowEntityModel/SheepEntityModel/ChickenEntityModel/WolfEntityModel(.realHead)/
        // OcelotEntityModel(.head, shared by CatEntityModel - CatEntity resolves its own
        // breed-specific texture file per instance via getTexture(entity), independent of this UV).
        map.put(EntityType.COW, headBox(0, 0, 8, 8, 6, 64, 32));
        map.put(EntityType.SHEEP, headBox(0, 0, 6, 6, 8, 64, 32));
        map.put(EntityType.CHICKEN, headBox(0, 0, 4, 6, 3, 64, 32));
        map.put(EntityType.WOLF, headBox(0, 0, 6, 6, 4, 64, 32));
        final FaceUv catHead = headBox(0, 0, 5, 4, 5, 64, 32);
        map.put(EntityType.CAT, catHead);
        map.put(EntityType.OCELOT, catHead);

        // HORSE is deliberately omitted: HorseEntityModel's visible head is a rotated multi-part
        // assembly (an outer "head_parts" trunk cuboid plus a nested "head" sub-cuboid plus mane/
        // ear/mouth pieces), not a single front-facing box the way every entry above is - no
        // single UV rect produces a recognizable face crop for it. Falls back to the dot marker.

        return Map.copyOf(map);
    }
}
