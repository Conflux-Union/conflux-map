package cn.net.rms.confluxmap.paper;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.core.util.ChunkRegionSlice;
import cn.net.rms.confluxmap.server.ChunkColumnSummarizer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class PaperRealWorldMcaTest {
    @Test
    void scansARegionSavedByPaper() throws IOException {
        final String configured = System.getProperty("confluxmap.paper.realRegionDir");
        Assumptions.assumeTrue(
            configured != null && !configured.isBlank(),
            "set confluxmap.paper.realRegionDir for the opt-in integration test"
        );

        final Path directory = Path.of(configured);
        final List<Path> files;
        try (var paths = Files.list(directory)) {
            files = paths.filter(path -> path.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.mca"))
                .limit(8)
                .toList();
        }
        final PaperAnvilReader reader = new PaperAnvilReader();
        final ChunkColumnSummarizer summarizer = new ChunkColumnSummarizer(name -> 11);
        boolean generated = false;
        for (final Path file : files) {
            final String[] coordinates = file.getFileName().toString().split("\\.");
            final int mcaX = Integer.parseInt(coordinates[1]);
            final int mcaZ = Integer.parseInt(coordinates[2]);
            for (int localZ = 0; localZ < 2 && !generated; localZ++) {
                for (int localX = 0; localX < 2 && !generated; localX++) {
                    final int regionX = mcaX * 2 + localX;
                    final int regionZ = mcaZ * 2 + localZ;
                    final SummaryCodec.SampledRegion region = reader.scanRegion(
                        directory,
                        4,
                        new ChunkRegionSlice(regionX, regionZ, 0, 0, 15, 15),
                        summarizer
                    );
                    assertNotNull(region);
                    generated = java.util.Arrays.stream(region.chunks())
                        .anyMatch(SummaryCodec.SampledChunk::generated);
                }
            }
            if (generated) {
                break;
            }
        }
        assertTrue(generated, "Paper's saved region files should contain a generated chunk");
    }
}
