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
        "waypointImageMode", "enableChat", "showGameChat", "allowWebChat", "defaultMode"
    };

    private static final String[] SERVER_SETTINGS = {
        "shareChunkLoadState", "allowBiomeMap", "allowStructureSearch", "allowEntityRadar",
        "allowNonOperatorSharedWaypointManagement", "maxTilesPerRequest", "minRequestIntervalMs",
        "maxSharedWaypointsPerPlayer"
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
            for (String key : SERVER_SETTINGS) status.addProperty(key, value(config, key));
            status.addProperty("maxSharedWaypointsPerWorld", config.maxSharedWaypointsPerWorld);
        }
        reply.accept(encode(status));
        return true;
    }

    private static String update(ServerConfig config, String key, String next,
                                 BooleanSupplier save, Runnable settingsChanged) {
        final String previous = value(config, key);
        if (previous == null) return "Unknown setting";
        if (previous.equals(next)) return "Saved";
        set(config, key, next);
        if (!save.getAsBoolean()) {
            set(config, key, previous);
            return "Save failed";
        }
        settingsChanged.run();
        return "Saved";
    }

    private static String value(final ServerConfig config, final String key) {
        return switch (key) {
            case "shareChunkLoadState" -> Boolean.toString(config.shareChunkLoadState);
            case "allowBiomeMap" -> Boolean.toString(config.allowBiomeMap);
            case "allowStructureSearch" -> Boolean.toString(config.allowStructureSearch);
            case "allowEntityRadar" -> Boolean.toString(config.allowEntityRadar);
            case "allowNonOperatorSharedWaypointManagement" ->
                Boolean.toString(config.allowNonOperatorSharedWaypointManagement);
            case "maxTilesPerRequest" -> Integer.toString(config.maxTilesPerRequest);
            case "minRequestIntervalMs" -> Integer.toString(config.minRequestIntervalMs);
            case "maxSharedWaypointsPerPlayer" -> Integer.toString(config.maxSharedWaypointsPerPlayer);
            default -> value(config.webMap, key);
        };
    }

    private static void set(final ServerConfig config, final String key, final String value) {
        switch (key) {
            case "shareChunkLoadState" -> config.shareChunkLoadState = booleanValue(value);
            case "allowBiomeMap" -> config.allowBiomeMap = booleanValue(value);
            case "allowStructureSearch" -> config.allowStructureSearch = booleanValue(value);
            case "allowEntityRadar" -> config.allowEntityRadar = booleanValue(value);
            case "allowNonOperatorSharedWaypointManagement" ->
                config.allowNonOperatorSharedWaypointManagement = booleanValue(value);
            case "maxTilesPerRequest" -> config.maxTilesPerRequest = boundedInt(value, 1, 255);
            case "minRequestIntervalMs" -> config.minRequestIntervalMs = boundedInt(value, 0, 60_000);
            case "maxSharedWaypointsPerPlayer" -> config.maxSharedWaypointsPerPlayer =
                boundedInt(value, 1, config.maxSharedWaypointsPerWorld);
            default -> set(config.webMap, key, value);
        }
    }

    private static int boundedInt(final String value, final int min, final int max) {
        final int number = Integer.parseInt(value);
        if (number < min || number > max) throw new IllegalArgumentException();
        return number;
    }

    private static boolean booleanValue(final String value) {
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException();
        return Boolean.parseBoolean(value);
    }

    private static byte[] encode(JsonObject object) {
        return JSON.toJson(object).getBytes(StandardCharsets.UTF_8);
    }

    static String value(WebMapConfig cfg, String key) {
        return switch (key) {
            case "defaultMode" -> cfg.defaultMode.name();
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
        if (key.equals("defaultMode")) {
            cfg.defaultMode = WebMapConfig.DefaultMode.valueOf(value);
            return;
        }
        if (key.equals("waypointImageMode")) {
            cfg.waypointImageMode = WebMapConfig.ImageMode.valueOf(value);
            return;
        }
        final boolean b = booleanValue(value);
        switch (key) {
            case "sharePlayers" -> cfg.sharePlayers = b;
            case "allowPlayerSearch" -> cfg.allowPlayerSearch = b;
            case "allowWaypointSearch" -> cfg.allowWaypointSearch = b;
            case "showWaypointImages" -> cfg.showWaypointImages = b;
            case "enableChat" -> cfg.enableChat = b;
            case "showGameChat" -> cfg.showGameChat = b;
            case "allowWebChat" -> cfg.allowWebChat = b;
            default -> throw new IllegalArgumentException();
        }
    }

}
