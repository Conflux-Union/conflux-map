package cn.net.rms.confluxmap.mc.ui.screen;

/** One range-checked value shared by a {@link SliderInput} slider and its numeric field. */
interface SliderValue<T> {
    T value();

    double position();

    T updateFromPosition(double position);

    boolean updateFromText(String text);

    String text();
}
