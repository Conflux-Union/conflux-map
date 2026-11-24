package cn.net.rms.confluxmap.mc.net;

import cn.net.rms.confluxmap.compat.Ids;
import cn.net.rms.confluxmap.compat.PlayNetworking;

import com.google.gson.*;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class WebMapClient {
    private static final Identifier CHANNEL = Ids.of("confluxmap:webmap_v1");
    private static final Gson JSON = new Gson();
    private static final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private static ClientPlayNetworkHandler connection;
    private static JsonObject state = new JsonObject();
    private static String result = "";
    private static int ticks;

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
                                        case "status" -> state = o;
                                        case "result" -> {
                                            result = o.get("message").getAsString();
                                            queue.clear();
                                        }
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
            result = "";
            ticks = 0;
        }
        if (connection == null || client.player == null) return;
        if (!queue.isEmpty()) {
            try {
                PlayNetworking.sendClient(CHANNEL, queue.removeFirst());
            } catch (RuntimeException e) {
                queue.clear();
                result = "Upload interrupted";
            }
            return;
        }
        if (ticks++ % 40 == 0) send(packet("status"));
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

    public static void set(String key, String value) {
        if (!queue.isEmpty()) return;
        JsonObject o = packet("set");
        o.addProperty("key", key);
        o.addProperty("value", value);
        send(o);
    }

    public static void upload(UUID waypoint, String file) {
        if (!flag("upload") || !queue.isEmpty()) {
            result = "Upload unavailable";
            return;
        }
        try {
            Path path = Path.of(file);
            long n = Files.size(path);
            if (n < 1 || n > 2 * 1024 * 1024) throw new IllegalArgumentException();
            byte[] bytes;
            try (var in = Files.newInputStream(path)) {
                bytes = in.readNBytes(2 * 1024 * 1024 + 1);
            }
            if (bytes.length != n) throw new IllegalArgumentException();
            JsonObject start = packet("image-start");
            start.addProperty("waypoint", waypoint.toString());
            start.addProperty("length", bytes.length);
            queue.add(encode(start));
            for (int i = 0; i < bytes.length; i += 8192) {
                JsonObject chunk = packet("image-chunk");
                chunk.addProperty(
                        "data",
                        Base64.getEncoder()
                                .encodeToString(
                                        Arrays.copyOfRange(
                                                bytes, i, Math.min(i + 8192, bytes.length))));
                queue.add(encode(chunk));
            }
            queue.add(encode(packet("image-finish")));
            result = "Waiting for server";
        } catch (Exception e) {
            queue.clear();
            result = "Select a readable PNG/JPEG under 2 MiB";
        }
    }
}
