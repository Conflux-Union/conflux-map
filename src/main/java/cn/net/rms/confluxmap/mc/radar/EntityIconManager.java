package cn.net.rms.confluxmap.mc.radar;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.NativeImages;
import cn.net.rms.confluxmap.compat.Regs;
import cn.net.rms.confluxmap.core.radar.IconBakeCache;
import cn.net.rms.confluxmap.core.util.Argb;
import cn.net.rms.confluxmap.mixin.AgeableMobEntityRendererAccessor;
import cn.net.rms.confluxmap.mc.render.OffscreenCanvas;
import cn.net.rms.confluxmap.mc.render.RenderUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.IntBinaryOperator;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.texture.NativeImage;
//#if MC>=12103
//$$ import net.minecraft.client.render.entity.state.LivingEntityRenderState;
//#endif
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

/**
 * Render-thread facade for radar portraits. Player faces remain direct skin crops; every living
 * mob is lazily baked from its neutral-pose vanilla model into a persistent color atlas.
 * Callers see one small lookup interface and never handle model or version-specific renderer APIs.
 */
public final class EntityIconManager implements AutoCloseable {
    private record AtlasSprite(
        int slot,
        float u0,
        float v0,
        float u1,
        float v1,
        float widthScale,
        float heightScale
    ) {
    }

    private record PortraitKey(Identifier entityType, Identifier texture, EntityModel<?> model) {
    }

    private record ObservedPortrait(PortraitKey key, long checkedAt) {
    }

    private record TextureRequest(PortraitKey key, int generation) {
    }

    /** One portrait region and its source kind. Dynamic portraits bind through this manager. */
    public record FaceIcon(
        Identifier texture, float u0, float v0, float u1, float v1,
        Identifier overlayTexture, float ou0, float ov0, float ou1, float ov1,
        boolean dynamic, float widthScale, float heightScale
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
    private final PortraitTextureLoader<TextureRequest> textureLoader;
    private final Set<Identifier> unreadableSourceTextures = new java.util.HashSet<>();

    private int nextSlot;
    private int generation;
    private long clock;

    public EntityIconManager(final Executor worker) {
        textureLoader = new PortraitTextureLoader<>(worker);
    }

    /** Registers the one-bake-per-client-tick queue drain. */
    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(final MinecraftClient client) {
        assert RenderSystem.isOnRenderThread() : "EntityIconManager.tick() must run on the render thread";
        clock++;
        textureLoader.poll().ifPresent(result -> finishBake(client, result));
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
        final TextureRequest request = new TextureRequest(key, generation);
        final AtlasSprite sprite = textureLoader.isLoading(request)
            ? cache.value(key).orElse(null)
            : cache.request(key, clock).orElse(null);
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
            final float[] geometry = EntityHeadGeometry.projectNeutral(
                key.model(), key.entityType().toString(), 0, 0
            );
            if (geometry.length == 0) {
                cache.fail(key, now);
                return;
            }
            if (unreadableSourceTextures.contains(key.texture())) {
                completeBake(client, key, geometry, null);
                return;
            }
            final TextureRequest request = new TextureRequest(key, generation);
            final ResourceManager resources = client.getResourceManager();
            textureLoader.request(
                request, geometry, () -> sourceTexture(resources, key.texture())
            );
        } catch (final RuntimeException e) {
            cache.fail(key, now);
            ConfluxMapMod.LOGGER.debug("Failed to bake radar portrait for {}", entity.getType(), e);
        }
    }

    private void finishBake(
        final MinecraftClient client,
        final PortraitTextureLoader.Result<TextureRequest> result
    ) {
        final TextureRequest request = result.key();
        if (request.generation() != generation) {
            return;
        }
        final PortraitKey key = request.key();
        final WeakReference<LivingEntity> reference = liveEntities.get(key);
        final LivingEntity entity = reference == null ? null : reference.get();
        //#if MC>=12108 && MC<12109
        //$$ final boolean wrongWorld = entity == null || entity.getWorld() != client.world;
        //#else
        final boolean wrongWorld = entity == null || entity.getEntityWorld() != client.world;
        //#endif
        if (entity == null || wrongWorld) {
            cache.fail(key, clock);
            liveEntities.remove(key);
            return;
        }
        final int[] visibleBounds;
        if (result.success()) {
            visibleBounds = result.visibleBounds();
        } else {
            unreadableSourceTextures.add(key.texture());
            ConfluxMapMod.LOGGER.debug(
                "Could not inspect radar portrait alpha for {}", key.texture(), result.error()
            );
            visibleBounds = null;
        }
        try {
            completeBake(client, key, result.geometry(), visibleBounds);
        } catch (final RuntimeException e) {
            cache.fail(key, clock);
            ConfluxMapMod.LOGGER.debug("Failed to bake radar portrait for {}", entity.getType(), e);
        }
    }

