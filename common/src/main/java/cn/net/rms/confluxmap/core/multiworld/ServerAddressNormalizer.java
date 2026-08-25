package cn.net.rms.confluxmap.core.multiworld;

import java.util.Locale;

/**
 * Collapses the spellings of a server address that are certainly the same endpoint: surrounding
 * whitespace, letter case, and a trailing dot on the host, which DNS and the {@code SRV} lookup
 * both ignore.
 *
 * <p>The port is never touched, not even an explicit {@code :25565}. An address without a port
 * makes the client resolve {@code _minecraft._tcp.<host>} first, so {@code mc.example.com} and
 * {@code mc.example.com:25565} reach different machines whenever an SRV record exists — and that
 * is how most hosting providers publish a server. Merging two histories is unrecoverable while
 * keeping them apart only costs a duplicate cache, so anything short of certainty stays split.
 *
 * <p>Endpoints that really are one server merge through {@link ServerAliasResolver} instead, on
 * companion evidence or an explicit user link rather than on how the address is spelled.
 */
public final class ServerAddressNormalizer {
    private ServerAddressNormalizer() {
    }

    /**
     * Returns the canonical spelling of {@code rawAddress}. The result is still a raw address
     * rather than a storage id; callers sanitize it through
     * {@link cn.net.rms.confluxmap.core.model.WorldIdentity#serverId(String)}.
     */
    public static String normalize(final String rawAddress) {
        if (rawAddress == null) {
            return "";
        }
        final String trimmed = rawAddress.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            return "";
        }
        final int portSeparator = portSeparator(trimmed);
        final String host = portSeparator < 0 ? trimmed : trimmed.substring(0, portSeparator);
        final String canonicalHost = stripTrailingDots(host);
        if (canonicalHost.isEmpty() || canonicalHost.equals(host)) {
            return trimmed;
        }
        return portSeparator < 0 ? canonicalHost : canonicalHost + trimmed.substring(portSeparator);
    }

    /**
     * Index of the {@code :} that separates the port, or {@code -1} when there is none. A bare
     * IPv6 literal such as {@code ::1} carries several colons and no port; a bracketed literal
     * only has a port when a colon follows the closing bracket.
     */
    private static int portSeparator(final String address) {
        final int bracket = address.lastIndexOf(']');
        if (bracket >= 0) {
            final int colon = address.indexOf(':', bracket + 1);
            return colon < 0 ? -1 : colon;
        }
        final int first = address.indexOf(':');
        return first >= 0 && first == address.lastIndexOf(':') ? first : -1;
    }

    private static String stripTrailingDots(final String host) {
        int end = host.length();
        while (end > 0 && host.charAt(end - 1) == '.') {
            end--;
        }
        return host.substring(0, end);
    }
}
