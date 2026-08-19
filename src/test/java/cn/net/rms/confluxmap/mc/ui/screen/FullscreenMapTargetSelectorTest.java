package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class FullscreenMapTargetSelectorTest {
    private static final Pattern INTEGER_CONSTANT = Pattern.compile(
        "private static final int %s = (\\d+);"
    );

    @Test
    void targetSelectorsOnlyAppearInTerrainMode() throws IOException {
        final String source = source();
        final String controls = between(
            source,
            "private void rebuildWaypointControls()",
            "    private MapIconButton addToolGroupButton"
        );

        assertTrue(
            controls.contains("if (displayMode() == FullscreenDisplayMode.TERRAIN) {\n"
                + "            addTargetSelectors();\n"
                + "        }"),
            "temporary world and dimension selectors must be hidden outside terrain mode"
        );
        assertTrue(
            controls.contains("openTargetSelector = null;\n"
                + "            targetDropdownScrollOffset = 0;"),
            "leaving terrain mode must also close an open target dropdown"
        );
    }

    @Test
    void targetSelectorsUseTheCompactSize() throws IOException {
        final String source = source();

        assertEquals(128, integerConstant(source, "TARGET_SELECTOR_WIDTH"));
        assertEquals(16, integerConstant(source, "TARGET_SELECTOR_HEIGHT"));
        assertEquals(4, integerConstant(source, "TARGET_SELECTOR_GAP"));
    }

    private static int integerConstant(final String source, final String name) {
        final Matcher matcher = Pattern.compile(INTEGER_CONSTANT.pattern().formatted(name))
            .matcher(source);
        assertTrue(matcher.find(), name + " must remain a literal layout constant");
        return Integer.parseInt(matcher.group(1));
    }

    private static String between(final String source, final String start, final String end) {
        final int startIndex = source.indexOf(start);
        final int endIndex = source.indexOf(end, startIndex);
        assertTrue(startIndex >= 0 && endIndex > startIndex, "expected source section must exist");
        return source.substring(startIndex, endIndex);
    }

    private static String source() throws IOException {
        return Files.readString(projectRoot().resolve(
            "src/main/java/cn/net/rms/confluxmap/mc/ui/screen/FullscreenMapScreen.java"
        )).replace("\r\n", "\n");
    }

    private static Path projectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("common.gradle"))
                && Files.isDirectory(current.resolve("src/main/java"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate the Conflux Map project root");
    }
}
