package cn.net.rms.confluxmap.mc.color;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.color.world.BiomeColors;
import net.minecraft.client.color.world.FoliageColors;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.tag.FluidTags;
import net.minecraft.util.math.BlockPos;

/**
 * Live per-position biome tint resolution, per surface-color-sampling.md §3's
 * "live" path (single-point sample; the game's own biome-blend client setting
 * provides whatever smoothing is visible, not this pipeline). Only the "live"
 * path is implemented in this slice - the cached/world-map pass's 3x3 average
 * belongs to the persisted-map slice.
 *
 * <p>Redstone wire is deliberately not routed through here (§6): its color is
 * baked directly into the base color by the sprite sampler.
 */
public final class BiomeTintResolver {
    /** §3: fixed reference colors, not biome-sampled at all (matches the base game's own special-casing). */
    //#if MC>=12104
    //$$ private static final int SPRUCE_LEAVES_ARGB = 0xFF000000 | FoliageColors.SPRUCE;
    //$$ private static final int BIRCH_LEAVES_ARGB = 0xFF000000 | FoliageColors.BIRCH;
    //#else
    private static final int SPRUCE_LEAVES_ARGB = 0xFF000000 | FoliageColors.getSpruceColor();
    private static final int BIRCH_LEAVES_ARGB = 0xFF000000 | FoliageColors.getBirchColor();
    //#endif
    private static final int NO_TINT = 0xFFFFFFFF;

    private final MinecraftClient client;

    public BiomeTintResolver(final MinecraftClient client) {
        this.client = client;
    }

    /** The tint color (opaque ARGB, white = no tint) for {@code state} at {@code pos}. */
    public int resolve(final BlockState state, final ClientWorld world, final BlockPos pos) {
        final Block block = state.getBlock();
        if (block == Blocks.SPRUCE_LEAVES) {
            return SPRUCE_LEAVES_ARGB;
        }
        if (block == Blocks.BIRCH_LEAVES) {
            return BIRCH_LEAVES_ARGB;
        }
        if (isWater(state)) {
            return 0xFF000000 | BiomeColors.getWaterColor(world, pos);
        }
        if (isFoliageTinted(block)) {
            return 0xFF000000 | BiomeColors.getFoliageColor(world, pos);
        }
        if (isGrassTinted(block)) {
            return 0xFF000000 | BiomeColors.getGrassColor(world, pos);
        }
        // §3/§6: anything outside the fixed set (including modded blocks) still gets probed
        // through the game's own tint provider registry; unregistered blocks report NO_COLOR.
        final int color = client.getBlockColors().getColor(state, world, pos, 0);
        return color == -1 ? NO_TINT : (0xFF000000 | color);
    }

    private static boolean isWater(final BlockState state) {
        return !state.getFluidState().isEmpty() && state.getFluidState().isIn(FluidTags.WATER);
    }

    /** §3: oak/jungle/acacia/dark oak leaves plus vines use the generic foliage function. */
    private static boolean isFoliageTinted(final Block block) {
        return block == Blocks.OAK_LEAVES || block == Blocks.JUNGLE_LEAVES
            || block == Blocks.ACACIA_LEAVES || block == Blocks.DARK_OAK_LEAVES
            || block == Blocks.VINE;
    }

    /** §3: the grass-top block, short/tall grass and fern, reeds and lily pads use the grass function. */
    private static boolean isGrassTinted(final Block block) {
        return block == Blocks.GRASS_BLOCK || block == Blocks.GRASS || block == Blocks.FERN
            || block == Blocks.TALL_GRASS || block == Blocks.LARGE_FERN
            || block == Blocks.SUGAR_CANE || block == Blocks.LILY_PAD;
    }
}
