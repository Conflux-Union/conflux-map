package cn.net.rms.confluxmap.server;

import static org.junit.jupiter.api.Assertions.assertFalse;

import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

class ChunkSummarizerTest {
    @Test
    void structureStartsChunkIsNotTreatedAsGeneratedSurfaceData() {
        final NbtCompound level = new NbtCompound();
        level.putString("Status", "structure_starts");
        final NbtCompound root = new NbtCompound();
        root.put("Level", level);

        assertFalse(new ChunkSummarizer().summarize(root).generated());
    }
}
