package cn.net.rms.confluxmap.server.web;

import cn.net.rms.confluxmap.server.ServerConfig;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Game-channel settings which remain available when the HTTP map is disabled. */
public final class ServerConfigControl {
    private static final Gson JSON = new Gson();
    private static final String[] WEB_SETTINGS = {
        "sharePlayers", "allowPlayerSearch", "allowWaypointSearch", "showWaypointImages",
        "waypointImageMode", "enableChat", "showGameChat", "allowWebChat"
    };

    private ServerConfigControl() {}

    public static boolean handle(
        byte[] payload, ServerConfig config, boolean operator, BooleanSupplier save,
        Runnable settingsChanged, Consumer<byte[]> reply
    ) {
        final JsonObject request;
        final String type;
        try {
            request = JSON.fromJson(new String(payload, StandardCharsets.UTF_8), JsonObject.class);
            type = request.get("type").getAsString();
        } catch (RuntimeException malformed) {
            return false;
        }
        if (!type.equals("admin-status") && !type.equals("admin-set")) return false;
        final boolean allowed = operator && config.enabled && config.webMap.allowOperatorClientConfig;
        if (type.equals("admin-set")) {
            String message = "Forbidden";
            if (allowed) {
                try {
                    message = update(config, request.get("key").getAsString(),
                        request.get("value").getAsString(), save, settingsChanged);
                } catch (IllegalArgumentException | IllegalStateException | NullPointerException invalid) {
                    message = "Invalid setting";
                }
            }
            final JsonObject result = new JsonObject();
            result.addProperty("type", "admin-result");
            result.addProperty("message", message);
            reply.accept(encode(result));
        }
        final JsonObject status = new JsonObject();
        status.addProperty("type", "admin-status");
        status.addProperty("allowed", allowed);
        if (allowed) {
            for (String key : WEB_SETTINGS) status.addProperty(key, value(config.webMap, key));
            status.addProperty("shareChunkLoadState", config.shareChunkLoadState);
        }
        reply.accept(encode(status));
        return true;
    }

    private static String update(ServerConfig config, String key, String next,
                                 BooleanSupplier save, Runnable settingsChanged) {
        if (key.equals("shareChunkLoadState")) {
            if (!next.equals("false") && !next.equals("true")) return "Invalid setting";
            boolean old = config.shareChunkLoadState;
            config.shareChunkLoadState = Boolean.parseBoolean(next);
            if (save.getAsBoolean()) return "Saved. Restart the server to apply.";
            config.shareChunkLoadState = old;
            return "Save failed";
        }
        String previous = value(config.webMap, key);
        if (previous == null) return "Unknown setting";
        set(config.webMap, key, next);
        if (!save.getAsBoolean()) {
            set(config.webMap, key, previous);
            return "Save failed";
        }
        settingsChanged.run();
        return "Saved";
    }

    private static byte[] encode(JsonObject object) {
        return JSON.toJson(object).getBytes(StandardCharsets.UTF_8);
    }

    static String value(WebMapConfig cfg, String key) {
        return switch (key) {
            case "sharePlayers" -> Boolean.toString(cfg.sharePlayers);
            case "allowPlayerSearch" -> Boolean.toString(cfg.allowPlayerSearch);
            case "allowWaypointSearch" -> Boolean.toString(cfg.allowWaypointSearch);
            case "showWaypointImages" -> Boolean.toString(cfg.showWaypointImages);
            case "waypointImageMode" -> cfg.waypointImageMode.name();
            case "enableChat" -> Boolean.toString(cfg.enableChat);
            case "showGameChat" -> Boolean.toString(cfg.showGameChat);
            case "allowWebChat" -> Boolean.toString(cfg.allowWebChat);
            default -> null;
        };
    }

    static void set(WebMapConfig cfg, String key, String value) {
        if (!key.equals("waypointImageMode") && !value.equals("true") && !value.equals("false"))
            throw new IllegalArgumentException();
        boolean b = Boolean.parseBoolean(value);
        switch (key) {
            case "sharePlayers" -> cfg.sharePlayers = b;
            case "allowPlayerSearch" -> cfg.allowPlayerSearch = b;
            case "allowWaypointSearch" -> cfg.allowWaypointSearch = b;
            case "showWaypointImages" -> cfg.showWaypointImages = b;
            case "waypointImageMode" ->
                    cfg.waypointImageMode = WebMapConfig.ImageMode.valueOf(value);
            case "enableChat" -> cfg.enableChat = b;
            case "showGameChat" -> cfg.showGameChat = b;
            case "allowWebChat" -> cfg.allowWebChat = b;
            default -> throw new IllegalArgumentException();
        }
    }

}
