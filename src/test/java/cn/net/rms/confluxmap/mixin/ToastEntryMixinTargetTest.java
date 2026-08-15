package cn.net.rms.confluxmap.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The toast measurement hooks a per-toast draw call whose descriptor changed four times across the
 * supported versions, so the selector is checked against the vanilla bytecode of whichever version
 * is being built.
 */
final class ToastEntryMixinTargetTest {
    //#if MC>=260100
    //$$ private static final String ENTRY_CLASS =
    //$$     "net/minecraft/client/gui/components/toasts/ToastManager$ToastInstance";
    //#else
    private static final String ENTRY_CLASS = "net/minecraft/client/toast/ToastManager$Entry";
    //#endif

    @Test
    void toastRedirectMatchesTheVanillaPerToastDrawInvocation() throws IOException {
        final Method redirector = Arrays.stream(ToastEntryMixin.class.getDeclaredMethods())
            .filter(method -> method.getName().equals("confluxmap$measureToast"))
            .findFirst()
            .orElseThrow();
        final Redirect redirect = redirector.getAnnotation(Redirect.class);
        assertNotNull(redirect, "toast measurement must remain a redirect");

        final MixinTargetProbe.Result result = MixinTargetProbe.probe(
            ENTRY_CLASS, redirect.method()[0], redirect.at().target()
        );

        assertTrue(result.methodFound(), "the redirect's toast target method must exist");
        assertEquals(
            result.methodStatic(),
            Modifier.isStatic(redirector.getModifiers()),
            "the redirect handler and its target method must have matching static modifiers"
        );
        assertTrue(
            result.invocationFound(),
            "the redirect's selector must match the per-toast draw call vanilla makes"
        );
    }
}
