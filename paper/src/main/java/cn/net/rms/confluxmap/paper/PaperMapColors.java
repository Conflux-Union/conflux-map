package cn.net.rms.confluxmap.paper;

import cn.net.rms.confluxmap.core.predict.MapColorTable;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

/** Immutable main-thread snapshot of Bukkit block map colors for asynchronous Anvil scans. */
final class PaperMapColors {
    private final Map<String, Integer> colors;

    PaperMapColors() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Paper map colors must be captured on the primary thread");
        }
        final Map<String, Integer> captured = new HashMap<>();
        for (final Material material : Material.values()) {
            if (!material.isBlock()) {
                continue;
            }
            final int color = resolve(material);
            if (color >= 0) {
                captured.put(material.getKey().toString(), color);
            }
        }
        colors = Map.copyOf(captured);
    }

    int mapColorId(final String blockName) {
        return blockName == null ? -1 : colors.getOrDefault(blockName, -1);
    }

    private static int resolve(final Material material) {
        try {
            final BlockData data = Bukkit.createBlockData(material);
            final int rgb = data.getMapColor().asRGB();
            for (int id = 1; id < MapColorTable.size(); id++) {
                if ((MapColorTable.argb(id) & 0xFFFFFF) == rgb) {
                    return id;
                }
            }
        } catch (final RuntimeException ignored) {
            // Disabled blocks use the summarizer's stable heuristic fallback.
        }
        return -1;
    }
}
