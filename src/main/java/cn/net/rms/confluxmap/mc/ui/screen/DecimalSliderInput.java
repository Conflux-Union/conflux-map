package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.Widgets;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.regex.Pattern;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/** A quarter-step decimal slider and numeric field backed by one range-checked map scale. */
final class DecimalSliderInput {
    private static final Pattern DECIMAL_TEXT = Pattern.compile("[+-]?(?:\\d+)?(?:\\.\\d*)?");
    private static final int CONTROL_GAP = 4;
    private static final int MIN_INPUT_WIDTH = 40;
    private static final int MAX_INPUT_WIDTH = 56;
    private static final int MAX_DECIMAL_LENGTH = 8;

    private final DecimalSliderValue value;
    private final DoubleConsumer setter;
    private final ValueSlider slider;
    private final TextFieldWidget input;
    private String lastAcceptedText;
    private boolean synchronizingInput;

    DecimalSliderInput(
        final TextRenderer textRenderer,
        final int x,
        final int y,
        final int width,
        final int height,
        final double min,
        final double max,
        final double initialValue,
        final DoubleConsumer setter,
        final DoubleFunction<Text> message
    ) {
        this.value = new DecimalSliderValue(min, max, initialValue);
        this.setter = setter;

        final int preferredInputWidth = Math.min(
            MAX_INPUT_WIDTH,
            Math.max(MIN_INPUT_WIDTH, width / 4)
        );
        final int inputWidth = Math.min(preferredInputWidth, Math.max(1, width - CONTROL_GAP - 1));
        final int sliderWidth = Math.max(1, width - CONTROL_GAP - inputWidth);
        this.slider = new ValueSlider(x, y, sliderWidth, height, message);
        this.input = new TextFieldWidget(
            textRenderer,
            x + sliderWidth + CONTROL_GAP,
            y,
            inputWidth,
            height,
            message.apply(this.value.value())
        );
        this.input.setMaxLength(MAX_DECIMAL_LENGTH);
        this.lastAcceptedText = this.value.text();
        Widgets.setText(this.input, this.lastAcceptedText);
        Widgets.setChangedListener(this.input, this::onInputChanged);
    }

    ClickableWidget slider() {
        return slider;
    }

    TextFieldWidget input() {
        return input;
    }

    void setActive(final boolean active) {
        slider.active = active;
        input.active = active;
        input.setEditable(active);
    }

    void tick() {
        Widgets.tick(input);
        if (!input.isFocused() && !Widgets.text(input).equals(value.text())) {
            replaceInputText(value.text());
        }
    }

    static String format(final double value) {
        return DecimalSliderValue.format(value);
    }

    private void onInputChanged(final String text) {
        if (synchronizingInput) {
            return;
        }
        if (!DECIMAL_TEXT.matcher(text).matches()) {
            replaceInputText(lastAcceptedText);
            return;
        }
        lastAcceptedText = text;
        if (value.updateFromText(text)) {
            setter.accept(value.value());
            slider.syncFromValue();
        }
    }

    private void replaceInputText(final String text) {
        lastAcceptedText = text;
        synchronizingInput = true;
        try {
            Widgets.setText(input, text);
        } finally {
            synchronizingInput = false;
        }
    }

    private final class ValueSlider extends SliderWidget {
        private final DoubleFunction<Text> message;

        private ValueSlider(
            final int x,
            final int y,
            final int width,
            final int height,
            final DoubleFunction<Text> message
        ) {
            super(x, y, width, height, Text.of(""), DecimalSliderInput.this.value.position());
            this.message = message;
            updateMessage();
        }

        private void syncFromValue() {
            this.value = DecimalSliderInput.this.value.position();
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            if (message != null) {
                setMessage(message.apply(DecimalSliderInput.this.value.value()));
            }
        }

        @Override
        protected void applyValue() {
            DecimalSliderInput.this.value.updateFromPosition(value);
            setter.accept(DecimalSliderInput.this.value.value());
            replaceInputText(DecimalSliderInput.this.value.text());
        }
    }
}
