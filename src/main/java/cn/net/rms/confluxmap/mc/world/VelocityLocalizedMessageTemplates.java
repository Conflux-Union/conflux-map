package cn.net.rms.confluxmap.mc.world;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

/** Loads the small, pinned subset of Velocity translations bundled for message recognition. */
final class VelocityLocalizedMessageTemplates {
    private VelocityLocalizedMessageTemplates() {
    }

    static List<String> load(final String resource) {
        final InputStream stream = VelocityLocalizedMessageTemplates.class.getResourceAsStream(
            resource
        );
        if (stream == null) {
            throw new IllegalStateException("Missing Velocity message templates: " + resource);
        }
        final Properties properties = new Properties();
        try (stream; InputStreamReader reader = new InputStreamReader(
            stream, StandardCharsets.UTF_8
        )) {
            properties.load(reader);
        } catch (final IOException exception) {
            throw new IllegalStateException(
                "Could not load Velocity message templates: " + resource,
                exception
            );
        }
        final List<String> templates = properties.stringPropertyNames().stream()
            .sorted()
            .map(properties::getProperty)
            .toList();
        if (templates.isEmpty()) {
            throw new IllegalStateException("No Velocity message templates in: " + resource);
        }
        return templates;
    }
}
