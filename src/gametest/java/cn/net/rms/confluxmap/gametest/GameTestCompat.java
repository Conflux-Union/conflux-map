package cn.net.rms.confluxmap.gametest;

import cn.net.rms.confluxmap.compat.Texts;
import net.minecraft.test.TestContext;

/** Small seam for GameTest assertion signatures that changed in 1.21.5. */
public final class GameTestCompat {
    private GameTestCompat() {
    }

    public static void fail(final TestContext context, final String message) {
        //#if MC>=12105
        //$$ context.throwGameTestException(Texts.literal(message));
        //#else
        context.throwGameTestException(message);
        //#endif
    }
}
