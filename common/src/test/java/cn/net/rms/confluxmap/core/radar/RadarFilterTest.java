package cn.net.rms.confluxmap.core.radar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class RadarFilterTest {
    private static RadarEntry entry(
        final String entityType,
        final RadarCategory category,
        final double x
    ) {
        return new RadarEntry(x, 0.0, 0, category, null, (int) x, false, null, entityType);
    }

    @Test
    void typeOverrideShowsEntityWhenCategoryIsHidden() {
        final RadarFilter filter = new RadarFilter(
            true, false, true, true, 100,
            Map.of("minecraft:creeper", true)
        );

        assertTrue(filter.allows(RadarCategory.HOSTILE, "minecraft:creeper"));
        final List<RadarEntry> kept = filter.apply(
            List.of(entry("minecraft:creeper", RadarCategory.HOSTILE, 1.0)), 0.0, 0.0
        );
        assertEquals(1, kept.size());
    }

    @Test
    void typeOverrideHidesEntityWhenCategoryIsShown() {
        final RadarFilter filter = new RadarFilter(
            true, true, true, true, 100,
            Map.of("minecraft:bat", false)
        );

        assertFalse(filter.allows(RadarCategory.PASSIVE, "minecraft:bat"));
        assertTrue(filter.apply(
            List.of(entry("minecraft:bat", RadarCategory.PASSIVE, 1.0)), 0.0, 0.0
        ).isEmpty());
    }

    @Test
    void typesWithoutOverrideFollowCategoryToggles() {
        final RadarFilter filter = new RadarFilter(
            true, false, true, true, 100,
            Map.of("minecraft:creeper", true)
        );

        assertFalse(filter.allows(RadarCategory.HOSTILE, "minecraft:zombie"));
        assertTrue(filter.allows(RadarCategory.PASSIVE, "minecraft:cow"));
    }

    @Test
    void nullEntityTypeFallsBackToCategoryToggle() {
        final RadarFilter filter = new RadarFilter(
            true, false, true, true, 100,
            Map.of("minecraft:creeper", true)
        );

        assertFalse(filter.allows(RadarCategory.HOSTILE, null));
        assertTrue(filter.apply(
            List.of(entry(null, RadarCategory.HOSTILE, 1.0)), 0.0, 0.0
        ).isEmpty());
    }

    @Test
    void emptyOverridesBehaveLikeCategoryOnlyFilter() {
        final RadarFilter filter = new RadarFilter(
            true, true, false, true, 100, Map.of()
        );

        assertFalse(filter.allows(RadarCategory.PASSIVE, "minecraft:cow"));
        assertTrue(filter.allows(RadarCategory.OTHER, "minecraft:minecart"));
    }

    @Test
    void playerTypeOverrideTakesPriorityOverPlayerToggle() {
        final RadarFilter filter = new RadarFilter(
            true, true, true, true, 100,
            Map.of("minecraft:player", false)
        );

        assertFalse(filter.allows(RadarCategory.PLAYER, "minecraft:player"));
    }

    @Test
    void maxEntitiesStillCapsAfterTypeOverrides() {
        final RadarFilter filter = new RadarFilter(
            true, false, true, true, 2,
            Map.of("minecraft:creeper", true)
        );
        final List<RadarEntry> raw = List.of(
            entry("minecraft:creeper", RadarCategory.HOSTILE, 1.0),
            entry("minecraft:creeper", RadarCategory.HOSTILE, 5.0),
            entry("minecraft:creeper", RadarCategory.HOSTILE, 30.0),
            entry("minecraft:cow", RadarCategory.PASSIVE, 2.0)
        );

        final List<RadarEntry> kept = filter.apply(raw, 0.0, 0.0);

        // cap keeps the two nearest: creeper at 1.0 and the cow at 2.0, not the farther creeper
        assertEquals(2, kept.size());
        assertEquals("minecraft:creeper", kept.get(0).entityType());
        assertEquals("minecraft:cow", kept.get(1).entityType());
    }
}
