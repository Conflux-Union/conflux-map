package cn.net.rms.confluxmap.server.web;

import cn.net.rms.confluxmap.core.net.Proto;

/** Operator-controlled HTTP boundary for the browser map. */
public final class WebMapConfig {
    public boolean enabled = true;
    public String bindAddress = "127.0.0.1";
    public int port = 8123;
    public boolean allowInsecureRemote = false;
    public volatile boolean sharePlayers = false;
    public boolean sharedGateway = true;
    transient String gatewayToken;
    public volatile boolean enableChat = false;
    public volatile boolean showGameChat = true;
    public volatile boolean allowWebChat = false;
    public volatile boolean showWaypointImages = true;
    public volatile boolean allowOperatorClientConfig = false;
    public enum ImageMode { APPROVAL, OP_ONLY, EVERYONE, DISABLED }
    public volatile ImageMode waypointImageMode = ImageMode.APPROVAL;
    public volatile boolean allowPlayerSearch = false;
    public volatile boolean allowWaypointSearch = true;
    public String serverDisplayName = "";
    public enum DefaultMode { AUTO, ALL, GENERATED }
    public DefaultMode defaultMode = DefaultMode.AUTO;
    public String title = "Conflux Map";
    public String favicon = "";
    public int maxConnections = 20;
    public int maxBytesPerSecondPerAddress = Proto.DEFAULT_MAX_BYTES_PER_SEC;
    public int minRequestIntervalMs = Proto.DEFAULT_MIN_REQ_INTERVAL_MS;

    public void normalize() {
        if (bindAddress == null || bindAddress.isBlank()) {
            bindAddress = "127.0.0.1";
        } else {
            bindAddress = bindAddress.trim();
        }
        title = title == null || title.isBlank() ? "Conflux Map" : title.trim();
        favicon = favicon == null ? "" : favicon.trim();
        serverDisplayName = serverDisplayName == null ? "" : serverDisplayName.strip();
        if (serverDisplayName.codePointCount(0, serverDisplayName.length()) > 80) {
            serverDisplayName = serverDisplayName.substring(0, serverDisplayName.offsetByCodePoints(0, 80));
        }
        if (defaultMode == null) defaultMode = DefaultMode.AUTO;
        if (waypointImageMode == null) waypointImageMode = ImageMode.APPROVAL;
        port = clamp(port, 0, 65_535);
        maxConnections = clamp(maxConnections, 1, 256);
        maxBytesPerSecondPerAddress = clamp(maxBytesPerSecondPerAddress, 1024, 1 << 20);
        minRequestIntervalMs = clamp(minRequestIntervalMs, 0, 60_000);
    }

    public boolean loopbackOnly() {
        return "127.0.0.1".equals(bindAddress)
            || "::1".equals(bindAddress)
            || "localhost".equalsIgnoreCase(bindAddress);
    }

    public static WebMapConfig loopbackEphemeral() {
        final WebMapConfig config = new WebMapConfig();
        config.enabled = true;
        config.port = 0;
        return config;
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(max, value));
    }
}
