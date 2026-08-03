package cn.net.rms.confluxmap.mc.world;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/** Matches Velocity's localized current-server notice without interpreting it as identity. */
final class VelocityServerCurrentMessageMatcher {
    private static final String RESOURCE = "/assets/confluxmap/velocity_server_current.properties";
    private static final String SERVER_PLACEHOLDER = "<arg:0>";
    private static final int MAX_SERVER_NAME_LENGTH = 256;

    private final List<Pattern> patterns;

    private VelocityServerCurrentMessageMatcher(final List<Pattern> patterns) {
        this.patterns = patterns;
    }

    static VelocityServerCurrentMessageMatcher load() {
        final List<Pattern> patterns = new LinkedHashSet<>(
            VelocityLocalizedMessageTemplates.load(RESOURCE)
        ).stream()
            .map(VelocityServerCurrentMessageMatcher::compile)
            .toList();
        return new VelocityServerCurrentMessageMatcher(patterns);
    }

    boolean matches(final String message) {
        return message != null
            && patterns.stream().anyMatch(pattern -> pattern.matcher(message).matches());
    }

    private static Pattern compile(final String template) {
        final int placeholder = template.indexOf(SERVER_PLACEHOLDER);
        if (placeholder < 0 || placeholder != template.lastIndexOf(SERVER_PLACEHOLDER)) {
            throw new IllegalArgumentException("Invalid Velocity current-server template");
        }
        final String prefix = template.substring(0, placeholder);
        final String suffix = template.substring(placeholder + SERVER_PLACEHOLDER.length());
        return Pattern.compile(
            "\\A" + Pattern.quote(prefix)
                + "([^\\p{Cc}]{1," + MAX_SERVER_NAME_LENGTH + "})"
                + Pattern.quote(suffix) + "\\z"
        );
    }
}
