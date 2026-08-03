package cn.net.rms.confluxmap.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
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

        final MemberSelector targetMethod = MemberSelector.parseMethod(redirect.method()[0]);
        final MemberSelector fillInvocation = MemberSelector.parseInvocation(redirect.at().target());
        final AtomicBoolean methodFound = new AtomicBoolean();
        final AtomicBoolean targetMethodStatic = new AtomicBoolean();
        final AtomicBoolean invocationFound = new AtomicBoolean();

        try (InputStream input = minecraftClass(HUD_CLASS)) {
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(
                    final int access,
                    final String name,
                    final String descriptor,
                    final String signature,
                    final String[] exceptions
                ) {
                    if (!targetMethod.matches(null, name, descriptor)) {
                        return null;
                    }
                    methodFound.set(true);
                    targetMethodStatic.set((access & Opcodes.ACC_STATIC) != 0);
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(
                            final int opcode,
                            final String owner,
                            final String invokedName,
                            final String invokedDescriptor,
                            final boolean isInterface
                        ) {
                            if (fillInvocation.matches(owner, invokedName, invokedDescriptor)) {
                                invocationFound.set(true);
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }

        assertTrue(methodFound.get(), "the redirect's scoreboard target method must exist");
        assertEquals(
            targetMethodStatic.get(),
            Modifier.isStatic(redirector.getModifiers()),
            "the redirect handler and its target method must have matching static modifiers"
        );
        assertTrue(
            invocationFound.get(),
            "the redirect's fill selector must match an invocation in the vanilla scoreboard method"
        );
    }

    private static InputStream minecraftClass(final String internalName) {
        final InputStream input = InGameHudMixinTargetTest.class.getClassLoader()
            .getResourceAsStream(internalName + ".class");
        if (input == null) {
            throw new IllegalStateException("Could not load Minecraft class " + internalName);
        }
        return input;
    }

    private static final class MemberSelector {
        private final String owner;
        private final String name;
        private final String descriptor;

        private MemberSelector(final String owner, final String name, final String descriptor) {
            this.owner = owner;
            this.name = name;
            this.descriptor = descriptor;
        }

        private static MemberSelector parseMethod(final String selector) {
            final int descriptorStart = selector.indexOf('(');
            return new MemberSelector(null, selector.substring(0, descriptorStart), selector.substring(descriptorStart));
        }

        private static MemberSelector parseInvocation(final String selector) {
            final int ownerEnd = selector.indexOf(';');
            final int descriptorStart = selector.indexOf('(', ownerEnd);
            return new MemberSelector(
                selector.substring(1, ownerEnd),
                selector.substring(ownerEnd + 1, descriptorStart),
                selector.substring(descriptorStart)
            );
        }

        private boolean matches(final String candidateOwner, final String candidateName, final String candidateDescriptor) {
            return (owner == null || owner.equals(candidateOwner))
                && name.equals(candidateName)
                && descriptor.equals(candidateDescriptor);
        }
    }
}