    private void completeBake(
        final MinecraftClient client,
        final PortraitKey key,
        final float[] geometry,
        final int[] visibleBounds
    ) {
        final AtlasSprite baked = bake(
            client, key, cache.value(key).orElse(null), geometry, visibleBounds
        );
        if (baked == null) {
            cache.fail(key, clock);
            return;
        }
        cache.complete(key, baked, clock);
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
        final AtlasSprite current,
        final float[] geometry,
        final int[] visibleBounds
    ) {
        final AtlasSprite sprite = current == null ? allocateSprite() : current;
        if (sprite == null) {
            return null;
        }
        final int column = sprite.slot() % CELLS_PER_ROW;
        final int row = sprite.slot() / CELLS_PER_ROW;
        final AtlasSprite cropped = cropSprite(sprite, geometry, visibleBounds);
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
        return cropped;
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
        return new AtlasSprite(slot, u0, v0, u1, v1, 1f, 1f);
    }

    private static PortraitTextureLoader.SourceImage sourceTexture(
        final ResourceManager resources,
        final Identifier texture
    ) {
        try (InputStream input = MinecraftAccess.openResource(resources, texture)) {
            return new NativeSourceImage(NativeImage.read(input));
        } catch (final IOException e) {
            throw new IllegalStateException("Could not read " + texture, e);
        }
    }

    private record NativeSourceImage(NativeImage image)
        implements PortraitTextureLoader.SourceImage {
        @Override
        public int width() {
            return image.getWidth();
        }

        @Override
        public int height() {
            return image.getHeight();
        }

        @Override
        public int alphaAt(final int x, final int y) {
            return Argb.alpha(NativeImages.getArgb(image, x, y));
        }

        @Override
        public void close() {
            image.close();
        }
    }

    /** Tightens atlas sampling and records the aspect-preserving destination rectangle. */
    private static AtlasSprite cropSprite(
        final AtlasSprite sprite,
        final float[] geometry,
        final int[] visibleBounds
    ) {
        final int[] bounds = visibleBounds == null ? geometryBounds(geometry) : visibleBounds;
        final int left = bounds[0];
        final int top = bounds[1];
        final int right = bounds[2];
        final int bottom = bounds[3];
        final int width = right - left;
        final int height = bottom - top;
        final int column = sprite.slot() % CELLS_PER_ROW;
        final int row = sprite.slot() / CELLS_PER_ROW;
        final float longest = Math.max(width, height);
        return new AtlasSprite(
            sprite.slot(),
            (column * CELL_PX + left) / (float) ATLAS_PX,
            1f - (row * CELL_PX + top) / (float) ATLAS_PX,
            (column * CELL_PX + right) / (float) ATLAS_PX,
            1f - (row * CELL_PX + bottom) / (float) ATLAS_PX,
            width / longest,
            height / longest
        );
    }

