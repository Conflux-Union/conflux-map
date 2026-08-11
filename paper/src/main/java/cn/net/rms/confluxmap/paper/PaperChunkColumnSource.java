package cn.net.rms.confluxmap.paper;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.server.ChunkColumnSource;
import java.util.Locale;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;

/** Immutable Paper ChunkSnapshot adapter for the platform-free summary algorithm. */
final class PaperChunkColumnSource implements ChunkColumnSource {
    private final ChunkSnapshot snapshot;
    private final long revision;
    private final int minHeight;
    private final int maxHeight;
    private final int[] motionBlockingHeights = new int[16 * 16];

    PaperChunkColumnSource(
        final ChunkSnapshot snapshot,
        final long revision,
        final int minHeight,
        final int maxHeight
    ) {
        this.snapshot = snapshot;
        this.revision = revision;
        this.minHeight = minHeight;
        this.maxHeight = maxHeight;
        java.util.Arrays.fill(motionBlockingHeights, Integer.MIN_VALUE);
    }

    @Override
    public boolean generated() {
        return snapshot != null;
    }

    @Override
    public long revision() {
        return revision;
    }

    @Override
    public int bottomY() {
        return minHeight;
    }

    @Override
    public int motionBlockingHeight(final int x, final int z) {
        final int index = z * 16 + x;
        final int cached = motionBlockingHeights[index];
        if (cached != Integer.MIN_VALUE) {
            return cached;
        }
        final int highest = Math.min(maxHeight - 1, snapshot.getHighestBlockYAt(x, z));
        for (int y = highest; y >= minHeight; y--) {
            final BlockData block = snapshot.getBlockData(x, y, z);
            if (block.getMaterial().isSolid() || fluidKind(block) != SurfaceKind.UNKNOWN) {
                motionBlockingHeights[index] = y + 1;
                return y + 1;
            }
        }
        motionBlockingHeights[index] = minHeight;
        return minHeight;
    }

    @Override
    public int oceanFloorHeight(final int x, final int z) {
        for (int y = motionBlockingHeight(x, z) - 1; y >= bottomY(); y--) {
            final BlockData block = snapshot.getBlockData(x, y, z);
            if (block.getMaterial().isSolid()) {
                return y + 1;
            }
        }
        return NO_HEIGHT;
    }

    @Override
    public int blockLightAbove(final int x, final int surfaceY, final int z) {
        final int surfaceBlockY = Math.max(minHeight, Math.min(surfaceY, maxHeight - 1));
        final int aboveY = Math.min(surfaceBlockY + 1, maxHeight - 1);
        // ChunkSnapshot exposes the light emitted by a block, not the propagated block-light
        // value at an air position. The old air-only lookup therefore returned 0 above every
        // glowstone roof. Include the sampled surface block so self-emitting roof materials keep
        // their authoritative light level on Paper as well.
        return Math.max(
            snapshot.getBlockEmittedLight(x, surfaceBlockY, z),
            snapshot.getBlockEmittedLight(x, aboveY, z)
        );
    }

    @Override
    public String blockNameAt(final int x, final int y, final int z) {
        if (y < minHeight || y >= maxHeight) {
            return "minecraft:air";
        }
        return snapshot.getBlockType(x, y, z).getKey().toString();
    }

    @Override
    public SurfaceKind fluidKindAt(final int x, final int y, final int z) {
        if (y < minHeight || y >= maxHeight) {
            return SurfaceKind.UNKNOWN;
        }
        return fluidKind(snapshot.getBlockData(x, y, z));
    }

    @Override
    public int biomeIdAt(final int x, final int y, final int z) {
        final int clampedY = Math.max(
            minHeight,
            Math.min(maxHeight - 1, y)
        );
        final Biome biome = snapshot.getBiome(x, clampedY, z);
        final String name = biome.getKey().getKey().toLowerCase(Locale.ROOT);
        return cn.net.rms.confluxmap.core.predict.CubiomesBiomeIds.idForName(name).orElse(1);
    }

    private static SurfaceKind fluidKind(final BlockData block) {
        final Material material = block.getMaterial();
        if (material == Material.WATER || block instanceof Waterlogged waterlogged && waterlogged.isWaterlogged()) {
            return SurfaceKind.WATER;
        }
        if (material == Material.LAVA) {
            return SurfaceKind.LAVA;
        }
        final String name = material.getKey().getKey();
        if (name.contains("kelp") || name.contains("seagrass") || name.equals("bubble_column")
            || name.equals("sea_pickle")) {
            return SurfaceKind.WATER;
        }
        return SurfaceKind.UNKNOWN;
    }
}
