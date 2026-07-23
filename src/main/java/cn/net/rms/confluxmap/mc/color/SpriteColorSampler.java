package cn.net.rms.confluxmap.mc.color;

import cn.net.rms.confluxmap.compat.Ids;
import cn.net.rms.confluxmap.core.util.Argb;
import java.util.ArrayList;
import java.util.List;
//#if MC<11900
import java.util.Random;
//#endif
import net.minecraft.block.AbstractSignBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CobwebBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.RedstoneWireBlock;
import net.minecraft.block.VineBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.MissingSprite;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
//#if MC>=11900
//$$ import net.minecraft.util.math.random.Random;
//#endif
import net.minecraft.world.BlockView;

/**
 * Per-BlockState cached base color, per surface-color-sampling.md §2. Colors come
 * from sampling the live stitched block-texture atlas (via each sprite's own
 * un-stitched frame-0 source image) - never from
 * Minecraft's built-in map-item palette.
 *
 * <p>Main-thread only (touches {@link BakedModel}s); the returned colors are
 * plain ints safe to hand to worker threads afterward.
 */
public final class SpriteColorSampler {
    /** §2: the alpha floor - both a "skip this pixel" threshold while averaging and a final clamp. */
    private static final int ALPHA_FLOOR = 27;
    private static final int UNRESOLVED_ARGB = Argb.pack(ALPHA_FLOOR, 0, 0, 0);
    private static final Identifier WATER_STILL = Ids.of("block/water_still");
    private static final Identifier LAVA_STILL = Ids.of("block/lava_still");

    private final MinecraftClient client;
    //#if MC>=11900
    //$$ private final Random modelRandom = Random.create(42L);
    //#else
    private final Random modelRandom = new Random(42L);
    //#endif
    private int[] cache = new int[4096];

    public SpriteColorSampler(final MinecraftClient client) {
        this.client = client;
    }

    /** Resource-reload listener hook: the atlas is being restitched, every cached color is stale. */
    public void clearCache() {
        cache = new int[4096];
    }

    /** The cached base color (tint not applied) for {@code state}, sampling and caching it if new. */
    public int colorFor(final BlockState state, final BlockView world, final BlockPos pos) {
        final int id = Block.getRawIdFromState(state);
        if (id >= 0 && id < cache.length && cache[id] != 0) {
            return cache[id];
        }
        final int color = compute(state, world, pos);
        store(id, color);
        return color;
    }

    private void store(final int id, final int color) {
        if (id < 0) {
            return;
        }
        if (id >= cache.length) {
            final int[] grown = new int[Math.max(id + 1, cache.length * 2)];
            System.arraycopy(cache, 0, grown, 0, cache.length);
            cache = grown;
        }
        cache[id] = color;
    }

    private int compute(final BlockState state, final BlockView world, final BlockPos pos) {
        final Block block = state.getBlock();
        if (block instanceof RedstoneWireBlock) {
            // §2: baked in unconditionally via the power-level color function, no texture sampling at all.
            final int level = state.get(RedstoneWireBlock.POWER);
            return 0xFF000000 | (RedstoneWireBlock.getWireColor(level) & 0xFFFFFF);
        }
        int color = sampleModel(state, world, pos);
        if (block instanceof CobwebBlock) {
            color = withAlpha(color, 255);
        } else if (block instanceof AbstractSignBlock) {
            color = withAlpha(color, 31);
        } else if (block instanceof DoorBlock) {
            color = withAlpha(color, 47);
        } else if (block instanceof LadderBlock || block instanceof VineBlock) {
            color = withAlpha(color, 15);
        }
        return color;
    }

    private int sampleModel(final BlockState state, final BlockView world, final BlockPos pos) {
        final BakedModel model = client.getBlockRenderManager().getModel(state);
        if (model == null) {
            // §2 tier 1 (model sprite average) is unavailable for this state - fall straight
            // through to tier 3 (MapColor) rather than crash. Seen for some states very early
            // after a world join, before every block's model is baked.
            return fallbackToMapColor(state, world, pos);
        }
        final List<Sprite> faceSprites = new ArrayList<>();
        for (final BakedQuad quad : model.getQuads(state, Direction.UP, modelRandom)) {
            faceSprites.add(quad.getSprite());
        }
        for (final BakedQuad quad : model.getQuads(state, null, modelRandom)) {
            faceSprites.add(quad.getSprite());
        }
        final Integer primary = averageSprites(faceSprites);
        if (primary != null) {
            return clampAlphaFloor(primary);
        }

        final Sprite particle = model.getParticleSprite();
        final boolean isFluid = !state.getFluidState().isEmpty();
        if (particle == null || isMissing(particle)) {
            if (isFluid) {
                final Sprite fluidSprite = fluidSprite(state);
                final Integer sampled = fluidSprite == null ? null : sampleOneSprite(fluidSprite);
                if (sampled != null) {
                    return clampAlphaFloor(sampled);
                }
            }
            return fallbackToMapColor(state, world, pos);
        }
        final Integer sampled = sampleOneSprite(particle);
        return sampled != null ? clampAlphaFloor(sampled) : fallbackToMapColor(state, world, pos);
    }

