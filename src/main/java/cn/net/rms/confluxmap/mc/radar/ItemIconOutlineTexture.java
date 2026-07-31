package cn.net.rms.confluxmap.mc.radar;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.compat.NativeImages;
import cn.net.rms.confluxmap.core.radar.IconOutliner;
import cn.net.rms.confluxmap.mc.render.RenderUtil;
//#if MC<12105
import com.mojang.blaze3d.platform.GlStateManager;
//#endif
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.MinecraftClient;
//#if MC>=12104
//$$ import net.minecraft.client.render.item.ItemRenderState;
//$$ import net.minecraft.item.ModelTransformationMode;
//$$ import net.minecraft.util.math.random.Random;
//#else
import net.minecraft.client.render.model.BakedModel;
//#endif
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.Sprite;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;

/**
 * Lazily builds the same one-pixel alpha outline used by entity-sheet icons for flat item GUI
 * models. The resolved model's particle sprite is the texture vanilla uses to represent the
 * model; it is reduced to the normal 16x16 GUI grid before {@link IconOutliner} expands its
 * silhouette into an 18x18 mask. Cache keys are sprite identities, so model overrides can keep
 * distinct outlines while stacks sharing one texture reuse a single GPU texture.
 *
 * <p>Depth-lit models are deliberately left without a contour: their rendered GUI silhouette is
 * a transformed 3-D model and does not match the particle sprite. Omitting the frame is preferable
 * to drawing another loose square around it.
 */
final class ItemIconOutlineTexture {
    private static final int ICON_PX = 16;
    private static final int MASK_PX = ICON_PX + 2 * IconOutliner.PAD;

    private final Map<Sprite, NativeImageBackedTexture> textures = new IdentityHashMap<>();
    private final Set<Sprite> failed = Collections.newSetFromMap(new IdentityHashMap<>());
    //#if MC>=12104
    //$$ private final ItemRenderState renderState = new ItemRenderState();
    //$$ private final Random modelRandom = Random.create(42L);
    //#endif

    /** Binds the stack's cached outline mask, building it on first use. */
    boolean bind(final MinecraftClient client, final ItemStack stack, final Entity entity) {
        assert RenderSystem.isOnRenderThread() : "ItemIconOutlineTexture.bind() must run on the render thread";
        final Sprite sprite = spriteFor(client, stack, entity);
        if (sprite == null || failed.contains(sprite)) {
            return false;
        }
        NativeImageBackedTexture texture = textures.get(sprite);
        if (texture == null) {
            try {
                texture = build(sprite);
                if (texture == null) {
                    failed.add(sprite);
                    return false;
                }
                textures.put(sprite, texture);
            } catch (final RuntimeException e) {
                failed.add(sprite);
                ConfluxMapMod.LOGGER.warn("Failed to bake radar item outline mask", e);
                return false;
            }
        }
        //#if MC>=12108
        //$$ RenderUtil.bindTexture(texture.getGlTextureView());
        //#elseif MC>=12105
        //$$ RenderUtil.bindTexture(texture.getGlTexture());
        //#else
        RenderUtil.bindTexture(texture.getGlId());
        //#endif
        return true;
    }

    /** Resource reload: close every mask because both model resolution and sprite pixels may change. */
    void invalidate() {
        assert RenderSystem.isOnRenderThread() : "ItemIconOutlineTexture.invalidate() must run on the render thread";
        for (final NativeImageBackedTexture texture : textures.values()) {
            texture.close();
        }
        textures.clear();
        failed.clear();
    }

    private Sprite spriteFor(final MinecraftClient client, final ItemStack stack, final Entity entity) {
        //#if MC>=12104
        //$$ client.getItemModelManager().updateForNonLivingEntity(
        //$$     renderState, stack, ModelTransformationMode.GUI, entity
        //$$ );
        //$$ if (renderState.isSideLit()) {
        //$$     return null;
        //$$ }
        //$$ modelRandom.setSeed(42L);
        //#if MC>=260100
        //$$ return renderState.pickParticleMaterial(modelRandom).sprite();
        //#else
        //$$ return renderState.getParticleSprite(modelRandom);
        //#endif
        //#else
        //#if MC>=12100
        //$$ final BakedModel model = client.getItemRenderer().getModel(stack, client.world, client.player, 0);
        //#else
        final BakedModel model = client.getItemRenderer().getHeldItemModel(stack, client.world, client.player, 0);
        //#endif
        return model == null || model.hasDepth() ? null : model.getParticleSprite();
        //#endif
    }

    private static NativeImageBackedTexture build(final Sprite sprite) {
        //#if MC>=11900
        //$$ final NativeImage[] images = sprite.getContents().mipmapLevelsImages;
        //#else
        final NativeImage[] images = sprite.images;
        //#endif
        if (images == null || images.length == 0 || images[0] == null) {
            return null;
        }
        final NativeImage source = images[0];
        //#if MC>=11900
        //$$ final int width = Math.min(sprite.getContents().getWidth(), source.getWidth());
        //$$ final int height = Math.min(sprite.getContents().getHeight(), source.getHeight());
        //#else
        final int width = Math.min(sprite.getWidth(), source.getWidth());
        final int height = Math.min(sprite.getHeight(), source.getHeight());
        //#endif
        if (width <= 0 || height <= 0) {
            return null;
        }

        final int[] icon = new int[ICON_PX * ICON_PX];
        for (int y = 0; y < ICON_PX; y++) {
            final int sourceY0 = y * height / ICON_PX;
            final int sourceY1 = Math.max(sourceY0 + 1, (y + 1) * height / ICON_PX);
            for (int x = 0; x < ICON_PX; x++) {
                final int sourceX0 = x * width / ICON_PX;
                final int sourceX1 = Math.max(sourceX0 + 1, (x + 1) * width / ICON_PX);
                int maxAlpha = 0;
                for (int sourceY = sourceY0; sourceY < Math.min(sourceY1, height); sourceY++) {
                    for (int sourceX = sourceX0; sourceX < Math.min(sourceX1, width); sourceX++) {
                        maxAlpha = Math.max(maxAlpha, NativeImages.getArgb(source, sourceX, sourceY) >>> 24);
                    }
                }
                icon[y * ICON_PX + x] = maxAlpha << 24;
            }
        }

        final int[] outline = IconOutliner.outlineMask(icon, ICON_PX, ICON_PX);
        final NativeImage mask = new NativeImage(MASK_PX, MASK_PX, false);
        for (int y = 0; y < MASK_PX; y++) {
            for (int x = 0; x < MASK_PX; x++) {
                NativeImages.setArgb(mask, x, y, outline[y * MASK_PX + x]);
            }
        }
        //#if MC>=12105
        //$$ final NativeImageBackedTexture texture = new NativeImageBackedTexture(
        //$$     () -> "Conflux Map radar item outline", mask
        //$$ );
        //#else
        final NativeImageBackedTexture texture = new NativeImageBackedTexture(mask);
        //#endif
        texture.upload();
        configureSampling(texture);
        return texture;
    }

    private static void configureSampling(final NativeImageBackedTexture texture) {
        //#if MC>=12111
        //$$ // Sampling is selected explicitly when the render pass binds the texture.
        //#else
        texture.setFilter(false, false);
        //#if MC>=12105
        //$$ texture.setClamp(true);
        //#else
        GlStateManager._bindTexture(texture.getGlId());
        GlStateManager._texParameter(3553, 10242, 33071);
        GlStateManager._texParameter(3553, 10243, 33071);
        //#endif
        //#endif
    }
}
