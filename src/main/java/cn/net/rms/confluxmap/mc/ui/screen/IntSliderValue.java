package cn.net.rms.confluxmap.mc.ui.screen;

/** Minecraft-free value mapping shared by an integer slider and its numeric input. */
final class IntSliderValue {
    private final int min;
    private final int max;
    private int value;

    IntSliderValue(final int min, final int max, final int initialValue) {
        if (min > max) {
            throw new IllegalArgumentException("min must not exceed max");
        }
        this.min = min;
        this.max = max;
        this.value = clamp(initialValue);
    }

    int value() {
        return value;
    }

    double position() {
        final long span = (long) max - min;
        return span == 0L ? 0.0 : (value - (long) min) / (double) span;
    }

    int updateFromPosition(final double position) {
        final double bounded = Double.isNaN(position) ? 0.0 : Math.max(0.0, Math.min(1.0, position));
        value = (int) (min + Math.round(bounded * ((long) max - min)));
        return value;
    }

    boolean updateFromText(final String text) {
        if (text == null || text.isEmpty() || "+".equals(text) || "-".equals(text)) {
            return false;
        }
        try {
            value = clamp(Long.parseLong(text));
            return true;
        } catch (final NumberFormatException ignored) {
            return false;
        }
    }

    String text() {
        return Integer.toString(value);
    }

    private int clamp(final long candidate) {
        return (int) Math.max(min, Math.min(max, candidate));
    }
}
