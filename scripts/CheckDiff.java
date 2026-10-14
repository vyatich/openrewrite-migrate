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
 * Checks that a change does not weaken the tests: no deleted test files, no more disabled tests,
 * no fewer test methods, no fewer assertions, no build setting that skips or ignores tests. Exits 1
 * on a violation, so as a pre-commit hook it stops the commit — the rule is enforced by code, not
 * by a sentence in a prompt.
 *
 * <pre>
 * java CheckDiff.java [--staged]                 changes staged for commit (pre-commit mode, default)
 * java CheckDiff.java --base REV                 everything since REV, including uncommitted changes
 * java CheckDiff.java --install-hook             install as the pre-commit hook of the current repository
 * java CheckDiff.java ... --report-only          print the report, never fail
 * </pre>
 *
 * Test files: any test source set (src/test, src/integrationTest, src/testFixtures, ...) in Java,
 * Kotlin, Groovy, Scala. Frameworks: JUnit 4/5, TestNG, Spock, Kotest, AssertJ, Hamcrest, Mockito,
 * StepVerifier/WebTestClient. Changing a test to follow an API change keeps the counts the same and
 * passes; so does renaming an annotation (@Ignore -> @Disabled in a JUnit 4 -> 5 migration).
 * Needs JDK 17+.
 */
public class CheckDiff {

    static final Pattern TEST_FILE = Pattern.compile("(.*/)?src/\\w*[Tt]est\\w*/.*\\.(java|kt|groovy|scala)$");
    // annotations may be written fully qualified: @org.junit.jupiter.api.Disabled
    static final Pattern TEST_METHOD = Pattern.compile(
        "@(?:[\\w.]+\\.)?(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate|Property|Example)\\b"
            // Spock feature methods: def "does something"()
            + "|\\bdef\\s+[\"'][^\"']+[\"']\\s*\\("
            // Kotest / Spek style: test("..."), should("..."), it("..."), scenario("...")
            + "|\\b(test|should|it|scenario|expect)\\s*\\(\\s*\"");
    static final Pattern DISABLED = Pattern.compile(
        "@(?:[\\w.]+\\.)?(Disabled\\w*|Ignore|Ignored|IgnoreIf|PendingFeature|Requires)\\b"
            + "|\\benabled\\s*=\\s*false\\b|\\bx(test|it|describe|context|should)\\s*\\(|\\bassume\\w*\\s*\\(");
    static final Pattern ASSERTION = Pattern.compile(
        "\\b(assert[A-Z]\\w*|andExpect\\w*|verify\\w*|then|catchThrowable\\w*|fail|expect\\w*)\\s*\\("
            // Kotest matchers (often infix: x shouldBe y), Groovy/Spock power assert, Spock blocks
            + "|\\bshould[A-Z]\\w*\\b|^assert\\s|^(then|expect):");
    // build settings that skip tests or ignore their failures
    static final Pattern BUILD_FILE = Pattern.compile("(.*/)?(pom\\.xml|[\\w.-]*\\.gradle(\\.kts)?|gradle\\.properties|maven\\.config)$");
    static final Pattern SKIP_TESTS = Pattern.compile(
        "skipTests|skipITs|maven\\.test\\.skip|testFailureIgnore|ignoreFailures\\s*=\\s*true|failOnNoMatchingTests"
            + "|\\b(test|check)\\s*\\{[^}]*enabled\\s*=\\s*false|tasks\\.(named\\(\"test\"\\)|test)[^\\n]*enabled\\s*=\\s*false"
            + "|<exclude>[^<]*Test[^<]*</exclude>|exclude\\s*\\(?\\s*[\"'][^\"']*Test[^\"']*[\"']");

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

