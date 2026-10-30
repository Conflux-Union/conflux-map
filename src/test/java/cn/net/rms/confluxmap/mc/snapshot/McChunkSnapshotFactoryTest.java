package cn.net.rms.confluxmap.mc.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.compat.Regs;
import cn.net.rms.confluxmap.core.color.OverlayVisibility;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class McChunkSnapshotFactoryTest {
    /**
     * Blocks whose registry name the shared name rule classifies (contains grass/fern, or the
     * torch/glass suffixes) but whose block instance legitimately lands elsewhere. Each entry
     * documents why the block can never reach an overlay slot, so the sweep still fails when a
     * NEW name/instance disagreement appears.
     */
    private static final Set<String> BENIGN_NAME_MISMATCHES = Set.of(
        // Opaque ground block: always the surface/floor itself, never the decoration above it.
        "grass_block",
        // collapse() turns seagrass into water long before any overlay classification runs
        // (tall_seagrass agrees with the name rule: it is a TallPlantBlock, hence bulk).
        "seagrass",
        // A flower pot, not a plant; unreachable as a live overlay (pots sit on solid blocks),
        // and the summarizer's substring rule has always skipped it too.
        "potted_fern"
    );

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        //#if MC>=11800
        //$$ Assumptions.abort(
        //$$     "Yarn's named 1.18+ test jars split vanilla package-private registry access; "
        //$$         + "this Minecraft-backed behavior is verified under Fabric Loader by GameTest"
        //$$ );
        //#else
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        // state.isIn(BlockTags.FLOWERS) throws "used before it was bound" under the bare test
        // bootstrap: RequiredTagListRegistry's empty-manager binding path never reaches the
        // tag holders there, so bind the one wrapper the classifier consults directly. Vanilla
        // flowers all resolve via FlowerBlock/TallFlowerBlock instances; the tag exists for
        // modded additions, which these registry-sweep tests do not cover.
        bindFlowersTagToEmpty();
        //#endif
    }

    @Test
    void kelpCollapsesToWaterBeforeUnderwaterOverlaySelection() {
        assertCollapsesToWater(Blocks.KELP.getDefaultState());
        assertCollapsesToWater(Blocks.KELP_PLANT.getDefaultState());
    }

    @Test
    void submergedGreenVegetationDoesNotBecomeASeafloorOverlay() {
        assertCollapsesToWater(Blocks.SEAGRASS.getDefaultState());
        assertCollapsesToWater(Blocks.TALL_SEAGRASS.getDefaultState());
    }

    @Test
    void decorationCategoriesFollowTheSharedVisibilityPolicy() {
        assertEquals(OverlayVisibility.Category.BULK_FOLIAGE, categorize(Blocks.FERN));
        assertEquals(OverlayVisibility.Category.BULK_FOLIAGE, categorize(Blocks.TALL_GRASS));
        assertEquals(OverlayVisibility.Category.BULK_FOLIAGE, categorize(shortGrass()));
        assertEquals(OverlayVisibility.Category.VEGETATION, categorize(Blocks.POPPY));
        assertEquals(OverlayVisibility.Category.VEGETATION, categorize(Blocks.LILAC));
        assertEquals(OverlayVisibility.Category.VEGETATION, categorize(Blocks.OAK_SAPLING));
        assertEquals(OverlayVisibility.Category.VEGETATION, categorize(Blocks.BROWN_MUSHROOM));
        assertEquals(OverlayVisibility.Category.VEGETATION, categorize(Blocks.BIG_DRIPLEAF));
        assertEquals(OverlayVisibility.Category.MARKER, categorize(Blocks.TORCH));
        assertEquals(OverlayVisibility.Category.MARKER, categorize(Blocks.SOUL_TORCH));
        assertEquals(OverlayVisibility.Category.MARKER, categorize(Blocks.GLASS));
        assertEquals(OverlayVisibility.Category.OTHER, categorize(Blocks.RAIL));
        assertEquals(OverlayVisibility.Category.OTHER, categorize(Blocks.SNOW));
        assertEquals(OverlayVisibility.Category.OTHER, categorize(Blocks.MOSS_CARPET));
    }

    @Test
    void floorOverlayEligibilityHidesVegetationAndKeepsMarkers() {
        assertFalse(McChunkSnapshotFactory.isFloorOverlayCandidate(Blocks.FERN.getDefaultState()));
        assertFalse(McChunkSnapshotFactory.isFloorOverlayCandidate(Blocks.TALL_GRASS.getDefaultState()));
        assertFalse(McChunkSnapshotFactory.isFloorOverlayCandidate(shortGrass().getDefaultState()));
        assertFalse(McChunkSnapshotFactory.isFloorOverlayCandidate(Blocks.POPPY.getDefaultState()));
        assertFalse(McChunkSnapshotFactory.isFloorOverlayCandidate(Blocks.OAK_SAPLING.getDefaultState()));
        assertFalse(McChunkSnapshotFactory.isFloorOverlayCandidate(Blocks.BROWN_MUSHROOM.getDefaultState()));
        assertFalse(McChunkSnapshotFactory.isFloorOverlayCandidate(Blocks.BIG_DRIPLEAF.getDefaultState()));
        assertTrue(McChunkSnapshotFactory.isFloorOverlayCandidate(Blocks.TORCH.getDefaultState()));
        assertTrue(McChunkSnapshotFactory.isFloorOverlayCandidate(Blocks.RAIL.getDefaultState()));
        assertTrue(McChunkSnapshotFactory.isFloorOverlayCandidate(Blocks.SNOW.getDefaultState()));
        assertTrue(McChunkSnapshotFactory.isFloorOverlayCandidate(Blocks.MOSS_CARPET.getDefaultState()));
        assertFalse(McChunkSnapshotFactory.isFloorOverlayCandidate(Blocks.WATER.getDefaultState()));
        assertFalse(McChunkSnapshotFactory.isFloorOverlayCandidate(Blocks.LAVA.getDefaultState()));
    }

    /**
     * Pins the instance-based classifier to the name-based one: every vanilla block the shared
     * name rule classifies must land in the same category when classified by instance, except
     * the documented {@link #BENIGN_NAME_MISMATCHES allowlist}. This is the guard that keeps
     * live capture, server summaries, and the native scanner from drifting apart again.
     */
    @Test
    void instanceClassificationAgreesWithTheNameRuleAcrossTheRegistry() {
        final Set<String> mismatchesSeen = new HashSet<>();
        for (final Block block : Regs.blocks()) {
            final String id = Regs.blockId(block).toString();
            final OverlayVisibility.Category nameCategory = OverlayVisibility.categorize(id);
            if (nameCategory == OverlayVisibility.Category.OTHER) {
                continue;
            }
            final OverlayVisibility.Category instanceCategory =
                McChunkSnapshotFactory.categorizeOverlay(block.getDefaultState());
            if (instanceCategory != nameCategory) {
                mismatchesSeen.add(Regs.blockId(block).getPath());
            }
        }
        assertEquals(
            BENIGN_NAME_MISMATCHES,
            mismatchesSeen,
            "name rule vs instance rule disagree beyond the documented allowlist"
        );
    }

    //#if MC<11800
    @SuppressWarnings("unchecked")
    private static void bindFlowersTagToEmpty() {
        try {
            final java.lang.reflect.Method updateDelegate =
                net.minecraft.tag.BlockTags.FLOWERS.getClass()
                    .getDeclaredMethod("updateDelegate", java.util.function.Function.class);
            updateDelegate.setAccessible(true);
            updateDelegate.invoke(
                net.minecraft.tag.BlockTags.FLOWERS,
                (java.util.function.Function<net.minecraft.util.Identifier, Object>) id -> net.minecraft.tag.SetTag.empty()
            );
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("failed to bind the flowers tag for tests", e);
        }
    }
    //#endif

    private static OverlayVisibility.Category categorize(final Block block) {
        return McChunkSnapshotFactory.categorizeOverlay(block.getDefaultState());
    }

    private static Block shortGrass() {
        //#if MC>=12100
        //$$ return Blocks.SHORT_GRASS;
        //#else
        return Blocks.GRASS;
        //#endif
    }

    private static void assertCollapsesToWater(final BlockState state) {
        final BlockState collapsed = McChunkSnapshotFactory.collapse(state);

        assertTrue(collapsed.isOf(Blocks.WATER));
        assertFalse(McChunkSnapshotFactory.isSeafloorCapturable(collapsed));
    }

    @Test
    void bedrockThicknessMultiplierValues() {
        assertEquals(1.15f, cn.net.rms.confluxmap.core.color.ShadingPipeline.bedrockThicknessMultiplier(1), 1e-6f);
        assertEquals(1.075f, cn.net.rms.confluxmap.core.color.ShadingPipeline.bedrockThicknessMultiplier(2), 1e-6f);
        assertEquals(1.00f, cn.net.rms.confluxmap.core.color.ShadingPipeline.bedrockThicknessMultiplier(3), 1e-6f);
        assertEquals(0.925f, cn.net.rms.confluxmap.core.color.ShadingPipeline.bedrockThicknessMultiplier(4), 1e-6f);
        assertEquals(0.85f, cn.net.rms.confluxmap.core.color.ShadingPipeline.bedrockThicknessMultiplier(5), 1e-6f);
    }
}
