package cn.net.rms.confluxmap.mc.mapbackfill;

import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.core.backfill.McaRegionScanner;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.core.util.ChunkRegionSlice;
import cn.net.rms.confluxmap.server.AnvilMcaScanner;
import cn.net.rms.confluxmap.server.ChunkSummarizer;
import cn.net.rms.confluxmap.server.RegistryMapColors;
import cn.net.rms.confluxmap.server.RegionStoragePaths;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.MinecraftClient;

/**
 * Reads summary regions out of the singleplayer save's Anvil files, reusing the
 * server companion's scan stack (native JNI fast path with the Mojang NBT
 * fallback) and the registry-backed map-colour resolver. The save root is
 * resolved on the client thread once per integrated-server instance —
 * {@link #mtimeMs} is called from the per-frame viewport patrol — and reused by
 * the worker-side {@link #scanRegion}; a session change swaps the server
 * instance, which re-resolves on the next probe, and stale reads are discarded
 * by the backfill service's token checks.
 */
public final class ClientMcaRegionScanner implements McaRegionScanner {
    private static final int REGION_CHUNKS = ChunkRegionSlice.REGION_CHUNKS;

    private final MinecraftClient client;
    private final AnvilMcaScanner scanner;
    private final ChunkSummarizer summarizer;
    private volatile Path saveRoot;
    /** Server instance {@link #saveRoot} was resolved from; never equal to a fresh instance. */
    private volatile Object saveRootServer = new Object();

    public ClientMcaRegionScanner(final MinecraftClient client) {
        this(client, new AnvilMcaScanner(), new ChunkSummarizer(new RegistryMapColors()), null);
    }

    /** Test seam: scanner, summarizer, and a fixed save root stay injectable (client null). */
    ClientMcaRegionScanner(
        final MinecraftClient client,
        final AnvilMcaScanner scanner,
        final ChunkSummarizer summarizer,
        final Path initialSaveRoot
    ) {
        this.client = client;
        this.scanner = scanner;
        this.summarizer = summarizer;
        this.saveRoot = initialSaveRoot;
    }

    @Override
    public long mtimeMs(final DimensionId dimension, final int regionX, final int regionZ) {
        final Path root = currentSaveRoot();
        return root == null
            ? 0L
            : RegionStoragePaths.mcaMtimeMs(root, dimension.toString(), regionX, regionZ);
    }

    @Override
    public SummaryCodec.SampledRegion scanRegion(
        final DimensionId dimension,
        final int regionX,
        final int regionZ,
        final long expectedMtimeMs
    ) {
        final Path root = saveRoot;
        if (root == null) {
            return null;
        }
        final Path mca = RegionStoragePaths.mcaFile(root, dimension.toString(), regionX, regionZ);
        if (!Files.isRegularFile(mca)) {
            return null;
        }
        return scanner.scanRegion(
            mca,
            Math.floorDiv(regionX, 2),
            Math.floorDiv(regionZ, 2),
            expectedMtimeMs,
            0,
            new ChunkRegionSlice(regionX, regionZ, 0, 0, REGION_CHUNKS - 1, REGION_CHUNKS - 1),
            summarizer
        );
    }

    private Path currentSaveRoot() {
        if (client != null && client.isOnThread()) {
            final Object server = MinecraftAccess.singleplayerServerRef(client);
            if (server != saveRootServer) {
                saveRoot = MinecraftAccess.singleplayerSaveRoot(client);
                saveRootServer = server;
            }
        }
        return saveRoot;
    }
}
