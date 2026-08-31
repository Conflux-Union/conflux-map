package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class StructureSearchBulkVisibilityTest {
    @Test
    void bulkActionsApplyToTheCurrentSearchResultsAndSaveOnce() throws Exception {
        final String source = Files.readString(preprocessedSource(
            "cn/net/rms/confluxmap/mc/ui/screen/StructureSearchScreen.java"
        ));
        final String method = sourceBetween(
            source,
            "private void setFilteredVisibility(",
            "\n    private boolean isVisible("
        );

        assertTrue(method.contains("final List<StructureIndex.StructureType> targets = filteredTypes();"));
        assertTrue(method.contains("for (final StructureIndex.StructureType type : targets)"));
        assertTrue(method.contains("structures.mcVersion(), dimension, type, visible"));
        assertEquals(1, occurrences(method, "saveConfig();"));
    }

    @Test
    void bulkButtonsFollowTheServerPolicy() throws Exception {
        final String source = Files.readString(preprocessedSource(
            "cn/net/rms/confluxmap/mc/ui/screen/StructureSearchScreen.java"
        ));
        final String method = sourceBetween(
            source,
            "private void updatePolicyAccess()",
            "\n    private void updateRows("
        );

        assertTrue(method.contains("selectAllButton"));
        assertTrue(method.contains("selectNoneButton"));
        assertTrue(method.contains("button.active = allowed"));
    }

    private static int occurrences(final String source, final String target) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(target, index)) >= 0) {
            count++;
            index += target.length();
        }
        return count;
    }

    private static String sourceBetween(final String source, final String start, final String end) {
        final int startIndex = source.indexOf(start);
        final int endIndex = source.indexOf(end, startIndex);
        if (startIndex < 0 || endIndex < 0) {
            throw new IllegalArgumentException("Could not locate source range");
        }
        return source.substring(startIndex, endIndex);
    }

    private static Path preprocessedSource(final String relativePath) throws URISyntaxException {
        Path current = Path.of(
            StructureSearchBulkVisibilityTest.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI()
        );
        while (current != null && !"build".equals(current.getFileName().toString())) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Could not locate the version build directory");
        }
        final Path preprocessed = current.resolve("preprocessed/main/java").resolve(relativePath);
        if (Files.exists(preprocessed)) {
            return preprocessed;
        }
        return current.getParent().getParent().getParent().resolve("src/main/java").resolve(relativePath);
    }
}