    private static int[] geometryBounds(final float[] geometry) {
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < geometry.length; i += 5) {
            minX = Math.min(minX, geometry[i]);
            minY = Math.min(minY, geometry[i + 1]);
            maxX = Math.max(maxX, geometry[i]);
            maxY = Math.max(maxY, geometry[i + 1]);
        }
        return normalizedBounds(minX, minY, maxX, maxY);
    }

    static int[] visibleBounds(
        final float[] geometry,
        final int textureWidth,
        final int textureHeight,
        final IntBinaryOperator alphaAt
    ) {
        boolean found = false;
        int left = CELL_PX;
        int top = CELL_PX;
        int right = 0;
        int bottom = 0;
        for (int quad = 0; quad < geometry.length; quad += 20) {
            for (int triangle = 0; triangle < 2; triangle++) {
                final int first = 0;
                final int second = triangle == 0 ? 1 : 2;
                final int third = triangle == 0 ? 2 : 3;
                final int a = quad + first * 5;
                final int b = quad + second * 5;
                final int c = quad + third * 5;
                final float minX = Math.min(
                    geometry[a], Math.min(geometry[b], geometry[c])
                );
                final float minY = Math.min(
                    geometry[a + 1], Math.min(geometry[b + 1], geometry[c + 1])
                );
                final float maxX = Math.max(
                    geometry[a], Math.max(geometry[b], geometry[c])
                );
                final float maxY = Math.max(
                    geometry[a + 1], Math.max(geometry[b + 1], geometry[c + 1])
                );
                final float denominator = (geometry[b + 1] - geometry[c + 1])
                    * (geometry[a] - geometry[c])
                    + (geometry[c] - geometry[b]) * (geometry[a + 1] - geometry[c + 1]);
                if (Math.abs(denominator) < 0.0001f) {
                    continue;
                }
                final int firstX = clamp((int) Math.floor(minX), 0, CELL_PX - 1);
                final int firstY = clamp((int) Math.floor(minY), 0, CELL_PX - 1);
                final int lastX = clamp((int) Math.ceil(maxX), 1, CELL_PX);
                final int lastY = clamp((int) Math.ceil(maxY), 1, CELL_PX);
                for (int y = firstY; y < lastY; y++) {
                    for (int x = firstX; x < lastX; x++) {
                        final float px = x + 0.5f;
                        final float py = y + 0.5f;
                        final float wa = ((geometry[b + 1] - geometry[c + 1])
                            * (px - geometry[c])
                            + (geometry[c] - geometry[b]) * (py - geometry[c + 1]))
                            / denominator;
                        final float wb = ((geometry[c + 1] - geometry[a + 1])
                            * (px - geometry[c])
                            + (geometry[a] - geometry[c]) * (py - geometry[c + 1]))
                            / denominator;
                        final float wc = 1f - wa - wb;
                        if (wa < -0.0001f || wb < -0.0001f || wc < -0.0001f) {
                            continue;
                        }
                        final float u = geometry[a + 3] * wa
                            + geometry[b + 3] * wb
                            + geometry[c + 3] * wc;
                        final float v = geometry[a + 4] * wa
                            + geometry[b + 4] * wb
                            + geometry[c + 4] * wc;
                        final int textureX = clamp((int) (u * textureWidth), 0, textureWidth - 1);
                        final int textureY = clamp((int) (v * textureHeight), 0, textureHeight - 1);
                        if (alphaAt.applyAsInt(textureX, textureY) <= 8) {
                            continue;
                        }
                        found = true;
                        left = Math.min(left, x);
                        top = Math.min(top, y);
                        right = Math.max(right, x + 1);
                        bottom = Math.max(bottom, y + 1);
                    }
                }
            }
        }
        return found ? new int[] {left, top, right, bottom} : null;
    }

    private static int[] normalizedBounds(
        final float minX,
        final float minY,
        final float maxX,
        final float maxY
    ) {
        final int left = clamp((int) Math.floor(minX), 0, CELL_PX - 1);
        final int top = clamp((int) Math.floor(minY), 0, CELL_PX - 1);
        return new int[] {
            left,
            top,
            clamp((int) Math.ceil(maxX), left + 1, CELL_PX),
            clamp((int) Math.ceil(maxY), top + 1, CELL_PX)
        };
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(max, value));
    }

    static float atlasTopV(final int row) {
        return 1f - row / (float) CELLS_PER_ROW;
    }

    static float atlasBottomV(final int row) {
        return 1f - (row + 1) / (float) CELLS_PER_ROW;
    }

    private void resetDynamic() {
        generation++;
        cache.clear();
        liveEntities.clear();
        observedPortraits.clear();
        colorAtlas.close();
        textureLoader.clear();
        unreadableSourceTextures.clear();
        nextSlot = 0;
    }

    private static FaceIcon dynamicIcon(final AtlasSprite sprite) {
        return new FaceIcon(
            null, sprite.u0(), sprite.v0(), sprite.u1(), sprite.v1(),
            null, 0f, 0f, 0f, 0f, true, sprite.widthScale(), sprite.heightScale()
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
            false, 1f, 1f
        );
    }
}
