package cn.net.rms.confluxmap.preprocess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class MainProjectPreprocessorBranchTest {
    private static final Pattern DIRECTIVE = Pattern.compile("^\\s*//#(if|elseif|else|endif)\\b(.*)$");
    private static final Pattern COMPARISON = Pattern.compile("^(.+?)(==|!=|<=|>=|<|>)(.+)$");

    @Test
    void mainProjectUsesTheEnabledPreprocessorBranches() throws IOException {
        final Path repositoryRoot = findRepositoryRoot();
        final String mainProject = Files.readString(repositoryRoot.resolve("versions/mainProject")).trim();
        final int mainMinecraftVersion = minecraftVersion(mainProject);

        final List<String> violations;
        try (Stream<Path> files = Files.walk(repositoryRoot.resolve("src"))) {
            violations = files
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".java"))
                .sorted()
                .flatMap(path -> validate(
                    repositoryRoot.relativize(path).toString(),
                    readLines(path),
                    mainMinecraftVersion
                ).stream())
                .toList();
        }

        assertTrue(
            violations.isEmpty(),
            () -> "Preprocessor branches do not match main project " + mainProject + ":\n"
                + String.join("\n", violations)
        );
    }

    @Test
    void validatorReportsBothMainProjectCommentMismatchDirections() {
        final List<String> violations = validate(
            "Example.java",
            List.of(
                "//#if MC>=12100",
                "int modernOnly;",
                "//#else",
                "//$$ int legacyOnly;",
                "//#endif"
            ),
            11701
        );

        assertEquals(2, violations.size());
        assertTrue(violations.get(0).startsWith("Example.java:2:"));
        assertTrue(violations.get(1).startsWith("Example.java:4:"));
    }

    private static List<String> validate(
        final String fileName,
        final List<String> lines,
        final int mainMinecraftVersion
    ) {
        final List<String> violations = new ArrayList<>();
        final Deque<BranchState> branches = new ArrayDeque<>();

        for (int index = 0; index < lines.size(); index++) {
            final String line = lines.get(index);
            final Matcher directive = DIRECTIVE.matcher(line);
            if (directive.matches()) {
                updateBranchState(
                    fileName,
                    index + 1,
                    directive.group(1),
                    directive.group(2).trim(),
                    mainMinecraftVersion,
                    branches,
                    violations
                );
                continue;
            }

            if (line.stripLeading().startsWith("//#")) {
                continue;
            }

            final boolean commentedOut = line.stripLeading().startsWith("//$$");
            final boolean active = branches.isEmpty() || branches.peek().currentActive;
            if (active == commentedOut) {
                violations.add(fileName + ":" + (index + 1) + ": main-project branch is "
                    + (active ? "active but line starts with //$$" : "inactive but line does not start with //$$"));
            }
        }

        while (!branches.isEmpty()) {
            final BranchState branch = branches.removeLast();
            violations.add(fileName + ":" + branch.startLine + ": preprocessor branch has no matching //#endif");
        }
        return violations;
    }

    private static void updateBranchState(
        final String fileName,
        final int lineNumber,
        final String directive,
        final String expression,
        final int mainMinecraftVersion,
        final Deque<BranchState> branches,
        final List<String> violations
    ) {
        switch (directive) {
            case "if": {
                final boolean parentActive = branches.isEmpty() || branches.peek().currentActive;
                final boolean condition = evaluate(expression, mainMinecraftVersion);
                branches.push(new BranchState(lineNumber, parentActive, condition));
                break;
            }
            case "elseif": {
                if (branches.isEmpty()) {
                    violations.add(fileName + ":" + lineNumber + ": //#elseif has no matching //#if");
                    break;
                }
                final BranchState branch = branches.peek();
                if (branch.elseSeen) {
                    violations.add(fileName + ":" + lineNumber + ": //#elseif appears after //#else");
                    branch.currentActive = false;
                    break;
                }
                final boolean condition = !branch.anyBranchMatched && evaluate(expression, mainMinecraftVersion);
                branch.currentActive = branch.parentActive && condition;
                branch.anyBranchMatched |= condition;
                break;
            }
            case "else": {
                if (branches.isEmpty()) {
                    violations.add(fileName + ":" + lineNumber + ": //#else has no matching //#if");
                    break;
                }
                final BranchState branch = branches.peek();
                if (branch.elseSeen) {
                    violations.add(fileName + ":" + lineNumber + ": duplicate //#else");
                    branch.currentActive = false;
                    break;
                }
                branch.elseSeen = true;
                branch.currentActive = branch.parentActive && !branch.anyBranchMatched;
                branch.anyBranchMatched = true;
                break;
            }
            case "endif": {
                if (branches.isEmpty()) {
                    violations.add(fileName + ":" + lineNumber + ": //#endif has no matching //#if");
                } else {
                    branches.pop();
                }
                break;
            }
            default:
                throw new IllegalArgumentException("Unknown preprocessor directive " + directive);
        }
    }

    private static boolean evaluate(final String expression, final int mainMinecraftVersion) {
        for (final String alternative : expression.split("\\|\\|")) {
            boolean matches = true;
            for (final String condition : alternative.split("&&")) {
                matches &= evaluateCondition(condition.trim(), mainMinecraftVersion);
            }
            if (matches) {
                return true;
            }
        }
        return false;
    }

    private static boolean evaluateCondition(final String expression, final int mainMinecraftVersion) {
        if (expression.startsWith("!")) {
            return !evaluateCondition(expression.substring(1).trim(), mainMinecraftVersion);
        }

        final Matcher comparison = COMPARISON.matcher(expression);
        if (!comparison.matches()) {
            return value(expression, mainMinecraftVersion) != 0;
        }

        final int left = value(comparison.group(1).trim(), mainMinecraftVersion);
        final int right = value(comparison.group(3).trim(), mainMinecraftVersion);
        switch (comparison.group(2)) {
            case "==":
                return left == right;
            case "!=":
                return left != right;
            case "<=":
                return left <= right;
            case ">=":
                return left >= right;
            case "<":
                return left < right;
            case ">":
                return left > right;
            default:
                throw new IllegalArgumentException("Unknown comparison in " + expression);
        }
    }

    private static int value(final String token, final int mainMinecraftVersion) {
        if (token.equals("MC")) {
            return mainMinecraftVersion;
        }
        if (token.contains(".")) {
            return minecraftVersion(token);
        }
        try {
            return Integer.parseInt(token.replace("_", ""));
        } catch (final NumberFormatException error) {
            throw new IllegalArgumentException("Unsupported preprocessor value: " + token, error);
        }
    }

    private static List<String> readLines(final Path path) {
        try {
            return Files.readAllLines(path);
        } catch (final IOException error) {
            throw new IllegalStateException("Failed to read " + path, error);
        }
    }

    private static int minecraftVersion(final String version) {
        final String[] parts = version.split("\\.");
        final int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
        return Integer.parseInt(parts[0]) * 10_000
            + Integer.parseInt(parts[1]) * 100
            + patch;
    }

    private static Path findRepositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("versions/mainProject"))
                && Files.isDirectory(candidate.resolve("src"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Could not find the repository root from " + Path.of("").toAbsolutePath());
    }

    private static final class BranchState {
        private final int startLine;
        private final boolean parentActive;
        private boolean anyBranchMatched;
        private boolean currentActive;
        private boolean elseSeen;

        private BranchState(final int startLine, final boolean parentActive, final boolean condition) {
            this.startLine = startLine;
            this.parentActive = parentActive;
            this.anyBranchMatched = condition;
            this.currentActive = parentActive && condition;
        }
    }
}
