package cn.net.rms.confluxmap.mc.world;

import java.util.Set;

/** Recognizes only Velocity's localized prefix for its no-argument /server list. */
final class VelocityServerAvailablePrefixMatcher {
    private static final String RESOURCE =
        "/assets/confluxmap/velocity_server_available.properties";

    private final Set<String> prefixes;

    private VelocityServerAvailablePrefixMatcher(final Set<String> prefixes) {
        this.prefixes = prefixes;
    }

    static VelocityServerAvailablePrefixMatcher load() {
        return new VelocityServerAvailablePrefixMatcher(Set.copyOf(
            VelocityLocalizedMessageTemplates.load(RESOURCE)
        ));
    }

    boolean matches(final String text) {
        return text != null && prefixes.contains(text);
    }
}
