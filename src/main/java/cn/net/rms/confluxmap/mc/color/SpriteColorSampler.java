package cn.net.rms.confluxmap.mc.color;

import cn.net.rms.confluxmap.compat.Ids;
import cn.net.rms.confluxmap.compat.NativeImages;
import cn.net.rms.confluxmap.core.color.MaterialDetailProfile;
import cn.net.rms.confluxmap.core.util.Argb;
import java.util.ArrayList;
import java.util.Arrays;
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
import net.minecraft.block.SnowBlock;
import net.minecraft.block.VineBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
//#if MC>=260100
//$$ import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
//#elseif MC>=12105
//$$ import net.minecraft.client.render.model.BlockModelPart;
//#endif
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
 * Per-BlockState cached base color and 4x4 luminance profile, per
 * surface-color-sampling.md §2/§7. Colors come from sampling the live stitched
 * block-texture atlas (via each sprite's own un-stitched frame-0 source image) -
 * never from Minecraft's built-in map-item palette.
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
    private SampledMaterial[] cache = new SampledMaterial[4096];

    public SpriteColorSampler(final MinecraftClient client) {
        this.client = client;
    }

    /** Resource-reload listener hook: the atlas is being restitched, every cached color is stale. */
    public void clearCache() {
        cache = new SampledMaterial[4096];
    }

    /** The cached base color (tint not applied) for {@code state}, sampling and caching it if new. */
    public int colorFor(final BlockState state, final BlockView world, final BlockPos pos) {
        final int id = Block.getRawIdFromState(state);
        final SampledMaterial material = materialFor(state, world, pos, id);
        return material.detail().apply(material.argb(), pos.getX(), pos.getZ(), material.patternSalt());
    }

    /** Resource-derived luminance profile for prediction's representative material palette. */
    public MaterialDetailProfile detailProfileFor(final BlockState state, final BlockView world, final BlockPos pos) {
        final int id = Block.getRawIdFromState(state);
        return materialFor(state, world, pos, id).detail();
    }

    private SampledMaterial materialFor(
        final BlockState state,
        final BlockView world,
        final BlockPos pos,
        final int id
    ) {
        if (id >= 0 && id < cache.length && cache[id] != null) {
            return cache[id];
        }
        final SampledMaterial material = compute(state, world, pos);
        store(id, material);
        return material;
    }

    private void store(final int id, final SampledMaterial material) {
        if (id < 0) {
            return;
        }
        if (id >= cache.length) {
            final SampledMaterial[] grown = new SampledMaterial[Math.max(id + 1, cache.length * 2)];
            System.arraycopy(cache, 0, grown, 0, cache.length);
            cache = grown;
        }
        cache[id] = material;
    }

    private SampledMaterial compute(final BlockState state, final BlockView world, final BlockPos pos) {
        final Block block = state.getBlock();
        if (block instanceof RedstoneWireBlock) {
            // §2: baked in unconditionally via the power-level color function, no texture sampling at all.
            final int level = state.get(RedstoneWireBlock.POWER);
            return new SampledMaterial(
                0xFF000000 | (RedstoneWireBlock.getWireColor(level) & 0xFFFFFF),
                MaterialDetailProfile.flat(),
                state.toString().hashCode()
            );
        }
        final RawMaterial sampled = sampleModel(state, world, pos);
        int color = sampled.argb();
        if (block instanceof CobwebBlock) {
            color = withAlpha(color, 255);
        } else if (block instanceof AbstractSignBlock) {
            color = withAlpha(color, 31);
        } else if (block instanceof DoorBlock) {
            color = withAlpha(color, 47);
        } else if (block instanceof LadderBlock || block instanceof VineBlock) {
            color = withAlpha(color, 15);
        }
        final double maxDetail = !state.getFluidState().isEmpty()
            || block == Blocks.ICE || block instanceof SnowBlock
            ? 0.04
            : 0.08;
        return new SampledMaterial(
            color,
            MaterialDetailProfile.fromLuminance(sampled.cellLuminance(), maxDetail),
            state.toString().hashCode()
        );
    }

    private RawMaterial sampleModel(final BlockState state, final BlockView world, final BlockPos pos) {
        //#if MC>=260100
        //$$ // 26.1 moved block-state models off the render dispatcher onto the model manager.
        //$$ final BlockStateModel model = client.getModelManager().getBlockStateModelSet().get(state);
        //#else
        final BakedModel model = client.getBlockRenderManager().getModel(state);
        //#endif
        if (model == null) {
            // §2 tier 1 (model sprite average) is unavailable for this state - fall straight
            // through to tier 3 (MapColor) rather than crash. Seen for some states very early
            // after a world join, before every block's model is baked.
            return fallbackToMapColor(state, world, pos);
        }
        final List<Sprite> faceSprites = new ArrayList<>();
        //#if MC>=260100
        //$$ // 26.1 turned the part list into an out-parameter and moved a quad's sprite behind
        //$$ // its material record.
        //$$ final List<BlockStateModelPart> parts = new ArrayList<>();
        //$$ model.collectParts(modelRandom, parts);
        //$$ for (final BlockStateModelPart part : parts) {
        //$$     for (final BakedQuad quad : part.getQuads(Direction.UP)) {
        //$$         faceSprites.add(quad.materialInfo().sprite());
        //$$     }
        //$$     for (final BakedQuad quad : part.getQuads(null)) {
        //$$         faceSprites.add(quad.materialInfo().sprite());
        //$$     }
        //$$ }
        //#elseif MC>=12105
        //$$ for (final BlockModelPart part : model.getParts(modelRandom)) {
        //$$     for (final BakedQuad quad : part.getQuads(Direction.UP)) {
        //$$         faceSprites.add(quad.sprite());
        //$$     }
        //$$     for (final BakedQuad quad : part.getQuads(null)) {
        //$$         faceSprites.add(quad.sprite());
        //$$     }
        //$$ }
        //#else
        for (final BakedQuad quad : model.getQuads(state, Direction.UP, modelRandom)) {
            faceSprites.add(quad.getSprite());
        }
        for (final BakedQuad quad : model.getQuads(state, null, modelRandom)) {
            faceSprites.add(quad.getSprite());
        }
        //#endif
        final RawMaterial primary = averageSprites(faceSprites);
        if (primary != null) {
            return primary.withArgb(clampAlphaFloor(primary.argb()));
        }

        //#if MC>=260100
        //$$ final TextureAtlasSprite particle = model.particleMaterial().sprite();
        //#elseif MC>=12105
        //$$ final Sprite particle = model.particleSprite();
        //#else
        final Sprite particle = model.getParticleSprite();
        //#endif
        final boolean isFluid = !state.getFluidState().isEmpty();
        if (particle == null || isMissing(particle)) {
            if (isFluid) {
                final Sprite fluidSprite = fluidSprite(state);
                final RawMaterial sampled = fluidSprite == null ? null : sampleOneSprite(fluidSprite);
                if (sampled != null) {
                    return sampled.withArgb(clampAlphaFloor(sampled.argb()));
                }
            }
            return fallbackToMapColor(state, world, pos);
        }
        final RawMaterial sampled = sampleOneSprite(particle);
        return sampled != null
            ? sampled.withArgb(clampAlphaFloor(sampled.argb()))
            : fallbackToMapColor(state, world, pos);
    }

    private RawMaterial fallbackToMapColor(final BlockState state, final BlockView world, final BlockPos pos) {
        try {
            final int rgb = state.getMapColor(world, pos).color;
            if (rgb != 0) {
                return RawMaterial.flat(0xFF000000 | (rgb & 0xFFFFFF));
            }
        } catch (final RuntimeException ignored) {
            // Some blocks' getMapColor implementations touch world state we don't have here; fall through.
        }
        return RawMaterial.flat(UNRESOLVED_ARGB);
    }

    private Sprite fluidSprite(final BlockState state) {
        final Identifier id = state.isOf(Blocks.LAVA) ? LAVA_STILL : WATER_STILL;
        //#if MC>=260100
        //$$ final TextureAtlas atlas = client.getAtlasManager().getAtlasOrThrow(
        //$$     TextureAtlas.LOCATION_BLOCKS
        //$$ );
        //#elseif MC>=12109
        //$$ final SpriteAtlasTexture atlas = client.getAtlasManager().getAtlasTexture(
        //$$     SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE
        //$$ );
        //#else
        final SpriteAtlasTexture atlas = client.getBakedModelManager().getAtlas(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);
        //#endif
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
    private RawMaterial averageSprites(final List<Sprite> sprites) {
        long sumA = 0;
        long sumR = 0;
        long sumG = 0;
        long sumB = 0;
        final long[] cellLuminance = new long[MaterialDetailProfile.CELLS];
        int count = 0;
        for (final Sprite sprite : sprites) {
            final RawMaterial sample = sampleOneSprite(sprite);
            if (sample == null) {
                continue;
            }
            final int c = sample.argb();
            sumA += Argb.alpha(c);
            sumR += Argb.red(c);
            sumG += Argb.green(c);
            sumB += Argb.blue(c);
            for (int i = 0; i < cellLuminance.length; i++) {
                cellLuminance[i] += sample.cellLuminance()[i];
            }
            count++;
        }
        if (count == 0) {
            return null;
        }
        final int[] cells = new int[MaterialDetailProfile.CELLS];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = (int) (cellLuminance[i] / count);
        }
        return new RawMaterial(
            Argb.pack((int) (sumA / count), (int) (sumR / count), (int) (sumG / count), (int) (sumB / count)),
            cells
        );
    }

    /**
     * Box-filter/downsample one sprite's frame-0 pixels to a single alpha-weighted average
     * color. Pixels below {@link #ALPHA_FLOOR} are skipped so mostly-transparent decorative
     * textures (leaves, vines) average toward their visible color rather than toward black.
     * Null if the sprite is unresolvable or has no usable pixels at all.
     */
    private RawMaterial sampleOneSprite(final Sprite sprite) {
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
        final long[] cellWeightedLuminance = new long[MaterialDetailProfile.CELLS];
        final long[] cellAlpha = new long[MaterialDetailProfile.CELLS];
        int count = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                final int argb = NativeImages.getArgb(image, x, y);
                final int a = Argb.alpha(argb);
                if (a < ALPHA_FLOOR) {
                    continue;
                }
                sumA += a;
                sumR += (long) Argb.red(argb) * a;
                sumG += (long) Argb.green(argb) * a;
                sumB += (long) Argb.blue(argb) * a;
                final int cellX = Math.min(3, x * 4 / w);
                final int cellY = Math.min(3, y * 4 / h);
                final int cell = cellY * 4 + cellX;
                cellWeightedLuminance[cell] += (long) luminance(argb) * a;
                cellAlpha[cell] += a;
                count++;
            }
        }
        if (count == 0 || sumA == 0) {
            return null;
        }
        final int argb = Argb.pack(
            (int) (sumA / count), (int) (sumR / sumA), (int) (sumG / sumA), (int) (sumB / sumA)
        );
        final int fallbackLuminance = luminance(argb);
        final int[] cells = new int[MaterialDetailProfile.CELLS];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = cellAlpha[i] == 0
                ? fallbackLuminance
                : (int) (cellWeightedLuminance[i] / cellAlpha[i]);
        }
        return new RawMaterial(argb, cells);
    }

    private static int luminance(final int argb) {
        return (54 * Argb.red(argb) + 183 * Argb.green(argb) + 19 * Argb.blue(argb)) >> 8;
    }

    private static int clampAlphaFloor(final int argb) {
        return Argb.alpha(argb) < ALPHA_FLOOR ? withAlpha(argb, ALPHA_FLOOR) : argb;
    }

    private static int withAlpha(final int argb, final int alpha) {
        return (argb & 0x00FFFFFF) | (alpha & 0xFF) << 24;
    }

    private record SampledMaterial(int argb, MaterialDetailProfile detail, int patternSalt) {
    }

    private record RawMaterial(int argb, int[] cellLuminance) {
        static RawMaterial flat(final int argb) {
            final int[] cells = new int[MaterialDetailProfile.CELLS];
            Arrays.fill(cells, luminance(argb));
            return new RawMaterial(argb, cells);
        }

        RawMaterial withArgb(final int replacement) {
            return new RawMaterial(replacement, cellLuminance);
        }
    }
}
