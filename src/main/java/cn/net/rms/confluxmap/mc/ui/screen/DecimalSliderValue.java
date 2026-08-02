package cn.net.rms.confluxmap.mc.ui.screen;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Minecraft-free decimal mapping shared by a slider and numeric input. */
final class DecimalSliderValue {
    static final double CONTINUOUS = 0.0;

    private final double min;
    private final double max;
    private final double step;
    private double value;

    DecimalSliderValue(final double min, final double max, final double step, final double initialValue) {
        if (!Double.isFinite(min) || !Double.isFinite(max) || !Double.isFinite(step)
            || min > max || step < CONTINUOUS) {
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
        if (span == 0.0) {
            return 0.0;
        }
        if (min <= 0.0) {
            return (value - min) / span;
        }
        // Zoom multipliers form a ratio: reserve more slider travel for the common low range.
        return Math.log(value / min) / Math.log(max / min);
    }

    double updateFromPosition(final double position) {
        final double bounded = Double.isNaN(position) ? 0.0 : Math.max(0.0, Math.min(1.0, position));
        final double candidate = min <= 0.0
            ? min + bounded * (max - min)
            : min * Math.pow(max / min, bounded);
        value = clampAndRound(candidate);
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
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString();
    }

    private double clampAndRound(final double candidate) {
        if (!Double.isFinite(candidate)) {
            return min;
        }
        final double clamped = Math.max(min, Math.min(max, candidate));
        if (step == CONTINUOUS) {
            return clamped;
        }
        return Math.max(min, Math.min(max, min + Math.round((clamped - min) / step) * step));
    }
}
