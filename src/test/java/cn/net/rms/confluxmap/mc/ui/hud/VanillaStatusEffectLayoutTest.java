package cn.net.rms.confluxmap.mc.ui.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.config.HudRect;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class VanillaStatusEffectLayoutTest {
    //#if MC>=260200
    //$$ private static final String HUD_CLASS = "net/minecraft/client/gui/Hud";
    //$$ private static final String EFFECT_METHOD = "extractEffects";
    //#elseif MC>=260100
    //$$ private static final String HUD_CLASS = "net/minecraft/client/gui/Gui";
    //$$ private static final String EFFECT_METHOD = "extractEffects";
    //#else
    private static final String HUD_CLASS = "net/minecraft/client/gui/hud/InGameHud";
    private static final String EFFECT_METHOD = "renderStatusEffectOverlay";
    //#endif

    @Test
    void derivesRowsFromTheRightEdgeDownwards() {
        assertEquals(new HudRect(250, 1, 300, 25), VanillaStatusEffectLayout.row(300, 1, 2));
        assertNull(VanillaStatusEffectLayout.row(300, 1, 0));
        assertNull(VanillaStatusEffectLayout.row(0, 1, 2));
    }

    @Test
    void placesTheHarmfulRowOneStepBelowTheBeneficialOne() {
        assertEquals(1, VanillaStatusEffectLayout.beneficialTop(false));
        assertEquals(16, VanillaStatusEffectLayout.beneficialTop(true));
        assertEquals(27, VanillaStatusEffectLayout.harmfulTop(false));
        assertEquals(42, VanillaStatusEffectLayout.harmfulTop(true));
    }

    /**
     * The effect icons expose no size API, so the row geometry is copied from vanilla's own
     * layout. Reading the constants back out of the bytecode turns a Mojang change from a silently
     * misplaced overlay into a build failure on the version that changed.
     */
    @Test
    void mirrorsTheConstantsVanillaStillLaysTheOverlayOutWith() throws IOException {
        final Set<Integer> constants = effectMethodConstants();

        assertTrue(
            constants.contains(VanillaStatusEffectLayout.ICON_SIZE),
            "vanilla no longer draws " + VanillaStatusEffectLayout.ICON_SIZE + "px effect icons"
        );
        assertTrue(
            constants.contains(VanillaStatusEffectLayout.ICON_STEP),
            "vanilla no longer steps effect icons by " + VanillaStatusEffectLayout.ICON_STEP
        );
        assertTrue(
            constants.contains(VanillaStatusEffectLayout.ROW_STEP),
            "vanilla no longer offsets the harmful row by " + VanillaStatusEffectLayout.ROW_STEP
        );
        assertTrue(
            constants.contains(VanillaStatusEffectLayout.DEMO_TOP_OFFSET),
            "vanilla no longer pushes the demo overlay down by "
                + VanillaStatusEffectLayout.DEMO_TOP_OFFSET
        );
    }

    private static Set<Integer> effectMethodConstants() throws IOException {
        final Set<Integer> constants = new LinkedHashSet<>();
        final boolean[] methodFound = {false};
        try (InputStream input = minecraftClass()) {
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(
                    final int access,
                    final String name,
                    final String descriptor,
                    final String signature,
                    final String[] exceptions
                ) {
                    if (!name.equals(EFFECT_METHOD)) {
                        return null;
                    }
                    methodFound[0] = true;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitIntInsn(final int opcode, final int operand) {
                            if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) {
                                constants.add(operand);
                            }
                        }

                        @Override
                        public void visitIincInsn(final int varIndex, final int increment) {
                            constants.add(increment);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertTrue(methodFound[0], "vanilla no longer has a " + EFFECT_METHOD + " method");
        return constants;
    }

    private static InputStream minecraftClass() {
        final InputStream input = VanillaStatusEffectLayoutTest.class.getClassLoader()
            .getResourceAsStream(HUD_CLASS + ".class");
        if (input == null) {
            throw new IllegalStateException("Could not load Minecraft class " + HUD_CLASS);
        }
        return input;
    }
}
