package cn.net.rms.confluxmap.mc.radar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import net.minecraft.client.model.Dilation;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.CreeperEntityModel;
import net.minecraft.client.render.entity.model.EntityModel;
import org.junit.jupiter.api.Test;

/**
 * Portrait selection against a real vanilla model instance. The creeper is the regression case:
 * its model implements no head interface and, before 1.21.3, exposes no head group either, so a
 * strategy chain that misses its named root child silently renders the whole mob.
 */
final class EntityHeadGeometryTest {
    private static final float SPAN_EPSILON = 0.01f;

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static EntityModel<?> creeperModel(final ModelPart root) {
        return new CreeperEntityModel(root);
    }

    @Test
    void selectsOnlyTheCreeperHead() {
        final ModelPart root = CreeperEntityModel.getTexturedModelData(Dilation.NONE).createModel();
        final List<ModelPart> selected = EntityHeadGeometry.selectParts(
            creeperModel(root), "minecraft:creeper"
        );

        assertEquals(1, selected.size(), "a portrait must not fall back to the whole creeper");
        assertSame(root.getChild("head"), selected.get(0));
    }

    @Test
    void normalizesTheCreeperPortraitOntoTheSharedSpan() {
        final ModelPart root = CreeperEntityModel.getTexturedModelData(Dilation.NONE).createModel();
        final float[] geometry = EntityHeadGeometry.project(
            EntityHeadGeometry.selectParts(creeperModel(root), "minecraft:creeper"),
            "minecraft:creeper", 0, 0
        );

        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < geometry.length; i += 5) {
            minX = Math.min(minX, geometry[i]);
            maxX = Math.max(maxX, geometry[i]);
            minY = Math.min(minY, geometry[i + 1]);
            maxY = Math.max(maxY, geometry[i + 1]);
        }

        assertEquals(EntityHeadGeometry.PORTRAIT_SPAN_PX, maxX - minX, SPAN_EPSILON);
        assertEquals(EntityHeadGeometry.PORTRAIT_SPAN_PX, maxY - minY, SPAN_EPSILON);
    }
}
