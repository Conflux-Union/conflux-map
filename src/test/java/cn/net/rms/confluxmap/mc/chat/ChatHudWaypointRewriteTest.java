package cn.net.rms.confluxmap.mc.chat;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ChatHudWaypointRewriteTest {
    @Test
    void chatHudHidesTheVelocityResponseConsumedByWorldDetection() throws Exception {
        final String source = Files.readString(preprocessedMixinSource());

        assertTrue(
            source.contains("confluxmap$hideVelocityProbeResponse"),
            "the chat queue must intercept the world-detection response before displaying it"
        );
        assertTrue(
            source.contains("ClientWorldIdentityHandler.chatMessage(message)")
                && source.contains("cancellable = true")
                && source.contains("callback.cancel()"),
            "a consumed Velocity response must be removed from the chat queue"
        );
    }

    @Test
    void chatHudRewritesModernPlayerAndSystemMessagesAtTheirSharedQueueBoundary() throws Exception {
        final String source = Files.readString(preprocessedMixinSource());

        //#if MC>=260100
        //$$ assertTrue(
        //$$     source.contains("method = \"addMessage(Lnet/minecraft/network/chat/Component;\"")
        //$$         && source.contains("Lnet/minecraft/network/chat/MessageSignature;")
        //$$         && source.contains("Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;")
        //$$         && source.contains("Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V"),
        //$$     "26.1+ must intercept the shared ChatComponent queue entry"
        //$$ );
        //#elseif MC>=12100
        //$$ assertTrue(
        //$$     source.contains("method = \"addMessage(Lnet/minecraft/text/Text;\"")
        //$$         && source.contains("Lnet/minecraft/network/message/MessageSignatureData;")
        //$$         && source.contains("Lnet/minecraft/client/gui/hud/MessageIndicator;)V"),
        //$$     "1.21+ must intercept the three-argument ChatHud entry used by player chat"
        //$$ );
        //#else
        assertTrue(
            source.contains("addMessage(Lnet/minecraft/text/Text;)V"),
            "legacy chat must intercept ChatHud rather than an action-bar packet path"
        );
        //#endif
        assertTrue(
            source.contains("confluxmap$rewriteWaypointMessage"),
            "the shared chat queue entry must apply the waypoint rewrite"
        );
    }

    private static Path preprocessedMixinSource() throws URISyntaxException {
        Path current = Path.of(
            ChatHudWaypointRewriteTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()
        );
        while (current != null && !"build".equals(current.getFileName().toString())) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Could not locate the version build directory");
        }
        final Path preprocessed = current.resolve(
            "preprocessed/main/java/cn/net/rms/confluxmap/mixin/ChatHudMixin.java"
        );
        if (Files.exists(preprocessed)) {
            return preprocessed;
        }

        // 1.17.1 is the main project and compiles shared sources directly without preprocessing.
        return current.getParent().getParent().getParent().resolve(
            "src/main/java/cn/net/rms/confluxmap/mixin/ChatHudMixin.java"
        );
    }
}
