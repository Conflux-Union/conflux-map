package cn.net.rms.confluxmap.server.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.imageio.ImageIO;

final class WebMapFeaturesTest {
    @TempDir Path directory;

    @Test
    void revokedOperatorsCannotWriteAndFailedSavesRollBack() {
        final WebMapConfig config = new WebMapConfig();
        config.allowOperatorClientConfig = true;
        final AtomicBoolean canSave = new AtomicBoolean(true);
        final AtomicBoolean operator = new AtomicBoolean(true);
        final UUID player = UUID.randomUUID();
        final List<String> replies = new ArrayList<>();

        try (WebMapFeatures features =
                new WebMapFeatures(
                        config,
                        directory,
                        id -> true,
                        canSave::get,
                        Runnable::run,
                        message -> {})) {
            final String enable = "{\"type\":\"set\",\"key\":\"sharePlayers\",\"value\":\"true\"}";
            final String disable = enable.replace("true", "false");
            send(features, player, operator, enable, replies);
            assertTrue(config.sharePlayers);
            operator.set(false);
            send(features, player, operator, disable, replies);
            assertEquals("Forbidden", replies.get(replies.size() - 1));
            assertTrue(config.sharePlayers);
            operator.set(true);
            canSave.set(false);
            send(features, player, operator, disable, replies);
            assertTrue(replies.contains("Save failed"));
            assertTrue(config.sharePlayers);
        }
    }

    @Test
    void imageOnlyBecomesPublicAfterApprovalAndSurvivesRestart() throws Exception {
        final WebMapConfig config = new WebMapConfig();
        final UUID waypoint = UUID.randomUUID();
        final UUID player = UUID.randomUUID();
        final AtomicBoolean operator = new AtomicBoolean(false);
        final List<String> replies = new ArrayList<>();
        final LinkedBlockingQueue<Runnable> gameTasks = new LinkedBlockingQueue<>();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(
                ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", bytes));
        final String imageId;
        final byte[] published;

        try (WebMapFeatures features =
                new WebMapFeatures(
                        config,
                        directory,
                        waypoint::equals,
                        () -> true,
                        gameTasks::add,
                        message -> {})) {
            send(
                    features,
                    player,
                    operator,
                    "{\"type\":\"image-start\",\"waypoint\":\""
                            + waypoint
                            + "\",\"length\":"
                            + bytes.size()
                            + "}",
                    replies);
            send(
                    features,
                    player,
                    operator,
                    "{\"type\":\"image-chunk\",\"data\":\""
                            + Base64.getEncoder().encodeToString(bytes.toByteArray())
                            + "\"}",
                    replies);
            send(features, player, operator, "{\"type\":\"image-finish\"}", replies);
            final Runnable finish = gameTasks.poll(5, TimeUnit.SECONDS);
            assertNotNull(finish);
            finish.run();
            assertEquals("Awaiting operator approval", replies.get(replies.size() - 1));
            imageId = features.pending().get(0).split(" ", 2)[0];
            assertNull(features.image(UUID.fromString(imageId)));
            assertFalse(features.imagesJson().contains(imageId));

            assertTrue(features.moderate(UUID.fromString(imageId), "image-approve"));
            assertTrue(features.imagesJson().contains(imageId));
            published = features.image(UUID.fromString(imageId));
            assertNotNull(published);
        }
        try (WebMapFeatures features =
                new WebMapFeatures(
                        config,
                        directory,
                        waypoint::equals,
                        () -> true,
                        Runnable::run,
                        message -> {})) {
            assertArrayEquals(published, features.image(UUID.fromString(imageId)));
            config.showWaypointImages = false;
            assertNull(features.image(UUID.fromString(imageId)));
        }
    }

    @Test
    void chatDirectionsAreIndependentAndWebMessagesAreSystemMessages() {
        final WebMapConfig config = new WebMapConfig();
        final List<String> broadcasts = new ArrayList<>();
        try (WebMapFeatures features =
                new WebMapFeatures(
                        config,
                        directory,
                        id -> true,
                        () -> true,
                        Runnable::run,
                        broadcasts::add)) {
            assertEquals("Chat disabled", features.webChat("127.0.0.1", "hello"));
            config.enableChat = true;
            features.gameChat(UUID.randomUUID(), "Alex", "from the game", null);
            assertTrue(features.chatJson().contains("from the game"));
            assertEquals("Chat disabled", features.webChat("127.0.0.1", "hello"));
            config.showGameChat = false;
            config.allowWebChat = true;
            assertFalse(features.chatJson().contains("from the game"));
            assertEquals("", features.webChat("127.0.0.1", "hello"));
            assertEquals(List.of("网页地图：hello"), broadcasts);
            assertEquals("Rate limited", features.webChat("127.0.0.1", "again"));
        }
    }

    private static void send(
            WebMapFeatures features,
            UUID player,
            AtomicBoolean operator,
            String packet,
            List<String> replies) {
        features.handle(
                player,
                "Alex",
                operator::get,
                () -> true,
                packet.getBytes(StandardCharsets.UTF_8),
                bytes -> {
                    final JsonObject message =
                            JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                                    .getAsJsonObject();
                    if (message.has("message")) {
                        replies.add(message.get("message").getAsString());
                    }
                },
                message -> {});
    }
}
