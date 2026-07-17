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
 * <p>Every rectangle in {@link #UV_TABLE} is grounded in real vanilla 1.17.1 assets, not guessed,
 * via one of two methods (see the per-group comments below for which applies): (1) read directly
 * off the decompiled entity model classes' {@code getTexturedModelData()}/{@code getModelData()}
 * head cuboid ({@code uv(u, v).cuboid(x, y, z, w, h, d)}) and converted with {@link #headBox}; or
 * (2), for species whose visible "head" isn't a single front-facing box (fish, squid, guardian,
 * shulker, the horse family, etc.), located by extracting the actual PNG from the vanilla client
 * jar (mc/textures/entity/**) and visually pinpointing the eyes/face pixel rect directly with
 * {@link #px}. The box-to-UV formula for method (1) is Minecraft's standard cuboid unwrap: for a
 * box of size (w, h, d) at texture offset (u, v), the box's front/north face lands at pixel rect
 * {@code (u+d, v+d)} to {@code (u+d+w, v+d+h)}.
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
        final FaceUv villagerHead = headBox(0, 0, 8, 10, 8, 64, 64);
        map.put(EntityType.VILLAGER, villagerHead);
        map.put(EntityType.ZOMBIE_VILLAGER, villagerHead);
        // WanderingTraderEntity extends VillagerEntity and reuses VillagerEntityModel verbatim.
        map.put(EntityType.WANDERING_TRADER, villagerHead);
        map.put(EntityType.WITCH, headBox(0, 0, 8, 10, 8, 64, 128));
        map.put(EntityType.IRON_GOLEM, headBox(0, 0, 8, 10, 8, 128, 128));
        // IllagerEntityModel head is the same VillagerResemblingModel shape with a slightly larger
        // nose cuboid; pixel-measured against illager/{evoker,vindicator,pillager,illusioner}.png
        // (all 64x64, identical layout) rather than headBox() because of that nose bulge. Shared
        // verbatim by the whole illager family per docs/reference-specs/radar-icons.md's grouping.
        final FaceUv illagerHead = px(6, 7, 18, 17, 64, 64);
        map.put(EntityType.EVOKER, illagerHead);
        map.put(EntityType.VINDICATOR, illagerHead);
        map.put(EntityType.PILLAGER, illagerHead);
        map.put(EntityType.ILLUSIONER, illagerHead);

        // SpiderEntityModel head = uv(32,4).cuboid(8,8,8); CaveSpiderEntity reuses SpiderEntityModel.
        final FaceUv spiderHead = headBox(32, 4, 8, 8, 8, 64, 32);
        map.put(EntityType.SPIDER, spiderHead);
        map.put(EntityType.CAVE_SPIDER, spiderHead);
        // SilverfishEntityModel/EndermiteEntityModel: tiny 64x32 body-segment textures with no
        // headBox-sized cuboid; pixel-measured against silverfish.png/endermite.png - both put
        // their small dark eye pair in the same top-left segment corner.
        final FaceUv tinySegmentHead = px(0, 0, 8, 5, 64, 32);
        map.put(EntityType.SILVERFISH, tinySegmentHead);
        map.put(EntityType.ENDERMITE, tinySegmentHead);

        // Species-specific quadruped head boxes (all on 64x32 textures), read from
        // CowEntityModel/SheepEntityModel/ChickenEntityModel/WolfEntityModel(.realHead)/
        // OcelotEntityModel(.head, shared by CatEntityModel - CatEntity resolves its own
        // breed-specific texture file per instance via getTexture(entity), independent of this UV).
        final FaceUv cowHead = headBox(0, 0, 8, 8, 6, 64, 32);
        map.put(EntityType.COW, cowHead);
        // MooshroomEntity extends CowEntity and reuses CowEntityModel/its 64x32 head layout
        // verbatim (verified against cow/red_mooshroom.png - same eye pixels, same offset).
        map.put(EntityType.MOOSHROOM, cowHead);
        map.put(EntityType.SHEEP, headBox(0, 0, 6, 6, 8, 64, 32));
        map.put(EntityType.CHICKEN, headBox(0, 0, 4, 6, 3, 64, 32));
        map.put(EntityType.WOLF, headBox(0, 0, 6, 6, 4, 64, 32));
        final FaceUv catHead = headBox(0, 0, 5, 4, 5, 64, 32);
        map.put(EntityType.CAT, catHead);
        map.put(EntityType.OCELOT, catHead);
        // RabbitEntityModel head, pixel-measured against rabbit/brown.png (eyes + pink nose).
        map.put(EntityType.RABBIT, px(33, 0, 48, 11, 64, 32));
        // BlazeEntityModel head = uv(0,0).cuboid(8,8,8) on the same 64x32 layout as biped64x32
        // above (verified pixel-for-pixel against blaze.png), so it reuses that constant.
        map.put(EntityType.BLAZE, biped64x32);

        // Horse family: HorseBaseModel's visible head is a rotated multi-part assembly (an outer
        // "head_parts" trunk cuboid plus a nested "head" sub-cuboid plus mane/ear/mouth pieces),
        // not a single front-facing box headBox() can express. Pixel-measured instead against
        // horse/{horse_brown,donkey,mule,horse_skeleton,horse_zombie}.png: despite the rotated
        // unwrap, one rect at this offset lands on both eyes + the nose/nostril shading for every
        // variant, since HorseEntityModel/DonkeyEntityModel/etc. all share the base head geometry.
        final FaceUv horseHead = px(0, 18, 19, 34, 64, 64);
        map.put(EntityType.HORSE, horseHead);
        map.put(EntityType.DONKEY, horseHead);
        map.put(EntityType.MULE, horseHead);
        map.put(EntityType.SKELETON_HORSE, horseHead);
        map.put(EntityType.ZOMBIE_HORSE, horseHead);

        // Piglin family (PiglinEntityModel, shared by PiglinBruteEntityModel and reused for
        // ZombifiedPiglinEntityModel's snout shape): pixel-measured against
        // piglin/{piglin,piglin_brute,zombified_piglin}.png - eyes sit at the same offset on all
        // three despite the different skin tones/decay overlay.
        final FaceUv piglinHead = px(0, 7, 17, 16, 64, 64);
        map.put(EntityType.PIGLIN, piglinHead);
        map.put(EntityType.PIGLIN_BRUTE, piglinHead);
        map.put(EntityType.ZOMBIFIED_PIGLIN, piglinHead);

        // HoglinEntityModel head, pixel-measured against hoglin/hoglin.png (128x64) - eyes plus
        // tusk row. ZoglinEntity extends HoglinEntity and reuses the same model/layout verbatim
        // (its texture just recolors/decays the same region into an exposed-skull look).
        final FaceUv hoglinHead = px(76, 0, 104, 18, 128, 64);
        map.put(EntityType.HOGLIN, hoglinHead);
        map.put(EntityType.ZOGLIN, hoglinHead);

        // Every remaining species below has its own model with no shared head shape - each rect
        // is pixel-measured individually against that species' own texture.
        map.put(EntityType.FOX, px(2, 10, 17, 16, 48, 32));
        map.put(EntityType.PANDA, px(2, 13, 19, 22, 64, 64));
        // LlamaEntityModel head, pixel-measured against llama/brown.png (128x64). TraderLlamaEntity
        // extends LlamaEntity and reuses the same model/base coat textures verbatim - only its
        // decor-carpet overlay differs, which this base-layer crop doesn't touch.
        final FaceUv llamaHead = px(0, 16, 11, 28, 128, 64);
        map.put(EntityType.LLAMA, llamaHead);
        map.put(EntityType.TRADER_LLAMA, llamaHead);
        map.put(EntityType.PARROT, px(0, 0, 10, 8, 32, 32));
        map.put(EntityType.POLAR_BEAR, px(6, 8, 15, 15, 128, 64));
        // GoatEntityModel: no clean single "head" cuboid (horns/ears occupy most of the top-left
        // UV region); pixel-measured judgment call - the muzzle region at the bottom of
        // goat/goat.png carries both eyes plus the nose, the most face-like crop available.
        map.put(EntityType.GOAT, px(33, 53, 59, 64, 64, 64));
        // AxolotlEntityModel head, pixel-measured against axolotl/axolotl_blue.png.
        map.put(EntityType.AXOLOTL, px(0, 2, 16, 16, 64, 64));
        // BeeEntityModel head, pixel-measured against bee/bee.png.
        map.put(EntityType.BEE, px(4, 7, 21, 18, 64, 64));
        // BatEntityModel head, pixel-measured against bat.png.
        map.put(EntityType.BAT, px(2, 5, 12, 13, 64, 64));
        // DolphinEntityModel head/snout (top-down), pixel-measured against dolphin.png.
        map.put(EntityType.DOLPHIN, px(0, 0, 20, 12, 64, 64));
        // SnowGolemEntityModel pumpkin head, pixel-measured against snow_golem.png.
        map.put(EntityType.SNOW_GOLEM, px(3, 4, 17, 13, 64, 64));
        // PhantomEntityModel head (top-down view, per its top-mounted eyes), pixel-measured
        // against phantom.png.
        map.put(EntityType.PHANTOM, px(0, 0, 12, 8, 64, 64));
        // StriderEntityModel head, pixel-measured against strider/strider.png (64x128).
        map.put(EntityType.STRIDER, px(8, 21, 34, 29, 64, 128));
        // VexEntityModel head, pixel-measured against illager/vex.png.
        map.put(EntityType.VEX, px(0, 7, 16, 16, 64, 64));
        // RavagerEntityModel head, pixel-measured against illager/ravager.png (128x128).
        map.put(EntityType.RAVAGER, px(13, 25, 31, 33, 128, 128));
        // GhastEntityModel head/face, pixel-measured against ghast/ghast.png (64x32).
        map.put(EntityType.GHAST, px(16, 16, 32, 28, 64, 32));

        // No single facing "head" cuboid: judgment-call crops per docs/reference-specs/radar-icons.md
        // guidance, pixel-measured against the real texture for a region that reads as a face.
        // GuardianEntityModel's whole body is its "face" - crop centers the single large eye.
        // GuardianEntity/ElderGuardianEntity share the same model/UV layout at different scale.
        final FaceUv guardianFace = px(14, 12, 30, 28, 64, 64);
        map.put(EntityType.GUARDIAN, guardianFace);
        map.put(EntityType.ELDER_GUARDIAN, guardianFace);
        // SquidEntityModel/front-of-body panel carrying the two eye dots; GlowSquidEntity reuses
        // SquidEntityModel verbatim with a glowing texture at the same offset.
        final FaceUv squidFace = px(12, 12, 24, 24, 64, 32);
        map.put(EntityType.SQUID, squidFace);
        map.put(EntityType.GLOW_SQUID, squidFace);
        // Fish use a side head profile (both eyes visible from the unwrap's lateral view).
        map.put(EntityType.COD, px(8, 0, 22, 10, 32, 32));
        map.put(EntityType.SALMON, px(22, 1, 32, 8, 32, 32));
        map.put(EntityType.PUFFERFISH, px(6, 7, 22, 13, 32, 32));
        map.put(EntityType.TROPICAL_FISH, px(2, 2, 15, 9, 32, 32));
        // TurtleEntityModel head (side profile), pixel-measured against turtle/big_sea_turtle.png.
        map.put(EntityType.TURTLE, px(0, 1, 18, 9, 128, 64));
        // ShulkerEntityModel's tiny extruded "face" foot at the base of the shell, pixel-measured
        // against shulker/shulker.png.
        map.put(EntityType.SHULKER, px(0, 55, 16, 64, 64, 64));
        // WitherEntityModel's central head (ignoring the two side heads), pixel-measured against
        // wither/wither.png.
        map.put(EntityType.WITHER, px(6, 2, 20, 15, 64, 64));
        // EnderDragonEntityModel head region, pixel-measured against enderdragon/dragon.png (256x256).
        map.put(EntityType.ENDER_DRAGON, px(120, 40, 180, 65, 256, 256));

        return Map.copyOf(map);
    }
}
