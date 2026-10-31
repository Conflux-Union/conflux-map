package cn.net.rms.confluxmap.core.xaeroimport;

import java.util.Locale;
import java.util.Map;

/**
 * A decoded block-state reference: the block's registry id plus its string
 * properties. Cross-version imports can reference states that do not exist in
 * the running registry; consumers must resolve defensively.
 */
public record XaeroBlockState(String id, Map<String, String> properties) {
    public XaeroBlockState {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    public static XaeroBlockState of(final String id) {
        return new XaeroBlockState(id, Map.of());
    }

    /** Human-readable {@code id[key=value,...]} form for logs and diagnostics. */
    public String textual() {
        if (properties.isEmpty()) {
            return id;
        }
        final StringBuilder sb = new StringBuilder(id).append('[');
        properties.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> sb.append(e.getKey()).append('=').append(e.getValue()).append(','));
        sb.setCharAt(sb.length() - 1, ']');
        return sb.toString();
    }

    @Override
    public String toString() {
        return textual().toLowerCase(Locale.ROOT);
    }
}
