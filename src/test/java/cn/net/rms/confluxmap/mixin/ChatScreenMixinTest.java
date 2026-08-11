package cn.net.rms.confluxmap.mixin;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ChatScreenMixinTest {
    @Test
    void legacyChatSubmissionTargetsTheScreenMethodThatDeclaresIt() throws IOException {
        final String source = Files.readString(findProjectRoot().resolve(
            "src/main/java/cn/net/rms/confluxmap/mixin/ChatScreenMixin.java"
        )).replace("\r\n", "\n");

        assertTrue(source.contains(
            "//#elseif MC>=11800\n//$$ import net.minecraft.client.gui.screen.ChatScreen;\n//#else\n"
                + "import net.minecraft.client.gui.screen.Screen;"
        ));
        assertTrue(source.contains(
            "//#if MC>=11800\n//$$ @Mixin(ChatScreen.class)\n//#else\n@Mixin(Screen.class)"
        ));
        assertTrue(source.contains("@Inject(method = \"sendMessage(Ljava/lang/String;Z)V\", at = @At(\"HEAD\"))"));
    }

    private static Path findProjectRoot() {
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
