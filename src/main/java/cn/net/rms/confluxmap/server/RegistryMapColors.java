package cn.net.rms.confluxmap.server;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.util.Identifier;
import net.minecraft.util.registry.Registry;

/**
 * Registry-backed {@link ChunkSummarizer.MapColorResolver}: block id string to the block's
 * vanilla default map-colour id, so corrections carry the same colour a vanilla map item
 * would show instead of the summarizer's coarse natural/artificial heuristic. Results are
 * memoized per name - region summaries hit the same few palette entries thousands of times.
 *
 * <p>Registry reads are safe off the server thread: the block registry is fully populated
 * during bootstrap and never mutated afterwards. Unknown ids (a mod removed between world
 * writes, a malformed palette entry) and CLEAR (id 0) resolve to -1 so the summarizer keeps
 * its heuristic fallback for them.
 */
public final class RegistryMapColors implements ChunkSummarizer.MapColorResolver {
    private final Map<String, Integer> cache = new ConcurrentHashMap<>();

    @Override
    public int mapColorId(final String blockName) {
        if (blockName == null) {
            return -1;
        }
        return cache.computeIfAbsent(blockName, RegistryMapColors::resolve);
    }

    private static int resolve(final String name) {
        final Identifier id = Identifier.tryParse(name);
        if (id == null) {
            return -1;
        }
        final Optional<Block> block = Registry.BLOCK.getOrEmpty(id);
        if (block.isEmpty()) {
            return -1;
        }
        try {
            final MapColor color = block.get().getDefaultMapColor();
            return color == null ? -1 : color.id;
        } catch (final RuntimeException e) {
            return -1;
        }
    }
}
