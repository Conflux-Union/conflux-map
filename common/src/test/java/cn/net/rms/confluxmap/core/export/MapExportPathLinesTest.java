package cn.net.rms.confluxmap.core.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class MapExportPathLinesTest {
    @Test
    void wrapsAtDirectoryBoundariesWithoutDroppingThePngFileName() {
        final String path = "/home/Trirrin/confluxmap/exports/world-123.png";

        final List<String> lines = MapExportPathLines.wrap(path, 18, String::length);

        assertEquals(path, String.join("", lines));
        assertEquals("world-123.png", lines.get(lines.size() - 1));
        assertTrue(lines.stream().allMatch(line -> line.length() <= 18));
    }

    @Test
    void wrapsLongUnicodeFileNamesOnlyAtCodePointBoundaries() {
        final String path = "/地图/非常长的🗺导出文件名.png";

        final List<String> lines = MapExportPathLines.wrap(
            path, 8, value -> value.codePointCount(0, value.length()) * 2
        );

        assertEquals(path, String.join("", lines));
        assertTrue(lines.stream().noneMatch(line ->
            !line.isEmpty() && Character.isHighSurrogate(line.charAt(line.length() - 1))
        ));
    }
}
