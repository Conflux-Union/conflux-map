package cn.net.rms.confluxmap.server.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebMapPrivacyStoreTest {
    @Test
    void optOutSurvivesReload(@TempDir final Path directory) throws Exception {
        final UUID player = UUID.randomUUID();
        final Path file = directory.resolve("confluxmap/webmap-hidden.txt");
        final WebMapPrivacyStore first = new WebMapPrivacyStore(file);
        first.load();
        assertTrue(first.setHidden(player, true));

        final WebMapPrivacyStore reloaded = new WebMapPrivacyStore(file);
        reloaded.load();
        assertTrue(reloaded.hidden(player));
        assertTrue(reloaded.setHidden(player, false));
        assertFalse(reloaded.hidden(player));
    }
}
