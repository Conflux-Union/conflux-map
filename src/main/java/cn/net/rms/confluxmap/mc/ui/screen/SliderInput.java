package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.Widgets;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/** A slider and raw text field kept in step by one range-checked {@link SliderValue}. */
final class SliderInput<T> {
    private static final int CONTROL_GAP = 4;
    private static final int MIN_INPUT_WIDTH = 40;
    private static final int MAX_INPUT_WIDTH = 56;

    private final SliderValue<T> value;
    private final Consumer<T> setter;
    private final Pattern textPattern;
    private final ValueSlider slider;
    private final TextFieldWidget input;
    private String lastAcceptedText;
    private boolean synchronizingInput;
    private boolean wheelEnabled;

    SliderInput(
        final TextRenderer textRenderer,
        final int x,
        final int y,
        final int width,
        final int height,
        final SliderValue<T> value,
        final Pattern textPattern,
        final int maxLength,
        final Consumer<T> setter,
        final Function<T, Text> message
    ) {
        this.value = value;
        this.setter = setter;
        this.textPattern = textPattern;

        final int inputWidth = inputWidth(width);
        final int sliderWidth = sliderWidth(width);
        this.slider = new ValueSlider(x, y, sliderWidth, height, message);
        this.input = new TextFieldWidget(
            textRenderer,
            x + sliderWidth + CONTROL_GAP,
            y,
            inputWidth,
            height,
            message.apply(this.value.value())
        );
        this.input.setMaxLength(maxLength);
        this.lastAcceptedText = this.value.text();
        Widgets.setText(this.input, this.lastAcceptedText);
        Widgets.setChangedListener(this.input, this::onInputChanged);
    }

    static int inputWidth(final int width) {
        final int preferredInputWidth = Math.min(
            MAX_INPUT_WIDTH,
            Math.max(MIN_INPUT_WIDTH, width / 4)
        );
        return Math.min(preferredInputWidth, Math.max(1, width - CONTROL_GAP - 1));
    }

    static int sliderWidth(final int width) {
        return Math.max(1, width - CONTROL_GAP - inputWidth(width));
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

    void enableWheel() { wheelEnabled = true; }

    boolean scrollAt(double x, double y, double amount) {
        if (!wheelEnabled || amount == 0 || !slider.active
            || !(slider.isMouseOver(x, y) || input.isMouseOver(x, y))) return false;
        int steps = (int) Math.min(Integer.MAX_VALUE, Math.max(1, Math.round(Math.abs(amount))));
        if (value.step(amount > 0 ? steps : -steps)) {
            setter.accept(value.value());
            slider.syncFromValue();
        }
        replaceInputText(value.text());
        return true;
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
        if (!textPattern.matcher(text).matches()) {
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

    private final class ValueSlider extends RevealedSlider {
        private final Function<T, Text> message;

        private ValueSlider(
            final int x,
            final int y,
            final int width,
            final int height,
            final Function<T, Text> message
        ) {
            super(x, y, width, height, SliderInput.this.value.position());
            this.message = message;
            updateMessage();
        }

        private void syncFromValue() {
            this.value = SliderInput.this.value.position();
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            if (message != null) {
                setMessage(message.apply(SliderInput.this.value.value()));
            }
        }

        @Override
        protected void applyValue() {
            SliderInput.this.value.updateFromPosition(value);
            setter.accept(SliderInput.this.value.value());
            replaceInputText(SliderInput.this.value.text());
        }
    }
}
