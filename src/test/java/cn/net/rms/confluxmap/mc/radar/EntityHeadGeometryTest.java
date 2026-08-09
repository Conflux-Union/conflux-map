package cn.net.rms.confluxmap.mc.radar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.client.model.Dilation;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.entity.model.CreeperEntityModel;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.render.entity.model.PigEntityModel;
import net.minecraft.client.render.entity.model.RabbitEntityModel;
//#if MC>=260100
//$$ import net.minecraft.client.model.animal.rabbit.AdultRabbitModel;
//#endif
import net.minecraft.client.render.entity.model.VillagerResemblingModel;
//#if MC>=260100
//$$ import net.minecraft.client.model.monster.warden.WardenModel;
//#elseif MC>=11900
//$$ import net.minecraft.client.render.entity.model.WardenEntityModel;
//#endif
import org.junit.jupiter.api.Test;

/**
 * Portrait selection against a real vanilla model instance. The creeper is the regression case:
 * its model implements no head interface and, before 1.21.3, exposes no head group either, so a
 * strategy chain that misses its named root child silently renders the whole mob.
 */
final class EntityHeadGeometryTest {
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
    void normalizesTheCreeperSubjectToTheSharedVisualArea() {
        final ModelPart root = CreeperEntityModel.getTexturedModelData(Dilation.NONE).createModel();
        final float[] geometry = EntityHeadGeometry.project(
            EntityHeadGeometry.selectParts(creeperModel(root), "minecraft:creeper"),
            "minecraft:creeper", 0, 0
        );

        assertComparableToPig("creeper", subjectQuadArea(geometry), subjectQuadArea(projectPig()));
    }

    @Test
    void villagerSubjectIsComparableToPig() {
        final ModelPart root = TexturedModelData.of(
            VillagerResemblingModel.getModelData(), 64, 64
        ).createModel();
        final float[] villager = EntityHeadGeometry.project(
            EntityHeadGeometry.selectFromRoot(root, "minecraft:villager"),
            "minecraft:villager", 0, 0
        );
        final float pigArea = subjectQuadArea(projectPig());
        final float villagerArea = subjectQuadArea(villager);

        assertComparableToPig("villager", villagerArea, pigArea);
    }

    @Test
    void rabbitSubjectIsComparableToPig() {
        //#if MC>=260100
        //$$ final ModelPart rabbitRoot = AdultRabbitModel.createBodyLayer().bakeRoot();
        //#elseif MC>=12103
        //$$ final ModelPart rabbitRoot = RabbitEntityModel.getTexturedModelData(false).createModel();
        //#else
        final ModelPart rabbitRoot = RabbitEntityModel.getTexturedModelData().createModel();
        //#endif
        final float[] rabbit = EntityHeadGeometry.project(
            EntityHeadGeometry.selectFromRoot(rabbitRoot, "minecraft:rabbit"),
            "minecraft:rabbit", 0, 0
        );

        final float pigSubjectArea = subjectQuadArea(projectPig());
        final float rabbitSubjectArea = subjectQuadArea(rabbit);
        assertComparableToPig("rabbit", rabbitSubjectArea, pigSubjectArea);
    }

    //#if MC>=11900
    //$$ @Test
    //$$ void wardenOccupiesComparablePixelsToPig() {
    //#if MC>=260100
    //$$     final ModelPart wardenRoot = WardenModel.createBodyLayer().bakeRoot();
    //#else
    //$$     final ModelPart wardenRoot = WardenEntityModel.getTexturedModelData().createModel();
    //#endif
    //$$     final float[] warden = EntityHeadGeometry.project(
    //$$         EntityHeadGeometry.selectFromRoot(wardenRoot, "minecraft:warden"),
    //$$         "minecraft:warden", 0, 0
    //$$     );
    //$$     final int wardenPixels = PortraitPixelCoverage.occupiedPixels(warden);
    //$$     final int pigPixels = PortraitPixelCoverage.occupiedPixels(projectPig());
    //$$
    //$$     assertTrue(
    //$$         wardenPixels >= pigPixels * 0.9f && wardenPixels <= pigPixels * 1.2f,
    //$$         "warden occupied " + wardenPixels + " pixels (subject " + subjectQuadArea(warden)
    //$$             + ") while pig occupied " + pigPixels + " (subject " + subjectQuadArea(projectPig()) + ")"
    //$$     );
    //$$ }
    //#endif

