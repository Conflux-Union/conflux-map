package cn.net.rms.confluxmap.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.predict.StructureIndex;
import com.google.gson.Gson;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class StructureVisibilityConfigTest {
    @Test
    void newProfilesShowEveryAvailableStructure() {
        final StructureVisibilityConfig config = new StructureVisibilityConfig();
        final EnumSet<StructureIndex.StructureType> available = EnumSet.of(
            StructureIndex.StructureType.VILLAGE,
            StructureIndex.StructureType.STRONGHOLD
        );

        assertEquals(available, config.visibleTypes(21, DimensionId.OVERWORLD, available));
    }

    @Test
    void hiddenTypesAreScopedByMinecraftVersionAndDimension() {
        final StructureVisibilityConfig config = new StructureVisibilityConfig();
        config.setVisible(21, DimensionId.OVERWORLD, StructureIndex.StructureType.VILLAGE, false);

        assertFalse(config.isVisible(21, DimensionId.OVERWORLD, StructureIndex.StructureType.VILLAGE));
        assertTrue(config.isVisible(30, DimensionId.OVERWORLD, StructureIndex.StructureType.VILLAGE));
        assertTrue(config.isVisible(21, DimensionId.NETHER, StructureIndex.StructureType.VILLAGE));

        config.setVisible(21, DimensionId.OVERWORLD, StructureIndex.StructureType.VILLAGE, true);
        assertTrue(config.isVisible(21, DimensionId.OVERWORLD, StructureIndex.StructureType.VILLAGE));
    }

    @Test
    void copyDoesNotShareMutableProfiles() {
        final StructureVisibilityConfig original = new StructureVisibilityConfig();
        original.setVisible(21, DimensionId.OVERWORLD, StructureIndex.StructureType.VILLAGE, false);

        final StructureVisibilityConfig copy = original.copy();
        copy.setVisible(21, DimensionId.OVERWORLD, StructureIndex.StructureType.STRONGHOLD, false);

        assertTrue(original.isVisible(21, DimensionId.OVERWORLD, StructureIndex.StructureType.STRONGHOLD));
        assertFalse(copy.isVisible(21, DimensionId.OVERWORLD, StructureIndex.StructureType.STRONGHOLD));
    }

    @Test
    void normalizePreservesUnknownIdsAndDropsMalformedEntries() {
        final StructureVisibilityConfig config = new Gson().fromJson(
            "{\"hiddenByProfile\":{" +
                "\"21|minecraft:overworld\":[\"village\",\"future_structure\",null]," +
                "\"\":[\"stronghold\"]," +
                "\"21|minecraft:the_end\":null}}",
            StructureVisibilityConfig.class
        );

        config.normalize();
        final String json = new Gson().toJson(config);

        assertTrue(json.contains("future_structure"));
        assertFalse(json.contains("the_end"));
        assertFalse(json.contains("stronghold"));
    }
}
