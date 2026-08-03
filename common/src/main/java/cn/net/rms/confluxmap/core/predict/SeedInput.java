package cn.net.rms.confluxmap.core.predict;

import java.util.OptionalLong;

/** Parses the same numeric-or-text seed form accepted by Minecraft's world creation screen. */
public final class SeedInput {
    private SeedInput() {
    }

    /** Empty input is unset; non-numeric input uses Java's stable {@link String#hashCode()} seed. */
    public static OptionalLong parse(final String input) {
        if (input == null || input.trim().isEmpty()) {
            return OptionalLong.empty();
        }
        final String normalized = input.trim();
        try {
            return OptionalLong.of(Long.parseLong(normalized));
        } catch (final NumberFormatException ignored) {
            return OptionalLong.of(normalized.hashCode());
        }
    }
}