    @Test
    void thinBranchWithMoreAreaDoesNotBecomeTheSubject() {
        final ModelData data = new ModelData();
        data.getRoot().addChild(
            "head",
            ModelPartBuilder.create().uv(0, 0).cuboid(-4f, -4f, -4f, 8f, 8f, 8f),
            ModelTransform.NONE
        ).addChild(
            "antenna",
            ModelPartBuilder.create().uv(0, 0).cuboid(-1f, -50f, -1f, 2f, 50f, 2f),
            ModelTransform.NONE
        );
        final ModelPart root = TexturedModelData.of(data, 64, 64).createModel();
        final float area = subjectQuadArea(EntityHeadGeometry.project(
            List.of(root.getChild("head")), "example:long_antenna", 0, 0
        ));

        assertComparableToPig("long-antenna subject", area, subjectQuadArea(projectPig()));
    }

    @Test
    void rotatedSubjectUsesOccupiedPixelsInsteadOfItsBoundingBox() {
        final ModelData data = new ModelData();
        data.getRoot().addChild(
            "head",
            ModelPartBuilder.create().uv(0, 0).cuboid(-4f, -4f, -4f, 8f, 8f, 8f),
            ModelTransform.of(0f, 0f, 0f, 0f, 0f, (float) Math.toRadians(45d))
        );
        final ModelPart root = TexturedModelData.of(data, 64, 64).createModel();
        final int occupied = PortraitPixelCoverage.occupiedPixels(EntityHeadGeometry.project(
            List.of(root.getChild("head")), "example:rotated_head", 0, 0
        ));

        assertTrue(occupied >= 400, "rotated subject occupied only " + occupied + " pixels");
    }

    @Test
    void planarSubjectStillProducesPortraitGeometry() {
        final ModelData data = new ModelData();
        data.getRoot().addChild(
            "head",
            ModelPartBuilder.create().uv(0, 0).cuboid(-4f, -4f, 0f, 8f, 8f, 0f),
            ModelTransform.NONE
        );
        final ModelPart root = TexturedModelData.of(data, 64, 64).createModel();
        final float[] geometry = EntityHeadGeometry.project(
            List.of(root.getChild("head")), "example:planar_head", 0, 0
        );

        assertTrue(geometry.length > 0, "a planar face must not degrade to a category dot");
    }

    private static float[] projectPig() {
        final ModelPart root = PigEntityModel.getTexturedModelData(Dilation.NONE).createModel();
        return EntityHeadGeometry.project(
            EntityHeadGeometry.selectFromRoot(root, "minecraft:pig"), "minecraft:pig", 0, 0
        );
    }

    private static void assertComparableToPig(
        final String subject,
        final float subjectArea,
        final float pigArea
    ) {
        assertTrue(
            subjectArea >= pigArea * 0.8f && subjectArea <= pigArea * 1.25f,
            subject + " subject area " + subjectArea + " was not comparable to pig " + pigArea
        );
    }

    private static float subjectQuadArea(final float[] geometry) {
        float bestCompactness = 0f;
        float subjectArea = 0f;
        for (int quad = 0; quad < geometry.length; quad += 20) {
            float minX = Float.POSITIVE_INFINITY;
            float minY = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY;
            float maxY = Float.NEGATIVE_INFINITY;
            for (int vertex = quad; vertex < quad + 20; vertex += 5) {
                minX = Math.min(minX, geometry[vertex]);
                maxX = Math.max(maxX, geometry[vertex]);
                minY = Math.min(minY, geometry[vertex + 1]);
                maxY = Math.max(maxY, geometry[vertex + 1]);
            }
            final float width = maxX - minX;
            final float height = maxY - minY;
            final float compactness = Math.min(width, height) * Math.min(width, height);
            if (compactness > bestCompactness) {
                bestCompactness = compactness;
                subjectArea = width * height;
            }
        }
        return subjectArea;
    }

}
