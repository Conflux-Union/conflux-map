package cn.net.rms.confluxmap.mc.input;

import java.util.Objects;
import java.util.function.IntFunction;

final class MaliLibShortcutKey {
    private static final int UNBOUND_KEY_CODE = -1;

    private MaliLibShortcutKey() {
    }

    static String storageKey(final int keyCode, final IntFunction<String> keyNameResolver) {
        if (keyCode == UNBOUND_KEY_CODE) {
            return "";
        }
        return Objects.requireNonNull(keyNameResolver.apply(keyCode), "MaliLib key name");
    }
}
