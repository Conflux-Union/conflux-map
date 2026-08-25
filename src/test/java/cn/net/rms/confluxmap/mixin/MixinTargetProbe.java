package cn.net.rms.confluxmap.mixin;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Reads a vanilla class back out of the compiled Minecraft jar to confirm an injector resolves.
 *
 * <p>Mixin only reports an unmatched selector when the game loads, and this repository builds a
 * dozen Minecraft versions from one source tree, so a descriptor that drifted on one of them would
 * otherwise ship as a crash on that version alone.
 */
final class MixinTargetProbe {
    private MixinTargetProbe() {
    }

    record Result(boolean methodFound, boolean methodStatic, boolean invocationFound) {
    }

    /**
     * @param internalClassName the vanilla class to read, in internal form
     * @param methodSelector the injector's target method, with or without a descriptor
     * @param invocationSelector the {@code @At} target of an INVOKE injection point
     */
    static Result probe(
        final String internalClassName,
        final String methodSelector,
        final String invocationSelector
    ) throws IOException {
        final MemberSelector targetMethod = MemberSelector.parseMethod(methodSelector);
        final MemberSelector invocation = MemberSelector.parseInvocation(invocationSelector);
        final AtomicBoolean methodFound = new AtomicBoolean();
        final AtomicBoolean methodStatic = new AtomicBoolean();
        final AtomicBoolean invocationFound = new AtomicBoolean();

        try (InputStream input = minecraftClass(internalClassName)) {
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
                    methodStatic.set((access & Opcodes.ACC_STATIC) != 0);
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(
                            final int opcode,
                            final String owner,
                            final String invokedName,
                            final String invokedDescriptor,
                            final boolean isInterface
                        ) {
                            if (invocation.matches(owner, invokedName, invokedDescriptor)) {
                                invocationFound.set(true);
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return new Result(methodFound.get(), methodStatic.get(), invocationFound.get());
    }

    private static InputStream minecraftClass(final String internalName) {
        final InputStream input = MixinTargetProbe.class.getClassLoader()
            .getResourceAsStream(internalName + ".class");
        if (input == null) {
            throw new IllegalStateException("Could not load Minecraft class " + internalName);
        }
        return input;
    }

    /** A mixin member selector, where a missing owner or descriptor matches anything. */
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
            return descriptorStart < 0
                ? new MemberSelector(null, selector, null)
                : new MemberSelector(
                    null,
                    selector.substring(0, descriptorStart),
                    selector.substring(descriptorStart)
                );
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

        private boolean matches(
            final String candidateOwner,
            final String candidateName,
            final String candidateDescriptor
        ) {
            return (owner == null || owner.equals(candidateOwner))
                && name.equals(candidateName)
                && (descriptor == null || descriptor.equals(candidateDescriptor));
        }
    }
}
