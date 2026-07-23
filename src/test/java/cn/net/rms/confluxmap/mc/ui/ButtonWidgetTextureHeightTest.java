package cn.net.rms.confluxmap.mc.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

final class ButtonWidgetTextureHeightTest {
    private static final int VANILLA_BUTTON_TEXTURE_HEIGHT = 20;

    @Test
    void directVanillaButtonsUseTheFullTextureHeight() throws IOException {
        final Path projectRoot = findProjectRoot();
        final Path uiSourceRoot = projectRoot.resolve(
            "src/main/java/cn/net/rms/confluxmap/mc/ui"
        );
        final List<Path> sourceFiles;
        try (Stream<Path> paths = Files.walk(uiSourceRoot)) {
            sourceFiles = paths
                .filter(path -> path.getFileName().toString().endsWith(".java"))
                .sorted()
                .toList();
        }

        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertTrue(compiler != null, "A JDK is required to inspect UI source files");
        final List<String> violations = new ArrayList<>();
        final int[] buttonCount = {0};
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            final JavacTask task = (JavacTask) compiler.getTask(
                null,
                fileManager,
                null,
                List.of("-proc:none"),
                null,
                fileManager.getJavaFileObjectsFromPaths(sourceFiles)
            );
            final Iterable<? extends CompilationUnitTree> units = task.parse();
            final SourcePositions positions = Trees.instance(task).getSourcePositions();
            for (final CompilationUnitTree unit : units) {
                inspectUnit(projectRoot, unit, positions, buttonCount, violations);
            }
        }

        assertTrue(buttonCount[0] > 0, "No direct ButtonWidget construction was found");
        assertTrue(
            violations.isEmpty(),
            () -> "Vanilla ButtonWidget uses a fixed 20px state texture; use height 20 or a custom "
                + "widget that overrides background rendering:\n" + String.join("\n", violations)
        );
    }

    private static void inspectUnit(
        final Path projectRoot,
        final CompilationUnitTree unit,
        final SourcePositions positions,
        final int[] buttonCount,
        final List<String> violations
    ) {
        final Map<String, ExpressionTree> constants = new HashMap<>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitVariable(final VariableTree node, final Void unused) {
                if (node.getType() != null
                    && "int".equals(node.getType().toString())
                    && node.getInitializer() != null
                    && node.getModifiers().getFlags().containsAll(
                        Set.of(Modifier.STATIC, Modifier.FINAL)
                    )) {
                    constants.put(node.getName().toString(), node.getInitializer());
                }
                return super.visitVariable(node, unused);
            }
        }.scan(unit, null);

        new TreeScanner<Void, Void>() {
            @Override
            public Void visitNewClass(final NewClassTree node, final Void unused) {
                final String type = node.getIdentifier().toString();
                if (!type.equals("ButtonWidget") && !type.endsWith(".ButtonWidget")) {
                    return super.visitNewClass(node, unused);
                }
                buttonCount[0]++;
                if (node.getArguments().size() < 4) {
                    addViolation(node, "constructor has fewer than four arguments");
                    return super.visitNewClass(node, unused);
                }
                final ExpressionTree heightExpression = node.getArguments().get(3);
                final OptionalInt height = evaluate(heightExpression, constants, new HashSet<>());
                if (height.isEmpty()) {
                    addViolation(
                        node,
                        "cannot resolve height expression `" + heightExpression + "`"
                    );
                } else if (height.getAsInt() != VANILLA_BUTTON_TEXTURE_HEIGHT) {
                    addViolation(
                        node,
                        "height expression `" + heightExpression + "` resolves to "
                            + height.getAsInt() + "px"
                    );
                }
                return super.visitNewClass(node, unused);
            }

            private void addViolation(final Tree node, final String message) {
                final long start = positions.getStartPosition(unit, node);
                final long line = unit.getLineMap().getLineNumber(start);
                final Path sourcePath = Path.of(unit.getSourceFile().toUri());
                violations.add(projectRoot.relativize(sourcePath) + ":" + line + ": " + message);
            }
        }.scan(unit, null);
    }

    private static OptionalInt evaluate(
        final ExpressionTree expression,
        final Map<String, ExpressionTree> constants,
        final Set<String> resolving
    ) {
        return switch (expression.getKind()) {
            case INT_LITERAL -> OptionalInt.of(((Number) ((LiteralTree) expression).getValue()).intValue());
            case IDENTIFIER -> evaluateIdentifier((IdentifierTree) expression, constants, resolving);
            case PARENTHESIZED -> evaluate(
                ((ParenthesizedTree) expression).getExpression(), constants, resolving
            );
            case UNARY_PLUS, UNARY_MINUS -> evaluateUnary((UnaryTree) expression, constants, resolving);
            case PLUS, MINUS, MULTIPLY, DIVIDE, REMAINDER -> evaluateBinary(
                (BinaryTree) expression, constants, resolving
            );
            default -> OptionalInt.empty();
        };
    }

    private static OptionalInt evaluateIdentifier(
        final IdentifierTree identifier,
        final Map<String, ExpressionTree> constants,
        final Set<String> resolving
    ) {
        final String name = identifier.getName().toString();
        final ExpressionTree value = constants.get(name);
        if (value == null || !resolving.add(name)) {
            return OptionalInt.empty();
        }
        final OptionalInt result = evaluate(value, constants, resolving);
        resolving.remove(name);
        return result;
    }

    private static OptionalInt evaluateUnary(
        final UnaryTree unary,
        final Map<String, ExpressionTree> constants,
        final Set<String> resolving
    ) {
        final OptionalInt value = evaluate(unary.getExpression(), constants, resolving);
        if (value.isEmpty()) {
            return value;
        }
        return OptionalInt.of(
            unary.getKind() == Tree.Kind.UNARY_MINUS ? -value.getAsInt() : value.getAsInt()
        );
    }

    private static OptionalInt evaluateBinary(
        final BinaryTree binary,
        final Map<String, ExpressionTree> constants,
        final Set<String> resolving
    ) {
        final OptionalInt left = evaluate(binary.getLeftOperand(), constants, resolving);
        final OptionalInt right = evaluate(binary.getRightOperand(), constants, resolving);
        if (left.isEmpty() || right.isEmpty()) {
            return OptionalInt.empty();
        }
        final int a = left.getAsInt();
        final int b = right.getAsInt();
        if ((binary.getKind() == Tree.Kind.DIVIDE || binary.getKind() == Tree.Kind.REMAINDER)
            && b == 0) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(switch (binary.getKind()) {
            case PLUS -> a + b;
            case MINUS -> a - b;
            case MULTIPLY -> a * b;
            case DIVIDE -> a / b;
            case REMAINDER -> a % b;
            default -> throw new IllegalStateException("Unexpected operator " + binary.getKind());
        });
    }

    private static Path findProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("common.gradle"))
                && Files.isDirectory(current.resolve("src/main/java"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate the Conflux Map project root");
    }
}
