package cn.net.rms.confluxmap.mc.radar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.radar.RadarCategory;
import cn.net.rms.confluxmap.core.radar.RadarEntry;
import java.util.List;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.AbstractDecorationEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
//#if MC>=12000
//$$ import net.minecraft.entity.decoration.DisplayEntity;
//$$ import net.minecraft.entity.decoration.InteractionEntity;
//#endif
import org.junit.jupiter.api.Test;

class EntityRadarScannerPolicyTest {
    @Test
    void serverPolicyCannotBeOverriddenByTheClientToggle() {
        assertTrue(EntityRadarScanner.scanningAllowed(true, true));
        assertFalse(EntityRadarScanner.scanningAllowed(false, true));
        assertFalse(EntityRadarScanner.scanningAllowed(true, false));
    }

    @Test
    void forbiddenPolicyHidesAnAlreadyPublishedSnapshot() {
        final List<RadarEntry> current = List.of(
            new RadarEntry(
                12.0, -4.0, 0, RadarCategory.PLAYER, "Alex", 7, false
            )
        );

        assertSame(current, EntityRadarScanner.visibleSnapshot(true, current));
        assertEquals(List.of(), EntityRadarScanner.visibleSnapshot(false, current));
    }

    @Test
    void staticDisplayEntitiesAreNotRadarCandidates() {
        assertFalse(EntityRadarScanner.isRadarCandidate(AbstractDecorationEntity.class));
        //#if MC>=12000
        //$$ assertFalse(EntityRadarScanner.isRadarCandidate(DisplayEntity.class));
        //$$ assertFalse(EntityRadarScanner.isRadarCandidate(InteractionEntity.class));
        //#endif

        assertTrue(EntityRadarScanner.isRadarCandidate(ArmorStandEntity.class));
        assertTrue(EntityRadarScanner.isRadarCandidate(ItemEntity.class));
    }
}