    private int fallbackToMapColor(final BlockState state, final BlockView world, final BlockPos pos) {
        try {
            final int rgb = state.getMapColor(world, pos).color;
            if (rgb != 0) {
                return 0xFF000000 | (rgb & 0xFFFFFF);
            }
        } catch (final RuntimeException ignored) {
            // Some blocks' getMapColor implementations touch world state we don't have here; fall through.
        }
        return UNRESOLVED_ARGB;
    }

    private Sprite fluidSprite(final BlockState state) {
        final Identifier id = state.isOf(Blocks.LAVA) ? LAVA_STILL : WATER_STILL;
        final SpriteAtlasTexture atlas = client.getBakedModelManager().getAtlas(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);
        return atlas.getSprite(id);
    }

    private static boolean isMissing(final Sprite sprite) {
        //#if MC>=11900
        //$$ return sprite.getContents().getId().equals(MissingSprite.getMissingSpriteId());
        //#else
        return sprite.getId().equals(MissingSprite.getMissingSpriteId());
        //#endif
    }

    /** Equal-weighted average across every quad's resolved sprite color; null if none were usable. */
    private Integer averageSprites(final List<Sprite> sprites) {
        long sumA = 0;
        long sumR = 0;
        long sumG = 0;
        long sumB = 0;
        int count = 0;
        for (final Sprite sprite : sprites) {
            final Integer c = sampleOneSprite(sprite);
            if (c == null) {
                continue;
            }
            sumA += Argb.alpha(c);
            sumR += Argb.red(c);
            sumG += Argb.green(c);
            sumB += Argb.blue(c);
            count++;
        }
        if (count == 0) {
            return null;
        }
        return Argb.pack((int) (sumA / count), (int) (sumR / count), (int) (sumG / count), (int) (sumB / count));
    }

    /**
     * Box-filter/downsample one sprite's frame-0 pixels to a single alpha-weighted average
     * color. Pixels below {@link #ALPHA_FLOOR} are skipped so mostly-transparent decorative
     * textures (leaves, vines) average toward their visible color rather than toward black.
     * Null if the sprite is unresolvable or has no usable pixels at all.
     */
    private Integer sampleOneSprite(final Sprite sprite) {
        if (sprite == null || isMissing(sprite)) {
            return null;
        }
        //#if MC>=11900
        //$$ final NativeImage[] images = sprite.getContents().mipmapLevelsImages;
        //#else
        final NativeImage[] images = sprite.images;
        //#endif
        if (images == null || images.length == 0 || images[0] == null) {
            return null;
        }
        final NativeImage image = images[0];
        //#if MC>=11900
        //$$ final int w = Math.min(sprite.getContents().getWidth(), image.getWidth());
        //$$ final int h = Math.min(sprite.getContents().getHeight(), image.getHeight());
        //#else
        final int w = Math.min(sprite.getWidth(), image.getWidth());
        final int h = Math.min(sprite.getHeight(), image.getHeight());
        //#endif
        if (w <= 0 || h <= 0) {
            return null;
        }
        long sumA = 0;
        long sumR = 0;
        long sumG = 0;
        long sumB = 0;
        int count = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                final int argb = Argb.toAbgr(image.getColor(x, y));
                final int a = Argb.alpha(argb);
                if (a < ALPHA_FLOOR) {
                    continue;
                }
                sumA += a;
                sumR += (long) Argb.red(argb) * a;
                sumG += (long) Argb.green(argb) * a;
                sumB += (long) Argb.blue(argb) * a;
                count++;
            }
        }
        if (count == 0 || sumA == 0) {
            return null;
        }
        return Argb.pack((int) (sumA / count), (int) (sumR / sumA), (int) (sumG / sumA), (int) (sumB / sumA));
    }

    private static int clampAlphaFloor(final int argb) {
        return Argb.alpha(argb) < ALPHA_FLOOR ? withAlpha(argb, ALPHA_FLOOR) : argb;
    }

    private static int withAlpha(final int argb, final int alpha) {
        return (argb & 0x00FFFFFF) | (alpha & 0xFF) << 24;
    }
}
