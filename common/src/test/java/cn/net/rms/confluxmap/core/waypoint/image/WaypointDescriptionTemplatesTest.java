package cn.net.rms.confluxmap.core.waypoint.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WaypointDescriptionTemplatesTest {
    @Test
    void builtInTemplatesCarryDistinctChineseAndEnglishLabels() {
        final Set<String> ids = new HashSet<>();
        for (final WaypointDescriptionTemplates.Template template
            : WaypointDescriptionTemplates.builtIn()) {
            assertFalse(template.zh().isBlank());
            assertFalse(template.en().isBlank());
            assertFalse(template.zh().equals(template.en()));
            ids.add(template.id());
        }
        assertEquals(WaypointDescriptionTemplates.builtIn().size(), ids.size());
    }
}
