package cn.net.rms.confluxmap.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.server.ServerChunkDirtyHandler;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.block.BlockState;
import net.minecraft.world.chunk.WorldChunk;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

final class WorldChunkMixinTargetTest {
    private static final String CALLBACK = "confluxmap$afterBlockStateChanged";

    @Test
    void successfulBlockWritesMarkTheServerChunkDirty() throws IOException {
        final Method callback = Arrays.stream(WorldChunkMixin.class.getDeclaredMethods())
            .filter(method -> method.getName().equals(CALLBACK))
            .findFirst()
            .orElseThrow();
        final Inject inject = callback.getAnnotation(Inject.class);
        assertNotNull(inject);
        assertEquals("setBlockState", inject.method()[0]);
        assertEquals("RETURN", inject.at()[0].value());

        final Class<?>[] callbackParameters = callback.getParameterTypes();
        final String targetDescriptor = Type.getMethodDescriptor(
            Type.getType(BlockState.class),
            Arrays.stream(callbackParameters, 0, callbackParameters.length - 1)
                .map(Type::getType)
                .toArray(Type[]::new)
        );
        assertEquals(CallbackInfoReturnable.class, callbackParameters[callbackParameters.length - 1]);
        assertTrue(Arrays.stream(WorldChunk.class.getDeclaredMethods()).anyMatch(method ->
            method.getName().equals("setBlockState")
                && Type.getMethodDescriptor(method).equals(targetDescriptor)
        ));

        final AtomicBoolean marksDirty = new AtomicBoolean();
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
                    if (!name.equals(CALLBACK)) {
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
                            if (owner.equals(Type.getInternalName(ServerChunkDirtyHandler.class))
                                && invokedName.equals("chunkDirty")
                                && invokedDescriptor.equals(Type.getMethodDescriptor(
                                    Type.VOID_TYPE, Type.getType(WorldChunk.class)
                                ))) {
                                marksDirty.set(true);
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertTrue(marksDirty.get());
    }

    @Test
    void packagedMixinConfigurationRegistersEveryConditionalMixin() throws IOException {
        try (InputStream input = WorldChunkMixinTargetTest.class.getClassLoader()
            .getResourceAsStream("confluxmap.mixins.json")) {
            assertNotNull(input, "packaged mixin configuration");
            final String configuration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(configuration.contains("\"AgeableMobEntityRendererAccessor\""), configuration);
            assertTrue(configuration.contains("\"GameRendererAccessor\""), configuration);
            assertNotNull(
                WorldChunkMixinTargetTest.class.getClassLoader()
                    .getResource("cn/net/rms/confluxmap/mixin/GameRendererAccessor.class"),
                "registered GameRendererAccessor class"
            );
            assertTrue(configuration.contains("\"VanillaLayeredBiomeSourceAccessor\""), configuration);
            assertTrue(configuration.contains("\"WorldRendererMixin\""), configuration);
            assertTrue(configuration.contains("\"WorldChunkMixin\""), configuration);
        }
    }

    private static InputStream mixinClass() {
        final InputStream input = WorldChunkMixinTargetTest.class.getClassLoader()
            .getResourceAsStream(Type.getInternalName(WorldChunkMixin.class) + ".class");
        if (input == null) {
            throw new IllegalStateException("Could not load WorldChunkMixin bytecode");
        }
        return input;
    }
}
