package cn.net.rms.confluxmap.mc.snapshot;

import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.core.color.BiomeSampleWindow;
import cn.net.rms.confluxmap.mc.color.BiomeTintResolver;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkStatus;

/**
 * Resolves biome tints for one chunk at a time, keeping every sample position inside the
 * chunk's {@link BiomeSampleWindow}.
 *
 * <p>The game blends a tint over a square reaching {@code biomeBlendRadius} blocks into the
 * neighbouring chunks, and answers the client's plains fallback biome for any position in a
 * chunk that has not arrived yet. A snapshot bakes the color it sampled, so a border column
 * sampled one tick too early keeps plains-blue water forever. Clamping the sample position
 * into the window trades a biome edge displaced by at most the blend radius - only in a chunk
 * whose neighbourhood is incomplete, and only until {@link ChunkCaptureService} samples it
 * again - for never averaging in a biome that isn't there.
 *
 * <p>Clamping also keeps the game's own per-column tint cache useful: the clamped position's
 * blend square lies inside the chunk, so whatever the cache holds for it was computed from
 * loaded data. Main thread only, like the factory that owns it.
 */
final class ChunkTintSampler {
    private final MinecraftClient client;
    private final BiomeTintResolver resolver;
    private final BlockPos.Mutable samplePos = new BlockPos.Mutable();

    private BiomeSampleWindow window = BiomeSampleWindow.FULL;
    private BiomeSampleWindow biomeIdentityWindow = BiomeSampleWindow.FULL;
    private int baseX;
    private int baseZ;

    ChunkTintSampler(final MinecraftClient client, final BiomeTintResolver resolver) {
        this.client = client;
        this.resolver = resolver;
    }

    /** Points the sampler at a chunk, deriving its window from which neighbours are loaded. */
    void beginChunk(final ClientWorld world, final int chunkX, final int chunkZ) {
        baseX = chunkX << 4;
        baseZ = chunkZ << 4;
        final int blendRadius = MinecraftAccess.biomeBlendRadius(client);
        final boolean northWest = loaded(world, chunkX - 1, chunkZ - 1);
        final boolean north = loaded(world, chunkX, chunkZ - 1);
        final boolean northEast = loaded(world, chunkX + 1, chunkZ - 1);
        final boolean west = loaded(world, chunkX - 1, chunkZ);
        final boolean east = loaded(world, chunkX + 1, chunkZ);
        final boolean southWest = loaded(world, chunkX - 1, chunkZ + 1);
        final boolean south = loaded(world, chunkX, chunkZ + 1);
        final boolean southEast = loaded(world, chunkX + 1, chunkZ + 1);
        final boolean westClear = west && northWest && southWest;
        final boolean eastClear = east && northEast && southEast;
        final boolean northClear = north && northWest && northEast;
        final boolean southClear = south && southWest && southEast;
        window = BiomeSampleWindow.of(
            blendRadius, westClear, eastClear, northClear, southClear
        );
        biomeIdentityWindow = BiomeSampleWindow.of(
            BiomeIdentityCapture.VORONOI_BORDER_INSET,
            westClear,
            eastClear,
            northClear,
            southClear
        );
    }

    BiomeSampleWindow biomeIdentityWindow() {
        return biomeIdentityWindow;
    }

    /** The tint for {@code state}, sampled at the window-clamped column of the given position. */
    int resolve(final BlockState state, final ClientWorld world, final int worldX, final int y, final int worldZ) {
        samplePos.set(
            baseX + window.clampLocalX(worldX - baseX),
            y,
            baseZ + window.clampLocalZ(worldZ - baseZ)
        );
        return resolver.resolve(state, world, samplePos);
    }

    static boolean loaded(final ClientWorld world, final int chunkX, final int chunkZ) {
        return world.getChunkManager().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) != null;
    }
}
