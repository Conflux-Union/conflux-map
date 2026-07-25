package cn.net.rms.confluxmap.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.predict.FlatBaseline;
import java.util.List;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@link FlatWorldBaseline}'s layer-index-to-absolute-Y contract. A flat generator stacks its
 * layer list from {@code chunk.getBottomY()} upward, so the surface Y a baseline reports has to
 * agree with the absolute Y {@code ChunkSummarizer} reads off the heightmap - otherwise the
 * predicted underlay height-shades against the wrong reference and every pristine flat column
 * diffs as a correction.
 */
class FlatWorldBaselineTest {
    private static final int PLAINS = 1;
    /** 1.18+ overworld bottom; the 1.17 overworld's Y=0 bottom is the case that hid the bug. */
    private static final int MODERN_BOTTOM_Y = -64;

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        //#if MC>=12100
        //$$ Assumptions.abort(
        //$$     "Yarn's named 1.21 test jar splits vanilla package-private registry access; "
        //$$         + "this Minecraft-backed behavior is verified under Fabric Loader by GameTest"
        //$$ );
        //#else
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        //#endif
    }

    @Test
    void classicFlatSurfaceIsAnAbsoluteYNotALayerIndex() {
        final FlatBaseline baseline = FlatWorldBaseline.fromLayers(classicFlat(), MODERN_BOTTOM_Y, PLAINS);

        assertEquals(MODERN_BOTTOM_Y + 3, baseline.surfaceY(), "the grass layer sits three blocks above the world bottom");
        assertEquals(SurfaceKind.LAND.ordinal(), baseline.kind());
        assertEquals(0, baseline.fluidDepth());
    }

    @Test
    void aWorldBottomedAtZeroStillReportsThePlainLayerIndex() {
        final FlatBaseline baseline = FlatWorldBaseline.fromLayers(classicFlat(), 0, PLAINS);

        assertEquals(3, baseline.surfaceY());
    }

    @Test
    void waterFlatKeepsItsDepthWhileLiftingTheSurface() {
        final List<BlockState> layers = List.of(
            Blocks.BEDROCK.getDefaultState(),
            Blocks.SAND.getDefaultState(),
            Blocks.WATER.getDefaultState(),
            Blocks.WATER.getDefaultState()
        );

        final FlatBaseline baseline = FlatWorldBaseline.fromLayers(layers, MODERN_BOTTOM_Y, PLAINS);

        assertEquals(MODERN_BOTTOM_Y + 3, baseline.surfaceY());
        assertEquals(SurfaceKind.WATER.ordinal(), baseline.kind());
        assertEquals(2, baseline.fluidDepth(), "fluid depth is a layer count and must not shift with the bottom");
    }

    @Test
    void trailingAirLayersDoNotCountAsTheSurface() {
        final List<BlockState> layers = List.of(
            Blocks.BEDROCK.getDefaultState(),
            Blocks.STONE.getDefaultState(),
            Blocks.AIR.getDefaultState(),
            Blocks.AIR.getDefaultState()
        );

        assertEquals(MODERN_BOTTOM_Y + 1, FlatWorldBaseline.fromLayers(layers, MODERN_BOTTOM_Y, PLAINS).surfaceY());
    }

    @Test
    void theVoidPresetHasNoSurfaceAtAll() {
        final FlatBaseline baseline = FlatWorldBaseline.fromLayers(List.of(), MODERN_BOTTOM_Y, PLAINS);

        assertEquals(SurfaceKind.VOID.ordinal(), baseline.kind());
    }

    private static List<BlockState> classicFlat() {
        return List.of(
            Blocks.BEDROCK.getDefaultState(),
            Blocks.DIRT.getDefaultState(),
            Blocks.DIRT.getDefaultState(),
            Blocks.GRASS_BLOCK.getDefaultState()
        );
    }
}
