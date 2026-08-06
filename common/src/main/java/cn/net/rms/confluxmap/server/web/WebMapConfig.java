package cn.net.rms.confluxmap.server.web;

import cn.net.rms.confluxmap.core.net.Proto;

/** Operator-controlled HTTP boundary for the optional public web map. */
public final class WebMapConfig {
    public boolean enabled = false;
    public String bindAddress = "127.0.0.1";
    public int port = 8123;
    public boolean allowInsecureRemote = false;
    public boolean sharePlayers = false;
    public int maxConnections = 20;
    public int maxBytesPerSecondPerAddress = Proto.DEFAULT_MAX_BYTES_PER_SEC;
    public int minRequestIntervalMs = Proto.DEFAULT_MIN_REQ_INTERVAL_MS;

    public void normalize() {
        if (bindAddress == null || bindAddress.isBlank()) {
            bindAddress = "127.0.0.1";
        } else {
            bindAddress = bindAddress.trim();
        }
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
