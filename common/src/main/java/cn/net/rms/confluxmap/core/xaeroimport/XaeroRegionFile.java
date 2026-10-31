package cn.net.rms.confluxmap.core.xaeroimport;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * One on-disk region file: either the current {@code <rx>_<rz>.zip} container
 * (exactly one {@code region.xaero} entry) or the legacy bare
 * {@code <rx>_<rz>.xaero} file holding the identical stream uncompressed.
 */
public record XaeroRegionFile(int regionX, int regionZ, Path path, boolean zipped) {
    private static final Pattern NAME = Pattern.compile("^(-?\\d+)_(-?\\d+)\\.(zip|xaero)$");
    private static final String STREAM_ENTRY = "region.xaero";

    /** Parses a region file name; null when the file is not map data. */
    public static XaeroRegionFile parse(final Path file) {
        final Matcher matcher = NAME.matcher(file.getFileName().toString());
        if (!matcher.matches()) {
            return null;
        }
        return new XaeroRegionFile(
            Integer.parseInt(matcher.group(1)),
            Integer.parseInt(matcher.group(2)),
            file,
            matcher.group(3).equals("zip")
        );
    }

    /**
     * Opens the region byte stream. The caller owns the returned stream and
     * must close it.
     */
    public InputStream open() throws IOException {
        final InputStream raw = new BufferedInputStream(Files.newInputStream(path));
        if (!zipped) {
            return raw;
        }
        final ZipInputStream zip = new ZipInputStream(raw);
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            if (STREAM_ENTRY.equals(entry.getName())) {
                return zip;
            }
        }
        zip.close();
        throw new XaeroImportException(path + " has no " + STREAM_ENTRY + " entry");
    }
}
