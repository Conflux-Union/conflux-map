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

final class InGameHudMixinTargetTest {
    //#if MC>=260200
    //$$ private static final String HUD_CLASS = "net/minecraft/client/gui/Hud";
    //#elseif MC>=260100
    //$$ private static final String HUD_CLASS = "net/minecraft/client/gui/Gui";
    //#else
    private static final String HUD_CLASS = "net/minecraft/client/gui/hud/InGameHud";
    //#endif

    @Test
    void scoreboardRedirectMatchesTheVanillaFillInvocation() throws IOException {
        final Method redirector = Arrays.stream(InGameHudMixin.class.getDeclaredMethods())
            .filter(method -> method.getName().equals("confluxmap$captureScoreboardFill"))
            .findFirst()
            .orElseThrow();
        final Redirect redirect = redirector.getAnnotation(Redirect.class);
        assertNotNull(redirect, "scoreboard bounds capture must remain a redirect");

        final MixinTargetProbe.Result result = MixinTargetProbe.probe(
            HUD_CLASS, redirect.method()[0], redirect.at().target()
        );

        assertTrue(result.methodFound(), "the redirect's scoreboard target method must exist");
        assertEquals(
            result.methodStatic(),
            Modifier.isStatic(redirector.getModifiers()),
            "the redirect handler and its target method must have matching static modifiers"
        );
        assertTrue(
            result.invocationFound(),
            "the redirect's fill selector must match an invocation in the vanilla scoreboard method"
        );
    }
}
