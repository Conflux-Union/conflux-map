package cn.net.rms.confluxmap.core.tile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LodFallbackResolverTest {
    private static final WorldIdentity WORLD = new WorldIdentity("local", "fallback-test");
    private static final DimensionId DIM = DimensionId.OVERWORLD;
    private static final String LAYER = MapLayer.SURFACE.cacheId();

    private static TileKey key(final int lod, final int x, final int z) {
        return new TileKey(WORLD, DIM, LAYER, lod, x, z);
    }

    @Test
    void zoomingInSkipsMultipleMissingParentLodsToReachResidentAncestor() {
        final TileKey target = key(0, -3, -1); // ancestor at LOD 3: tile (-1, -1), uv [5/8..6/8, 7/8..1]
        final Set<TileKey> resident = Set.of(key(3, -1, -1));
        final List<LodFallbackResolver.FallbackQuad> out = new ArrayList<>();

        assertTrue(LodFallbackResolver.resolve(target, false, resident::contains, out));
        assertEquals(1, out.size());
        final LodFallbackResolver.FallbackQuad quad = out.get(0);
        assertEquals(key(3, -1, -1), quad.key());
        assertEquals(0f, quad.relX());
        assertEquals(0f, quad.relY());
        assertEquals(1f, quad.relSize());
        assertEquals(0.625f, quad.u0());
        assertEquals(0.875f, quad.v0());
        assertEquals(0.75f, quad.u1());
        assertEquals(1.0f, quad.v1());
    }

    @Test
    void zoomingOutRecursesMultiLevelChildrenWithoutOverdraw() {
        final TileKey target = key(2, 0, 0);
        // LOD 1 has NW(0,0), NE(1,0), SE(1,1); SW(0,1) is missing at LOD 1, covered at LOD 0 by (0,2) & (1,3).
        final Set<TileKey> resident = Set.of(
            key(1, 0, 0),
            key(1, 1, 0),
            key(1, 1, 1),
            key(0, 0, 2),
            key(0, 1, 3),
            key(3, 0, 0) // coarse ancestor should cover ONLY the missing LOD 0 pieces under SW(0,1)
        );
        final List<LodFallbackResolver.FallbackQuad> out = new ArrayList<>();

        assertTrue(LodFallbackResolver.resolve(target, true, resident::contains, out));
        // Since SW(0,1) is only partially covered, LOD 3 ancestor comes first as underlay, followed by 5 child quads.
        assertEquals(6, out.size());
        assertEquals(key(3, 0, 0), out.get(0).key());
        assertEquals(key(1, 0, 0), out.get(1).key());
        assertEquals(key(1, 1, 0), out.get(2).key());
        assertEquals(key(0, 0, 2), out.get(3).key());
        assertEquals(0.0f, out.get(3).relX());
        assertEquals(0.5f, out.get(3).relY());
        assertEquals(0.25f, out.get(3).relSize());
        assertEquals(key(0, 1, 3), out.get(4).key());
        assertEquals(key(1, 1, 1), out.get(5).key());
    }

    @Test
    void zoomingOutFullChildCoverageSuppressesCoarseAncestorToPreventFlicker() {
        final TileKey target = key(2, 0, 0);
        final Set<TileKey> resident = Set.of(
            key(1, 0, 0),
            key(1, 1, 0),
            key(1, 0, 1),
            key(1, 1, 1),
            key(3, 0, 0)
        );
        final List<LodFallbackResolver.FallbackQuad> out = new ArrayList<>();

        assertTrue(LodFallbackResolver.resolve(target, true, resident::contains, out));
        assertEquals(4, out.size());
        assertTrue(out.stream().allMatch(quad -> quad.key().lod() == 1));
    }

    @Test
    void zoomingInWithNoAncestorFallsBackToFinerChildren() {
        final TileKey target = key(2, 0, 0);
        final Set<TileKey> resident = Set.of(key(0, 0, 0));
        final List<LodFallbackResolver.FallbackQuad> out = new ArrayList<>();

        assertTrue(LodFallbackResolver.resolve(target, false, resident::contains, out));
        assertEquals(1, out.size());
        assertEquals(key(0, 0, 0), out.get(0).key());
    }

    @Test
    void biomeAndPredictedLayerIdsStayAlignedAcrossAllFallbackLevels() {
        final TileKey biomePred = new TileKey(WORLD, DIM, "surface!biome!pred", 3, 1, 1);
        final TileKey child = new TileKey(WORLD, DIM, "surface!biome!pred", 1, 4, 4);
        final List<LodFallbackResolver.FallbackQuad> out = new ArrayList<>();

        assertTrue(LodFallbackResolver.resolve(biomePred, true, child::equals, out));
        assertEquals(1, out.size());
        assertEquals("surface!biome!pred", out.get(0).key().layerId());
        assertFalse(LodFallbackResolver.resolve(biomePred, true, ignored -> false, out));
        assertTrue(out.isEmpty());
    }
}
