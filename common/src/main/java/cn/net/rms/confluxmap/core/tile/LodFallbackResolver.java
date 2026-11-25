package cn.net.rms.confluxmap.core.tile;

import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.util.TileMath;
import java.util.List;
import java.util.function.Predicate;

/**
 * Resolves cross-LOD fallback textures for a missing tile without queueing any work.
 *
 * <p>Searches ancestors up to {@link TileMath#MAX_LOD} and descends finer child subtrees down to
 * LOD 0. Search priority follows zoom direction ({@code preferFiner}): zooming in prefers the
 * nearest coarser ancestor (100% tile coverage, falling back to finer subtrees when no ancestor is
 * resident), whereas zooming out prefers resident finer child subtrees and falls back to any
 * coarser ancestor underneath if finer tiles cover the area only partially.
 */
public final class LodFallbackResolver {
    private LodFallbackResolver() {
    }

    /** Normalized sub-rectangle inside the target tile to sample from a resident fallback key. */
    public record FallbackQuad(
        TileKey key,
        float relX,
        float relY,
        float relSize,
        float u0,
        float v0,
        float u1,
        float v1
    ) {
    }

    /**
     * Populates {@code out} with the fallback quads covering {@code target}.
     *
     * @return {@code true} if at least one resident fallback quad was found.
     */
    public static boolean resolve(
        final TileKey target,
        final boolean preferFiner,
        final Predicate<TileKey> usable,
        final List<FallbackQuad> out
    ) {
        out.clear();
        if (preferFiner) {
            final int beforeChildren = out.size();
            final boolean fullFinerCover = target.lod() > 0
                && collectFinerChildren(target, target, 0f, 0f, 1f, usable, out);
            if (!fullFinerCover) {
                final FallbackQuad ancestor = findNearestAncestor(target, usable);
                if (ancestor != null) {
                    if (out.size() == beforeChildren) {
                        out.add(ancestor);
                    } else {
                        // Paint the coarser ancestor underneath partial finer children so gaps
                        // stay covered without overriding sharper child quadrants.
                        out.add(beforeChildren, ancestor);
                    }
                }
            }
            return !out.isEmpty();
        }
        final FallbackQuad ancestor = findNearestAncestor(target, usable);
        if (ancestor != null) {
            out.add(ancestor);
            return true;
        }
        if (target.lod() > 0) {
            collectFinerChildren(target, target, 0f, 0f, 1f, usable, out);
        }
        return !out.isEmpty();
    }

    private static FallbackQuad findNearestAncestor(
        final TileKey target,
        final Predicate<TileKey> usable
    ) {
        final float[] uv = new float[4];
        for (int ancestorLod = target.lod() + 1; ancestorLod <= TileMath.MAX_LOD; ancestorLod++) {
            final int delta = ancestorLod - target.lod();
            final TileKey ancestor = new TileKey(
                target.world(),
                target.dimension(),
                target.layerId(),
                ancestorLod,
                TileMath.ancestorTile(target.tileX(), delta),
                TileMath.ancestorTile(target.tileZ(), delta)
            );
            if (usable.test(ancestor)) {
                TileMath.ancestorUv(target.tileX(), target.tileZ(), delta, uv);
                return new FallbackQuad(ancestor, 0f, 0f, 1f, uv[0], uv[1], uv[2], uv[3]);
            }
        }
        return null;
    }

    private static boolean collectFinerChildren(
        final TileKey root,
        final TileKey node,
        final float relX,
        final float relY,
        final float relSize,
        final Predicate<TileKey> usable,
        final List<FallbackQuad> out
    ) {
        if (node.lod() <= 0) {
            return false;
        }
        final int childLod = node.lod() - 1;
        final float half = relSize * 0.5f;
        boolean allCovered = true;
        for (int qz = 0; qz <= 1; qz++) {
            for (int qx = 0; qx <= 1; qx++) {
                final TileKey child = new TileKey(
                    root.world(),
                    root.dimension(),
                    root.layerId(),
                    childLod,
                    TileMath.childTile(node.tileX(), qx),
                    TileMath.childTile(node.tileZ(), qz)
                );
                final float childX = relX + qx * half;
                final float childY = relY + qz * half;
                if (usable.test(child)) {
                    out.add(new FallbackQuad(child, childX, childY, half, 0f, 0f, 1f, 1f));
                } else {
                    allCovered &= collectFinerChildren(root, child, childX, childY, half, usable, out);
                }
            }
        }
        return allCovered;
    }
}
