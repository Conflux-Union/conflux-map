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

    private static void assertTextureExists(final StructureIndex.StructureType type) throws Exception {
        final String resource = "/assets/minecraft/" + StructureIconCatalog.icon(type).getPath();
        try (InputStream stream = StructureIconCatalogTest.class.getResourceAsStream(resource)) {
            assertNotNull(stream, "missing vanilla texture " + resource + " for " + type.id());
        }
    }
}
