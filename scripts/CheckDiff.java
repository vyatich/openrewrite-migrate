import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Checks that a change does not weaken the tests: no deleted test files, no added @Disabled /
 * @Ignore, no fewer @Test methods, no fewer assertions. Exits 1 on a violation, so as a pre-commit
 * hook it stops the commit — the rule is enforced by code, not by a sentence in a prompt.
 *
 * <pre>
 * java CheckDiff.java [--staged]                 changes staged for commit (pre-commit mode, default)
 * java CheckDiff.java --base REV                 everything since REV, including uncommitted changes
 * java CheckDiff.java --install-hook             install as .git/hooks/pre-commit of the current repository
 * java CheckDiff.java ... --report-only          print the report, never fail
 * </pre>
 *
 * Changing a test to follow an API change keeps the counts the same and passes. Needs JDK 17+.
 */
public class CheckDiff {

    static final Pattern TEST_FILE = Pattern.compile(".*(/src/test/|^src/test/).*\\.(java|kt|groovy)$");
    // annotations may be written fully qualified: @org.junit.jupiter.api.Disabled
    static final Pattern TEST_METHOD = Pattern.compile("@(?:[\\w.]+\\.)?(Test|ParameterizedTest|RepeatedTest|TestFactory)\\b");
    static final Pattern DISABLED = Pattern.compile("@(?:[\\w.]+\\.)?(Disabled|Ignore)\\b");
    static final Pattern ASSERTION = Pattern.compile(
        "\\b(assert[A-Z]\\w*|assertThat\\w*|andExpect|verify|then|catchThrowable\\w*|fail)\\s*\\(");

    public static void main(String[] args) throws Exception {
        List<String> argv = List.of(args);
        Path repo = Path.of(git(Path.of("."), "rev-parse", "--show-toplevel").get(0).strip());
        if (argv.contains("--install-hook")) {
            installHook(repo);
            return;
        }
        boolean reportOnly = argv.contains("--report-only");
        int baseAt = argv.indexOf("--base");
        List<String> diffArgs = new ArrayList<>(List.of("diff", "-U0", "--no-color", "--no-ext-diff"));
        String mode;
        if (baseAt >= 0 && baseAt + 1 < argv.size()) {
            diffArgs.add(argv.get(baseAt + 1));
            mode = "since " + argv.get(baseAt + 1);
        } else {
            diffArgs.add("--cached");
            mode = "staged";
        }

        List<String> deleted = new ArrayList<>(git(repo, concat(diffArgs, "--name-only", "--diff-filter=D")));
        deleted.removeIf(f -> !TEST_FILE.matcher(f).matches());

        Map<String, int[]> perFile = new LinkedHashMap<>(); // [tests-, tests+, disabled+, asserts-, asserts+]
        String file = null;
        for (String line : git(repo, diffArgs.toArray(String[]::new))) {
            if (line.startsWith("+++ ")) {
                String path = line.substring(4);
                file = path.equals("/dev/null") ? null : path.replaceFirst("^b/", "");
                continue;
            }
            if (line.startsWith("--- ")) {
                String path = line.substring(4);
                if (!path.equals("/dev/null")) {
                    file = path.replaceFirst("^a/", ""); // deleted files keep their old name
                }
                continue;
            }
            if (file == null || !TEST_FILE.matcher(file).matches()) {
                continue;
            }
            boolean added = line.startsWith("+");
            boolean removed = line.startsWith("-");
            if (!added && !removed) {
                continue;
            }
            String code = line.substring(1).strip();
            if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) {
                continue;
            }
            int[] c = perFile.computeIfAbsent(file, f -> new int[5]);
            int tests = count(TEST_METHOD, code);
            int asserts = count(ASSERTION, code);
            if (removed) {
                c[0] += tests;
                c[3] += asserts;
            } else {
                c[1] += tests;
                c[2] += count(DISABLED, code);
                c[4] += asserts;
            }
        }

        List<String> violations = new ArrayList<>();
        deleted.forEach(f -> violations.add("test file deleted: " + f));
        perFile.forEach((f, c) -> {
            if (c[1] - c[0] < 0) {
                violations.add(f + ": " + (c[0] - c[1]) + " test method(s) removed");
            }
            if (c[2] > 0) {
                violations.add(f + ": @Disabled/@Ignore added");
            }
            if (c[4] - c[3] < 0) {
                violations.add(f + ": " + (c[3] - c[4]) + " assertion(s) removed");
            }
        });

        StringBuilder json = new StringBuilder("{\"mode\":\"").append(mode).append("\",\"testFilesChanged\":")
            .append(perFile.size()).append(",\"violations\":[");
        json.append(violations.stream().map(v -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
            .collect(Collectors.joining(",")));
        json.append("],\"verdict\":\"").append(violations.isEmpty() ? "OK" : "VIOLATION").append("\"");
        if (!violations.isEmpty()) {
            json.append(",\"hint\":\"tests may change only to follow an API change, keeping what they check;"
                + " fix the code instead, or ask the user\"");
        }
        System.out.println(json.append('}'));
        if (!violations.isEmpty() && !reportOnly) {
            System.err.println("CheckDiff: commit blocked - the change weakens tests (see violations above)");
            System.exit(1);
        }
    }

    static void installHook(Path repo) throws IOException, InterruptedException {
        Path self = Path.of(System.getProperty("jdk.launcher.sourcefile", "CheckDiff.java")).toAbsolutePath();
        Path hooks = Path.of(git(repo, "rev-parse", "--git-path", "hooks").get(0).strip());
        if (!hooks.isAbsolute()) {
            hooks = repo.resolve(hooks);
        }
        Files.createDirectories(hooks);
        Path hook = hooks.resolve("pre-commit");
        String script = "#!/bin/sh\n# installed by CheckDiff.java: block commits that weaken tests\n"
            + "exec java \"" + self.toString().replace('\\', '/') + "\" --staged\n";
        Files.writeString(hook, script, StandardCharsets.UTF_8);
        hook.toFile().setExecutable(true);
        System.out.println("{\"installed\":\"" + hook.toString().replace("\\", "\\\\") + "\"}");
    }

    static int count(Pattern p, String s) {
        return (int) p.matcher(s).results().count();
    }

    static String[] concat(List<String> base, String... more) {
        List<String> all = new ArrayList<>(base);
        all.addAll(1, List.of(more));
        return all.toArray(String[]::new);
    }

    static List<String> git(Path dir, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(false).start();
        List<String> lines = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines()
            .collect(Collectors.toList());
        p.waitFor();
        return lines;
    }
}
