package cn.net.rms.confluxmap.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.mc.snapshot.ChunkCaptureHandler;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

final class ClientPlayNetworkHandlerLightMixinTest {
    private static final String CALLBACK = "confluxmap$afterLightDataApplied";

    @Test
    void lightCaptureHookTargetsAppliedLightAndMarksTheChunkDirty() throws IOException {
        final Method callback = Arrays.stream(ClientPlayNetworkHandlerMixin.class.getDeclaredMethods())
            .filter(method -> method.getName().equals(CALLBACK))
            .findFirst()
            .orElseThrow();
        final Inject inject = callback.getAnnotation(Inject.class);
        assertNotNull(inject, "the light capture callback must remain an injection");
        assertEquals(1, inject.method().length, "the light capture callback must have one exact target");
        assertEquals(1, inject.at().length, "the light capture callback must have one injection point");
        assertEquals("TAIL", inject.at()[0].value(), "capture must run after vanilla applies light data");

        final String targetName = methodName(inject.method()[0]);
        final String targetDescriptor = targetDescriptor(callback);
        assertTrue(
            Arrays.stream(ClientPlayNetworkHandler.class.getDeclaredMethods()).anyMatch(method ->
                method.getName().equals(targetName) && Type.getMethodDescriptor(method).equals(targetDescriptor)
            ),
            "the light capture callback must match an actual vanilla method"
        );

        final AtomicBoolean marksChunkDirty = new AtomicBoolean();
        try (InputStream input = mixinClass()) {
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(
                    final int access,
                    final String name,
                    final String descriptor,
                    final String signature,
                    final String[] exceptions
                ) {
                    if (!name.equals(CALLBACK) || !descriptor.equals(Type.getMethodDescriptor(callback))) {
                        return null;
                    }
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(
                            final int opcode,
                            final String owner,
                            final String invokedName,
                            final String invokedDescriptor,
                            final boolean isInterface
                        ) {
                            if (owner.equals(Type.getInternalName(ChunkCaptureHandler.class))
                                && invokedName.equals("chunkDirty")
                                && invokedDescriptor.equals("(II)V")) {
                                marksChunkDirty.set(true);
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertTrue(marksChunkDirty.get(), "applied light must schedule a fresh authoritative snapshot");
    }

    private static String methodName(final String selector) {
        final int descriptorStart = selector.indexOf('(');
        return descriptorStart < 0 ? selector : selector.substring(0, descriptorStart);
    }

    private static String targetDescriptor(final Method callback) {
        final Class<?>[] callbackParameters = callback.getParameterTypes();
        assertTrue(
            callbackParameters.length > 0
                && callbackParameters[callbackParameters.length - 1].equals(CallbackInfo.class),
            "the injection callback must end with CallbackInfo"
        );
        return Type.getMethodDescriptor(
            Type.VOID_TYPE,
            Arrays.stream(callbackParameters, 0, callbackParameters.length - 1)
                .map(Type::getType)
                .toArray(Type[]::new)
        );
    }

    private static InputStream mixinClass() {
        final InputStream input = ClientPlayNetworkHandlerLightMixinTest.class.getClassLoader()
            .getResourceAsStream(Type.getInternalName(ClientPlayNetworkHandlerMixin.class) + ".class");
        if (input == null) {
            throw new IllegalStateException("Could not load ClientPlayNetworkHandlerMixin bytecode");
        }
        return input;
    }
}
