package cn.net.rms.confluxmap.mc.radar;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.radar.RadarCategory;
import net.minecraft.entity.Entity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.MinecartEntity;
import org.junit.jupiter.api.Test;

/**
 * The type-registry variant of the scan classifier must bucket entity types exactly the way
 * live scans classify entity instances, so the visibility-management screen groups types by
 * the same category toggle that governs them at runtime.
 */
class EntityRadarClassifyTypeTest {
    @Test
    void playerClassAlwaysClassifiesAsPlayer() {
        assertEquals(
            RadarCategory.PLAYER,
            EntityRadarScanner.classifyType(PlayerEntity.class, SpawnGroup.MISC)
        );
    }

    @Test
    void monsterSpawnGroupOfMobClassifiesAsHostile() {
        assertEquals(
            RadarCategory.HOSTILE,
            EntityRadarScanner.classifyType(ZombieEntity.class, SpawnGroup.MONSTER)
        );
    }

    @Test
    void nonMonsterMobClassifiesAsPassive() {
        assertEquals(
            RadarCategory.PASSIVE,
            EntityRadarScanner.classifyType(CowEntity.class, SpawnGroup.CREATURE)
        );
    }

    @Test
    void nonMobEntityClassifiesAsOtherEvenForMonsterGroup() {
        assertEquals(
            RadarCategory.OTHER,
            EntityRadarScanner.classifyType(MinecartEntity.class, SpawnGroup.MONSTER)
        );
    }

    @Test
    void classifyTypeAcceptsBareEntityClass() {
        assertEquals(
            RadarCategory.OTHER,
            EntityRadarScanner.classifyType(Entity.class, SpawnGroup.MISC)
        );
    }

    @Test
    void spawnGroupFallbackBucketsMonsterAsHostile() {
        assertEquals(RadarCategory.HOSTILE, EntityRadarScanner.classifySpawnGroup(SpawnGroup.MONSTER));
    }

    @Test
    void spawnGroupFallbackBucketsMiscAsOther() {
        assertEquals(RadarCategory.OTHER, EntityRadarScanner.classifySpawnGroup(SpawnGroup.MISC));
    }

    @Test
    void spawnGroupFallbackBucketsCreatureWaterAndAmbientAsPassive() {
        assertEquals(RadarCategory.PASSIVE, EntityRadarScanner.classifySpawnGroup(SpawnGroup.CREATURE));
        assertEquals(RadarCategory.PASSIVE, EntityRadarScanner.classifySpawnGroup(SpawnGroup.WATER_CREATURE));
        assertEquals(RadarCategory.PASSIVE, EntityRadarScanner.classifySpawnGroup(SpawnGroup.AMBIENT));
    }
}
