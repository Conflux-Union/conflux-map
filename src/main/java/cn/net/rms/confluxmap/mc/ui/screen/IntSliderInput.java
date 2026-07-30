package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.Widgets;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.regex.Pattern;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/** A slider and raw integer field backed by one range-checked value. */
final class IntSliderInput {
    private static final Pattern INTEGER_TEXT = Pattern.compile("[+-]?\\d*");
    private static final int CONTROL_GAP = 4;
    private static final int MIN_INPUT_WIDTH = 40;
    private static final int MAX_INPUT_WIDTH = 56;
    private static final int MAX_INTEGER_LENGTH = 11;

    private final IntSliderValue value;
    private final IntConsumer setter;
    private final ValueSlider slider;
    private final TextFieldWidget input;
    private String lastAcceptedText;
    private boolean synchronizingInput;

    IntSliderInput(
        final TextRenderer textRenderer,
        final int x,
        final int y,
        final int width,
        final int height,
        final int min,
        final int max,
        final int initialValue,
        final IntConsumer setter,
        final IntFunction<Text> message
    ) {
        this.value = new IntSliderValue(min, max, initialValue);
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
        this.input.setMaxLength(MAX_INTEGER_LENGTH);
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
        // Older TextFieldWidget mouse/key handlers do not consult ClickableWidget.active.
        input.setEditable(active);
    }

    void tick() {
        Widgets.tick(input);
        if (!input.isFocused() && !Widgets.text(input).equals(value.text())) {
            replaceInputText(value.text());
        }
    }

    private void onInputChanged(final String text) {
        if (synchronizingInput) {
            return;
        }
        if (!INTEGER_TEXT.matcher(text).matches()) {
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
        private final IntFunction<Text> message;

        private ValueSlider(
            final int x,
            final int y,
            final int width,
            final int height,
            final IntFunction<Text> message
        ) {
            super(x, y, width, height, Text.of(""), IntSliderInput.this.value.position());
            this.message = message;
            updateMessage();
        }

        private void syncFromValue() {
            this.value = IntSliderInput.this.value.position();
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            if (message != null) {
                setMessage(message.apply(IntSliderInput.this.value.value()));
            }
        }

        @Override
        protected void applyValue() {
            IntSliderInput.this.value.updateFromPosition(value);
            setter.accept(IntSliderInput.this.value.value());
            replaceInputText(IntSliderInput.this.value.text());
        }
    }
}
