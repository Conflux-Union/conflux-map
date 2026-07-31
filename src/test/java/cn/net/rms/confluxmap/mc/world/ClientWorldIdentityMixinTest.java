package cn.net.rms.confluxmap.mc.world;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class ClientWorldIdentityMixinTest {
    @Test
    void identityHooksRunAfterPacketHandlingMovesToTheClientThread() throws Exception {
        final String source = Files.readString(preprocessedMixinSource());

        assertTailInjection(source, "confluxmap$onGameJoin");
        assertTailInjection(source, "confluxmap$onPlayerRespawn");
    }

    private static void assertTailInjection(final String source, final String callback) {
        final Pattern injection = Pattern.compile(
            "@Inject\\(method = \\\"[^\\\"]+\\\", at = @At\\(\\\"TAIL\\\"\\)\\)\\s+"
                + "private void " + Pattern.quote(callback) + "\\b"
        );
        assertTrue(
            injection.matcher(source).find(),
            callback + " must observe identity only after the packet handler's client-thread handoff"
        );
    }

    private static Path preprocessedMixinSource() throws URISyntaxException {
        Path current = Path.of(
            ClientMultiworldService.class.getProtectionDomain().getCodeSource().getLocation().toURI()
        );
        while (current != null && !"build".equals(current.getFileName().toString())) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Could not locate the version build directory");
        }
        final Path preprocessed = current.resolve(
            "preprocessed/main/java/cn/net/rms/confluxmap/mixin/ClientPlayNetworkHandlerMixin.java"
        );
        if (Files.exists(preprocessed)) {
            return preprocessed;
        }

        return current.getParent().getParent().getParent().resolve(
            "src/main/java/cn/net/rms/confluxmap/mixin/ClientPlayNetworkHandlerMixin.java"
        );
    }
}
