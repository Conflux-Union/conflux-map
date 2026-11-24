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
            final List<JsonObject> repliesFromReview = new ArrayList<>();
            byte[] request = "{\"type\":\"image-review\",\"index\":0}".getBytes(StandardCharsets.UTF_8);
            features.handle(player, "Alex", () -> false, () -> true, request,
                data -> repliesFromReview.add(JsonParser.parseString(new String(data, StandardCharsets.UTF_8))
                    .getAsJsonObject()), message -> {});
            assertFalse(repliesFromReview.get(0).has("preview"));
            features.handle(player, "Alex", () -> true, () -> true, request,
                data -> repliesFromReview.add(JsonParser.parseString(new String(data, StandardCharsets.UTF_8))
                    .getAsJsonObject()), message -> {});
            JsonObject preview = repliesFromReview.get(1);
            assertEquals(imageId, preview.get("id").getAsString());
            assertEquals(waypoint.toString(), preview.get("waypoint").getAsString());
            byte[] jpeg = Base64.getDecoder().decode(preview.get("preview").getAsString());
            assertEquals(2, ImageIO.read(new java.io.ByteArrayInputStream(jpeg)).getWidth());

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

    @Test
    void imageStartRequiresAnOwnedWaypoint() {
        final UUID owner = UUID.randomUUID();
        final UUID waypoint = UUID.randomUUID();
        final List<String> replies = new ArrayList<>();
        try (WebMapFeatures features = new WebMapFeatures(new WebMapConfig(), directory, waypoint::equals,
            () -> true, Runnable::run, message -> {})) {
            features.restrictAttachments((actor, w) -> actor.equals(owner) && w.equals(waypoint));
            String start = "{\"type\":\"image-start\",\"waypoint\":\"" + waypoint + "\",\"length\":8}";
            send(features, UUID.randomUUID(), new AtomicBoolean(false), start, replies);
            assertEquals(List.of("Upload refused"), replies);
            replies.clear();
            send(features, owner, new AtomicBoolean(false), start, replies);
            assertTrue(replies.isEmpty());
        }
    }

    @Test
    void configurationWorksWithoutAWebBackendAndLeavesStateUntouchedWhenDenied() {
        final var config = new cn.net.rms.confluxmap.server.ServerConfig();
        config.webMap.enabled = false;
        config.webMap.allowOperatorClientConfig = true;
        final byte[] change = ("{\"type\":\"admin-set\",\"key\":\"shareChunkLoadState\",\"value\":\"true\"}")
            .getBytes(StandardCharsets.UTF_8);
        final List<String> replies = new ArrayList<>();
        final java.util.function.Consumer<byte[]> capture =
            data -> replies.add(new String(data, StandardCharsets.UTF_8));
        assertTrue(ServerConfigControl.handle(change, config, false, () -> true, () -> {}, capture));
        assertFalse(config.shareChunkLoadState);
        assertTrue(replies.get(0).contains("Forbidden"));
        replies.clear();
        assertTrue(ServerConfigControl.handle(change, config, true, () -> false, () -> {}, capture));
        assertFalse(config.shareChunkLoadState);
        assertTrue(replies.get(0).contains("Save failed"));
        replies.clear();
        assertTrue(ServerConfigControl.handle(change, config, true, () -> true, () -> {}, capture));
        assertTrue(config.shareChunkLoadState);
        assertTrue(replies.get(0).contains("Saved"));
    }

    @Test
    void numericSettingsValidateRangesAndOnlyApplyAfterSaving() {
        final var config = new cn.net.rms.confluxmap.server.ServerConfig();
        config.webMap.allowOperatorClientConfig = true;
        config.maxSharedWaypointsPerWorld = 100;
        final int old = config.maxTilesPerRequest;
        final var applied = new java.util.concurrent.atomic.AtomicInteger();
        final List<String> replies = new ArrayList<>();
        final java.util.function.Consumer<byte[]> reply =
            data -> replies.add(new String(data, StandardCharsets.UTF_8));

        final byte[] change = """
            {"type":"admin-set","key":"maxTilesPerRequest","value":"17"}
            """.getBytes(StandardCharsets.UTF_8);
        assertTrue(ServerConfigControl.handle(change, config, true, () -> false, applied::incrementAndGet, reply));
        assertEquals(old, config.maxTilesPerRequest);
        assertEquals(0, applied.get());
        replies.clear();
        assertTrue(ServerConfigControl.handle(change, config, true, () -> true, applied::incrementAndGet, reply));
        assertEquals(17, config.maxTilesPerRequest);
        assertEquals(1, applied.get());
        assertTrue(replies.get(1).contains("\"maxTilesPerRequest\":\"17\""));
        final byte[] invalid = """
            {"type":"admin-set","key":"maxSharedWaypointsPerPlayer","value":"101"}
            """.getBytes(StandardCharsets.UTF_8);
        replies.clear();
        assertTrue(ServerConfigControl.handle(invalid, config, true, () -> true, applied::incrementAndGet, reply));
        assertTrue(replies.get(0).contains("Invalid setting"));
        assertEquals(1, applied.get());
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
