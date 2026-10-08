package cn.net.rms.confluxmap.compat;

//#if MC>=260300
//$$ import com.mojang.blaze3d.platform.InputConstants;
//#endif

/**
 * Keyboard key and modifier codes compared against raw screen key events.
 *
 * <p>Through 26.2 these are GLFW key symbols. 26.3 replaced GLFW with SDL and moved the codes
 * onto {@code InputConstants} with SDL values and a few renamed spellings (ENTER becomes
 * RETURN, KP_ENTER becomes NUMPADENTER, RIGHT_BRACKET becomes RBRACKET); "unknown" stays -1.
 * The SDL codes are not compile-time constants, so switch-case labels over these values are
 * not allowed - use {@code ==} comparisons instead.
 */
public final class Keys {
    //#if MC>=260300
    //$$ public static final int B = InputConstants.KEY_B;
    //$$ public static final int COMMA = InputConstants.KEY_COMMA;
    //$$ public static final int DIGIT_1 = InputConstants.KEY_1;
    //$$ public static final int DIGIT_2 = InputConstants.KEY_2;
    //$$ public static final int DIGIT_3 = InputConstants.KEY_3;
    //$$ public static final int DIGIT_4 = InputConstants.KEY_4;
    //$$ public static final int DIGIT_5 = InputConstants.KEY_5;
    //$$ public static final int DOWN = InputConstants.KEY_DOWN;
    //$$ public static final int ENTER = InputConstants.KEY_RETURN;
    //$$ public static final int ESCAPE = InputConstants.KEY_ESCAPE;
    //$$ public static final int F3 = InputConstants.KEY_F3;
    //$$ public static final int F8 = InputConstants.KEY_F8;
    //$$ public static final int F9 = InputConstants.KEY_F9;
    //$$ public static final int H = InputConstants.KEY_H;
    //$$ public static final int J = InputConstants.KEY_J;
    //$$ public static final int KP_ENTER = InputConstants.KEY_NUMPADENTER;
    //$$ public static final int LEFT = InputConstants.KEY_LEFT;
    //$$ public static final int M = InputConstants.KEY_M;
    //$$ public static final int P = InputConstants.KEY_P;
    //$$ public static final int RIGHT = InputConstants.KEY_RIGHT;
    //$$ public static final int RIGHT_BRACKET = InputConstants.KEY_RBRACKET;
    //$$ public static final int U = InputConstants.KEY_U;
    //$$ public static final int UP = InputConstants.KEY_UP;
    //$$ public static final int Y = InputConstants.KEY_Y;
    //$$ public static final int Z = InputConstants.KEY_Z;
    //$$ public static final int UNKNOWN = -1;
    //$$ public static final int MOD_CONTROL = InputConstants.MOD_CONTROL;
    //$$ public static final int MOD_SHIFT = InputConstants.MOD_SHIFT;
    //$$ public static final int MOD_SUPER = InputConstants.MOD_SUPER;
    //#else
    public static final int B = org.lwjgl.glfw.GLFW.GLFW_KEY_B;
    public static final int COMMA = org.lwjgl.glfw.GLFW.GLFW_KEY_COMMA;
    public static final int DIGIT_1 = org.lwjgl.glfw.GLFW.GLFW_KEY_1;
    public static final int DIGIT_2 = org.lwjgl.glfw.GLFW.GLFW_KEY_2;
    public static final int DIGIT_3 = org.lwjgl.glfw.GLFW.GLFW_KEY_3;
    public static final int DIGIT_4 = org.lwjgl.glfw.GLFW.GLFW_KEY_4;
    public static final int DIGIT_5 = org.lwjgl.glfw.GLFW.GLFW_KEY_5;
    public static final int DOWN = org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN;
    public static final int ENTER = org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
    public static final int ESCAPE = org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
    public static final int F3 = org.lwjgl.glfw.GLFW.GLFW_KEY_F3;
    public static final int F8 = org.lwjgl.glfw.GLFW.GLFW_KEY_F8;
    public static final int F9 = org.lwjgl.glfw.GLFW.GLFW_KEY_F9;
    public static final int H = org.lwjgl.glfw.GLFW.GLFW_KEY_H;
    public static final int J = org.lwjgl.glfw.GLFW.GLFW_KEY_J;
    public static final int KP_ENTER = org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER;
    public static final int LEFT = org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT;
    public static final int M = org.lwjgl.glfw.GLFW.GLFW_KEY_M;
    public static final int P = org.lwjgl.glfw.GLFW.GLFW_KEY_P;
    public static final int RIGHT = org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT;
    public static final int RIGHT_BRACKET = org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_BRACKET;
    public static final int U = org.lwjgl.glfw.GLFW.GLFW_KEY_U;
    public static final int UP = org.lwjgl.glfw.GLFW.GLFW_KEY_UP;
    public static final int Y = org.lwjgl.glfw.GLFW.GLFW_KEY_Y;
    public static final int Z = org.lwjgl.glfw.GLFW.GLFW_KEY_Z;
    public static final int UNKNOWN = org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN;
    public static final int MOD_CONTROL = org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL;
    public static final int MOD_SHIFT = org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT;
    public static final int MOD_SUPER = org.lwjgl.glfw.GLFW.GLFW_MOD_SUPER;
    //#endif

    private Keys() {
    }
}
