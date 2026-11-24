package cn.net.rms.confluxmap.mc.net;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.core.waypoint.library.WaypointMediaService;
import cn.net.rms.confluxmap.compat.Ids;
import cn.net.rms.confluxmap.compat.PlayNetworking;

import com.google.gson.*;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;
import java.util.*;

public final class WebMapClient {
    private static final Identifier CHANNEL = Ids.of("confluxmap:webmap_v1");
    private static final Gson JSON = new Gson();
    private static final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private static ClientPlayNetworkHandler connection;
    private static JsonObject state = new JsonObject();
    private static String result = "";
    private static String configResult = "";
    private static final ArrayDeque<PhotoUpload> photos = new ArrayDeque<>();
    private static final Map<UUID, PendingPublication> publications = new HashMap<>();
    private static PhotoUpload activePhoto;
    private static int responseTicks;
    private static JsonObject admin = new JsonObject();
    private static JsonObject review = new JsonObject();
    private static int configRevision;
    private static int ticks;

    private record PhotoUpload(UUID waypoint, WaypointMediaService.Key album, UUID photo) {}
    private record PendingPublication(WaypointMediaService.Key album, List<UUID> photos, long deadline) {}

    private WebMapClient() {}

    public static void register() {
        PlayNetworking.registerClient(
                CHANNEL,
                (client, handler, payload) -> {
                    if (payload.length > 16000) return;
                    client.execute(
                            () -> {
                                if (client.getNetworkHandler() != handler) return;
                                try {
                                    JsonObject o =
                                            JSON.fromJson(
                                                    new String(payload, StandardCharsets.UTF_8),
                                                    JsonObject.class);
                                    switch (o.get("type").getAsString()) {
                                        case "status" -> {
                                            if (flag("review") != (o.has("review") && o.get("review").getAsBoolean())) {
                                                ++configRevision;
                                            }
                                            state = o;
                                        }
                                        case "result" -> {
                                            result = o.get("message").getAsString();
                                            queue.clear();
                                            activePhoto = null;
                                        }
                                        case "admin-status" -> {
                                            if (!admin.equals(o)) ++configRevision;
                                            admin = o;
                                        }
                                        case "admin-result" -> configResult = o.get("message").getAsString();
                                        case "image-review" -> review = o;
                                        case "image-target" -> publicationTarget(o);
                                        default -> {}
                                    }
                                } catch (RuntimeException ignored) {
                                }
                            });
                });
        ClientTickEvents.END_CLIENT_TICK.register(WebMapClient::tick);
    }

    private static void tick(MinecraftClient client) {
        if (connection != client.getNetworkHandler()) {
            connection = client.getNetworkHandler();
            state = new JsonObject();
            queue.clear();
            photos.clear();
            publications.clear();
            activePhoto = null;
            ++configRevision;
            admin = new JsonObject();
            review = new JsonObject();
            configResult = "";
            result = "";
            ticks = 0;
        }
        if (connection == null || client.player == null) return;
        if (!queue.isEmpty()) {
            try {
                PlayNetworking.sendClient(CHANNEL, queue.removeFirst());
            } catch (RuntimeException e) {
                queue.clear();
                activePhoto = null;
                result = "Upload interrupted";
            }
            return;
        }
        if (activePhoto != null && ++responseTicks >= 600) {
            activePhoto = null;
            result = "Upload timed out";
        }
        if (activePhoto == null && !photos.isEmpty()) {
            prepareNext(client);
            return;
        }
        if (ticks++ % 40 == 0) {
            publications.values().removeIf(pending -> pending.deadline() < System.nanoTime());
            send(packet("status"));
            send(packet("admin-status"));
        }
    }

    private static JsonObject packet(String type) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        return o;
    }

    private static byte[] encode(JsonObject o) {
        return JSON.toJson(o).getBytes(StandardCharsets.UTF_8);
    }

    private static void send(JsonObject o) {
        try {
            PlayNetworking.sendClient(CHANNEL, encode(o));
        } catch (RuntimeException ignored) {
        }
    }

    public static boolean flag(String key) {
        return state.has(key) && state.get(key).getAsBoolean();
    }

    public static String value(String key) {
        return state.has(key) ? state.get(key).getAsString() : "";
    }

    public static String result() {
        return queue.isEmpty() ? result : "Uploading (" + queue.size() + " packets left)";
    }

    public static boolean uploadBusy() {
        return activePhoto != null || !photos.isEmpty();
    }

    public static String configResult() {
        return configResult;
    }

    public static int configRevision() {
        return configRevision;
    }

    public static boolean adminAllowed() {
        return admin.has("allowed") && admin.get("allowed").getAsBoolean();
    }

    public static String setting(String key) {
        return admin.has(key) ? admin.get(key).getAsString() : value(key);
    }

    public static void setServerSetting(String key, String value) {
        JsonObject request = packet("admin-set");
        request.addProperty("key", key);
        request.addProperty("value", value);
        send(request);
    }

    public static JsonObject review() {
        return review;
    }

    public static void requestReview(int index) {
        JsonObject request = packet("image-review");
        request.addProperty("index", index);
        send(request);
    }

    public static void moderate(UUID id, boolean approve) {
        if (activePhoto != null) return;
        JsonObject request = packet(approve ? "image-approve" : "image-reject");
        request.addProperty("id", id.toString());
        send(request);
    }

    public static void preparePublication(UUID operation, WaypointMediaService.Key album, List<UUID> selected) {
        if (!selected.isEmpty() && flag("albumUpload")) {
            publications.put(operation, new PendingPublication(album, List.copyOf(selected),
                System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60)));
        }
    }

    public static void cancelPublication(UUID operation) {
        publications.remove(operation);
    }

    private static void publicationTarget(JsonObject message) {
        PendingPublication pending = publications.remove(UUID.fromString(message.get("operation").getAsString()));
        if (pending == null || pending.deadline() < System.nanoTime()) return;
        UUID waypoint = UUID.fromString(message.get("waypoint").getAsString());
        upload(waypoint, pending.album(), pending.photos());
    }

    public static void upload(UUID waypoint, WaypointMediaService.Key album, List<UUID> selected) {
        if (!flag("albumUpload") || selected.isEmpty() || photos.size() + selected.size() > 4) {
            result = "Upload unavailable";
            return;
        }
        for (UUID id : selected) photos.add(new PhotoUpload(waypoint, album, id));
        result = "Preparing photo";
    }

    private static void prepareNext(MinecraftClient client) {
        final PhotoUpload request = photos.removeFirst();
        final ClientPlayNetworkHandler target = connection;
        activePhoto = request;
        responseTicks = 0;
        ConfluxMapClient.get().waypointMediaService().webCopy(request.album(), request.photo())
            .whenComplete((bytes, error) -> client.execute(() -> {
                if (connection != target || client.getNetworkHandler() != target || activePhoto != request) return;
                if (error != null) {
                    activePhoto = null;
                    result = "Could not prepare the saved photo";
                    return;
                }
                JsonObject start = packet("image-start");
                start.addProperty("waypoint", request.waypoint().toString());
                start.addProperty("length", bytes.length);
                queue.add(encode(start));
                for (int offset = 0; offset < bytes.length; offset += 8192) {
                    JsonObject chunk = packet("image-chunk");
                    chunk.addProperty("data", Base64.getEncoder().encodeToString(
                        Arrays.copyOfRange(bytes, offset, Math.min(offset + 8192, bytes.length))
                    ));
                    queue.add(encode(chunk));
                }
                queue.add(encode(packet("image-finish")));
                result = "Waiting for server";
            }));
    }
}