        Map<String, int[]> perFile = new LinkedHashMap<>(); // [tests-, tests+, disabled+, asserts-, asserts+, disabled-]
        List<String> skipping = new ArrayList<>();
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
            if (file == null) {
                continue;
            }
            boolean added = line.startsWith("+");
            boolean removed = line.startsWith("-");
            if (!added && !removed) {
                continue;
            }
            String code = line.substring(1).strip();
            if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*") || code.startsWith("#")
                    || code.startsWith("<!--")) {
                continue;
            }
            if (BUILD_FILE.matcher(file).matches()) {
                if (added && SKIP_TESTS.matcher(code).find()) {
                    skipping.add(file + ": build skips or ignores tests - " + code);
                }
                continue;
            }
            if (!TEST_FILE.matcher(file).matches()) {
                continue;
            }
            int[] c = perFile.computeIfAbsent(file, f -> new int[6]);
            int tests = count(TEST_METHOD, code);
            int asserts = count(ASSERTION, code);
            if (removed) {
                c[0] += tests;
                c[3] += asserts;
                c[5] += count(DISABLED, code);
            } else {
                c[1] += tests;
                c[2] += count(DISABLED, code);
                c[4] += asserts;
            }
        }

        List<String> violations = new ArrayList<>();
        deleted.forEach(f -> violations.add("test file deleted: " + f));
        violations.addAll(skipping);
        perFile.forEach((f, c) -> {
            if (c[1] - c[0] < 0) {
                violations.add(f + ": " + (c[0] - c[1]) + " test method(s) removed");
            }
            // net count: a recipe that renames @Ignore to @Disabled adds as many as it removes
            if (c[2] - c[5] > 0) {
                violations.add(f + ": " + (c[2] - c[5]) + " test(s) disabled or made conditional");
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

    static final String MARKER = "# installed by CheckDiff.java";

    /**
     * Installs the check as the pre-commit hook. An existing hook keeps working: it is moved aside
     * and called after the check. Hooks that are part of the repository (core.hooksPath pointing to a
     * tracked directory) are never touched: then nothing is installed and the report says so.
     */
    static void installHook(Path repo) throws IOException, InterruptedException {
        Path self = Path.of(System.getProperty("jdk.launcher.sourcefile", "CheckDiff.java")).toAbsolutePath();
        Path hooks = Path.of(git(repo, "rev-parse", "--git-path", "hooks").get(0).strip());
        if (!hooks.isAbsolute()) {
            hooks = repo.resolve(hooks);
        }
        hooks = hooks.normalize();
        Path gitDir = Path.of(git(repo, "rev-parse", "--git-common-dir").get(0).strip());
        gitDir = (gitDir.isAbsolute() ? gitDir : repo.resolve(gitDir)).normalize();
        Path hook = hooks.resolve("pre-commit");
        String manual = "java \\\"" + json(self.toString().replace('\\', '/')) + "\\\" --staged";
        // inside the work tree a hook file is a repository file, unless git ignores it (husky's .husky/_)
        if (!hooks.startsWith(gitDir) && gitExit(repo, "check-ignore", "-q", hook.toString()) != 0) {
            System.out.println("{\"installed\":false,\"reason\":\"hooks are in " + json(repo.relativize(hooks).toString())
                + ", a directory of the repository (core.hooksPath); not changing repository files\","
                + "\"instead\":\"run " + manual + " before every commit\"}");
            return;
        }
        Files.createDirectories(hooks);
        Path chained = hooks.resolve("pre-commit.before-checkdiff");
        if (Files.exists(hook) && !Files.readString(hook).contains(MARKER)) {
            Files.move(hook, chained, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        // the JDK running this script: the "java" on PATH may be older than 17
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        String script = "#!/bin/sh\n" + MARKER + ": block commits that weaken tests\n"
            + "\"" + java.toString().replace('\\', '/') + "\" \"" + self.toString().replace('\\', '/') + "\" --staged || exit 1\n"
            + "if [ -x \"" + chained.toString().replace('\\', '/') + "\" ]; then\n"
            + "  exec \"" + chained.toString().replace('\\', '/') + "\" \"$@\"\n"
            + "fi\n";
        Files.writeString(hook, script, StandardCharsets.UTF_8);
        hook.toFile().setExecutable(true);
        System.out.println("{\"installed\":\"" + json(hook.toString()) + "\""
            + (Files.exists(chained) ? ",\"chained\":\"" + json(chained.toString()) + "\"" : "") + "}");
    }

    static String json(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static int gitExit(Path dir, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        return p.waitFor();
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
