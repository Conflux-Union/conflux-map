package cn.net.rms.confluxmap.mc.radar;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.compat.Regs;
import cn.net.rms.confluxmap.core.radar.IconBakeCache;
import cn.net.rms.confluxmap.mixin.AgeableMobEntityRendererAccessor;
import cn.net.rms.confluxmap.mc.render.OffscreenCanvas;
import cn.net.rms.confluxmap.mc.render.RenderUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModel;
//#if MC>=12103
//$$ import net.minecraft.client.render.entity.state.LivingEntityRenderState;
//#endif
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Identifier;

/**
 * Render-thread facade for radar portraits. Player faces remain direct skin crops; every living
 * mob is lazily baked from its neutral-pose vanilla model into a persistent color atlas.
 * Callers see one small lookup interface and never handle model or version-specific renderer APIs.
 */
public final class EntityIconManager implements AutoCloseable {
    private record AtlasSprite(int slot, float u0, float v0, float u1, float v1) {
    }

    private record PortraitKey(Identifier entityType, Identifier texture, EntityModel<?> model) {
    }

    private record ObservedPortrait(PortraitKey key, long checkedAt) {
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

    static final int CELL_PX = 32;
    static final int ATLAS_PX = 1024;
    private static final int CELLS_PER_ROW = ATLAS_PX / CELL_PX;
    private static final int SLOT_COUNT = CELLS_PER_ROW * CELLS_PER_ROW;
    private static final int OBSERVATION_CAPACITY = SLOT_COUNT * 4;
    private static final long VARIANT_CHECK_TICKS = 200;
    private static final long RETRY_TICKS = 200;

    private final OffscreenCanvas colorAtlas = new OffscreenCanvas();
    private final IconBakeCache<PortraitKey, AtlasSprite> cache = new IconBakeCache<>(
        SLOT_COUNT, RETRY_TICKS
    );
    private final Map<PortraitKey, WeakReference<LivingEntity>> liveEntities = new HashMap<>();
    private final Map<UUID, ObservedPortrait> observedPortraits = new HashMap<>();

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
        cache.pollNext(clock).ifPresent(key -> bakeOne(client, key, clock));
    }

    /** Returns a ready portrait or null while its first distinct appearance is queued/failed. */
    public FaceIcon iconFor(final Entity entity) {
        if (entity instanceof AbstractClientPlayerEntity) {
            return playerIcon((AbstractClientPlayerEntity) entity);
        }
        if (!(entity instanceof LivingEntity)) {
            return null;
        }
        final LivingEntity living = (LivingEntity) entity;
        final UUID entityId = entity.getUuid();
        ObservedPortrait observed = observedPortraits.get(entityId);
        if (observed == null || clock - observed.checkedAt() >= VARIANT_CHECK_TICKS) {
            PortraitKey key = observed == null ? null : observed.key();
            try {
                final PortraitKey resolved = resolvePortrait(MinecraftClient.getInstance(), living, 0f);
                if (resolved != null) {
                    key = resolved;
                }
            } catch (final RuntimeException e) {
                ConfluxMapMod.LOGGER.debug(
                    "Failed to resolve radar portrait appearance for {}", entity.getType(), e
                );
            }
            observed = new ObservedPortrait(key, clock);
            if (!observedPortraits.containsKey(entityId)
                && observedPortraits.size() >= OBSERVATION_CAPACITY) {
                observedPortraits.clear();
            }
            observedPortraits.put(entityId, observed);
        }
        if (observed == null || observed.key() == null) {
            return null;
        }
        final PortraitKey key = observed.key();
        liveEntities.put(key, new WeakReference<>(living));
        final AtlasSprite sprite = cache.request(key, clock).orElse(null);
        return sprite == null ? null : dynamicIcon(sprite);
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

    /** World/session change: observed entities and their live references no longer belong here. */
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
        final PortraitKey key,
        final long now
    ) {
        final WeakReference<LivingEntity> reference = liveEntities.get(key);
        final LivingEntity entity = reference == null ? null : reference.get();
        //#if MC>=12108 && MC<12109
        //$$ final boolean wrongWorld = entity == null || entity.getWorld() != client.world;
        //#else
        final boolean wrongWorld = entity == null || entity.getEntityWorld() != client.world;
        //#endif
        if (entity == null || wrongWorld) {
            cache.fail(key, now);
            liveEntities.remove(key);
            return;
        }
        try {
            final AtlasSprite current = cache.value(key).orElse(null);
            final AtlasSprite baked = bake(client, key, current);
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
    private PortraitKey resolvePortrait(
        final MinecraftClient client,
        final LivingEntity entity,
        final float tickDelta
    ) {
        final EntityRenderer renderer = client.getEntityRenderDispatcher().getRenderer(entity);
        if (!(renderer instanceof LivingEntityRenderer)) {
            return null;
        }
        final LivingEntityRenderer livingRenderer = (LivingEntityRenderer) renderer;
        final EntityModel model;
        final Identifier texture;
        //#if MC>=12103
        //$$ final LivingEntityRenderState state = (LivingEntityRenderState) renderer.getAndUpdateRenderState(entity, tickDelta);
        //$$ model = portraitModel(livingRenderer, state);
        //$$ texture = livingRenderer.getTexture(state);
        //#else
        model = (EntityModel) livingRenderer.getModel();
        texture = renderer.getTexture(entity);
        //#endif
        if (texture == null) {
            return null;
        }
        return new PortraitKey(Regs.entityTypeId(entity.getType()), texture, model);
    }

    private AtlasSprite bake(
        final MinecraftClient client,
        final PortraitKey key,
        final AtlasSprite current
    ) {
        final float[] geometry = EntityHeadGeometry.projectNeutral(
            key.model(), key.entityType().toString(), 0, 0
        );
        if (geometry.length == 0) {
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
        matrices.translate(0f, 0f, OffscreenCanvas.atlasDrawPlaneZ());
        colorAtlas.beginPreserving(ATLAS_PX);
        try {
            RenderUtil.clearTargetRect(matrices, column * CELL_PX, row * CELL_PX, CELL_PX, CELL_PX);
            RenderUtil.bindTexture(client, key.texture());
            RenderUtil.enableTargetScissor(
                column * CELL_PX, row * CELL_PX, CELL_PX, CELL_PX, ATLAS_PX
            );
            try {
                RenderUtil.drawProjectedTexturedQuads(matrices, geometry);
            } finally {
                RenderUtil.disableScissor();
            }
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
            // A heavily modded session can exceed the atlas' distinct appearance count. Reset as
            // one atomic generation instead of reusing cells still referenced by cached icons.
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

    static float atlasTopV(final int row) {
        return 1f - row / (float) CELLS_PER_ROW;
    }

    static float atlasBottomV(final int row) {
        return 1f - (row + 1) / (float) CELLS_PER_ROW;
    }

    private void resetDynamic() {
        cache.clear();
        liveEntities.clear();
        observedPortraits.clear();
        colorAtlas.close();
        nextSlot = 0;
    }

    private static FaceIcon dynamicIcon(final AtlasSprite sprite) {
        return new FaceIcon(
            null, sprite.u0(), sprite.v0(), sprite.u1(), sprite.v1(),
            null, 0f, 0f, 0f, 0f, true
        );
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
