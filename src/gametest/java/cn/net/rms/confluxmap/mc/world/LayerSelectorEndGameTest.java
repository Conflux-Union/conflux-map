package cn.net.rms.confluxmap.mc.world;

import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.gametest.GameTestCompat;
//#if MC>=12105
//$$ import net.fabricmc.fabric.api.gametest.v1.GameTest;
//#else
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
//#endif
import net.minecraft.server.world.ServerWorld;
//#if MC<12105
import net.minecraft.test.GameTest;
//#endif
import net.minecraft.test.TestContext;
import net.minecraft.world.World;

/**
 * Locks the End's classification to its dimension identity against the real vanilla dimension
 * type: 1.21.9 flipped the_end's has_skylight to true, which must not change the layer the
 * mod captures and renders the End with.
 */
//#if MC>=12105
//$$ public final class LayerSelectorEndGameTest {
//#else
public final class LayerSelectorEndGameTest implements FabricGameTest {
//#endif
    //#if MC>=12105
    //$$ @GameTest(maxTicks = 20)
    //#elseif MC>=12000
    //$$ @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    //#else
    @GameTest(structureName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    //#endif
    public void theEndClassifiesByIdentityNotDimensionMetadata(final TestContext context) {
        final ServerWorld end = context.getWorld().getServer().getWorld(World.END);
        if (end == null) {
            GameTestCompat.fail(context, "server did not create the End dimension");
            return;
        }
        final DimensionLayerPolicy.DimensionKind kind = DimensionLayerPolicy.classify(
            DimensionLayerPolicy.dimensionId(end),
            end.getDimension()
        );
        if (kind != DimensionLayerPolicy.DimensionKind.NO_SKY_NO_CEILING) {
            GameTestCompat.fail(context,
                "the End classified as " + kind + " (hasSkyLight=" + end.getDimension().hasSkyLight()
                    + "), expected NO_SKY_NO_CEILING");
            return;
        }
        if (DimensionLayerPolicy.layerFor(kind) != MapLayer.END_SURFACE) {
            GameTestCompat.fail(context, "the End did not select its END_SURFACE layer");
            return;
        }
        context.complete();
    }
}
