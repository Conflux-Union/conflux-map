package cn.net.rms.confluxmap.mc.ui.screen;

import java.math.BigDecimal;

/** Minecraft-free stepped decimal mapping shared by a slider and numeric input. */
final class DecimalSliderValue {
    private final double min;
    private final double max;
    private final double step;
    private double value;

    DecimalSliderValue(final double min, final double max, final double step, final double initialValue) {
        if (!Double.isFinite(min) || !Double.isFinite(max) || !Double.isFinite(step)
            || min > max || step <= 0.0) {
            throw new IllegalArgumentException("min, max, and step must be finite and ordered");
        }
        this.min = min;
        this.max = max;
        this.step = step;
        this.value = clampAndRound(initialValue);
    }

    double value() {
        return value;
    }

    double position() {
        final double span = max - min;
        return span == 0.0 ? 0.0 : (value - min) / span;
    }

    double updateFromPosition(final double position) {
        final double bounded = Double.isNaN(position) ? 0.0 : Math.max(0.0, Math.min(1.0, position));
        value = clampAndRound(min + bounded * (max - min));
        return value;
    }

    boolean updateFromText(final String text) {
        if (text == null || text.isEmpty() || "+".equals(text) || "-".equals(text)
            || ".".equals(text) || "+.".equals(text) || "-.".equals(text)) {
            return false;
        }
        try {
            value = clampAndRound(Double.parseDouble(text));
            return true;
        } catch (final NumberFormatException ignored) {
            return false;
        }
    }

    String text() {
        return format(value);
    }

    static String format(final double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private double clampAndRound(final double candidate) {
        if (!Double.isFinite(candidate)) {
            return min;
        }
        final double rounded = min + Math.round((candidate - min) / step) * step;
        return Math.max(min, Math.min(max, rounded));
    }
}
