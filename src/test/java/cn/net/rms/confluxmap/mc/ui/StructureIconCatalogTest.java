package cn.net.rms.confluxmap.mc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.predict.StructureIndex;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class StructureIconCatalogTest {
    @Test
    void everyStructureTypeHasAVanillaTexture() {
        for (final StructureIndex.StructureType type : StructureIndex.StructureType.values()) {
            assertEquals("minecraft", StructureIconCatalog.icon(type).getNamespace());
        }
    }

    @Test
    void everyMinecraft117StructureTextureExistsInTheClientJar() throws Exception {
        for (final DimensionId dimension : new DimensionId[] {
            DimensionId.OVERWORLD, DimensionId.NETHER, DimensionId.END
        }) {
            for (final StructureIndex.StructureType type :
                StructureIndex.StructureType.availableIn(21, dimension)) {
                assertTextureExists(type);
            }
        }
    }

    //#if MC>=12100
    //$$ @Test
    //$$ void everyModernStructureTextureExistsInTheClientJar() throws Exception {
    //$$     for (final StructureIndex.StructureType type : StructureIndex.StructureType.values()) {
    //$$         assertTextureExists(type);
    //$$     }
    //$$ }
    //#endif

    @Test
    void candidateAndVerifiedMarkersKeepDistinctFrames() {
        assertNotEquals(
            StructureMarkerRenderer.borderColor(StructureIndex.State.CANDIDATE),
            StructureMarkerRenderer.borderColor(StructureIndex.State.VERIFIED)
        );
    }

    @Test
    void visibleVariantsUseDistinctVanillaTextures() {
        assertNotEquals(
            StructureIconCatalog.icon(StructureIndex.StructureType.VILLAGE, 0),
            StructureIconCatalog.icon(StructureIndex.StructureType.VILLAGE, 1)
        );
        assertNotEquals(
            StructureIconCatalog.icon(StructureIndex.StructureType.VILLAGE, 1),
            StructureIconCatalog.icon(StructureIndex.StructureType.VILLAGE, 8)
        );
        assertNotEquals(
            StructureIconCatalog.icon(StructureIndex.StructureType.BASTION_REMNANT, 0),
            StructureIconCatalog.icon(StructureIndex.StructureType.BASTION_REMNANT, 2)
        );
        assertNotEquals(
            StructureIconCatalog.icon(StructureIndex.StructureType.END_CITY, 0),
            StructureIconCatalog.icon(StructureIndex.StructureType.END_CITY, 1)
        );
        assertEquals(
            StructureIconCatalog.icon(StructureIndex.StructureType.STRONGHOLD),
            StructureIconCatalog.icon(StructureIndex.StructureType.STRONGHOLD, 99)
        );
    }

    @Test
    void everyVariantTextureExistsInTheClientJar() throws Exception {
        assertVariantTextures(StructureIndex.StructureType.VILLAGE, 0, 1, 2, 3, 4, 8);
        assertVariantTextures(StructureIndex.StructureType.IGLOO, 0, 1);
        assertVariantTextures(StructureIndex.StructureType.SHIPWRECK, 0, 1);
        assertVariantTextures(StructureIndex.StructureType.BASTION_REMNANT, 0, 1, 2, 3);
        assertVariantTextures(StructureIndex.StructureType.RUINED_PORTAL, 0, 1);
        assertVariantTextures(StructureIndex.StructureType.END_CITY, 0, 1);
    }

    private static void assertTextureExists(final StructureIndex.StructureType type) throws Exception {
        assertTextureExists(type, 0);
    }

    private static void assertVariantTextures(
        final StructureIndex.StructureType type,
        final int... variants
    ) throws Exception {
        for (final int variant : variants) {
            assertTextureExists(type, variant);
        }
    }

    private static void assertTextureExists(
        final StructureIndex.StructureType type,
        final int variant
    ) throws Exception {
        final String resource = "/assets/minecraft/" + StructureIconCatalog.icon(type, variant).getPath();
        try (InputStream stream = StructureIconCatalogTest.class.getResourceAsStream(resource)) {
            assertNotNull(
                stream,
                "missing vanilla texture " + resource + " for " + type.id() + " variant " + variant
            );
        }
    }
}
