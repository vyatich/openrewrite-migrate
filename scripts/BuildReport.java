import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Builds a Maven or Gradle project and prints a compact JSON report instead of the build log:
 * verdict, compile errors (Java and Kotlin) grouped by cause, failed tests without stack traces,
 * build-script deprecations. The full log goes to a file outside the project. Multi-module builds
 * are covered: test reports are collected from every module, the build goes on after the first
 * failing module (--continue / -fae) so one run shows all of them.
 *
 * <pre>
 * java BuildReport.java [--project DIR] [--task build|compile|test] [--tests PATTERN]
 *                       [--java-home DIR] [--offline] [--build gradle|maven]
 * </pre>
 *
 * The wrapper is looked up in DIR and its parents. Gradle runs on a JDK it supports: if
 * --java-home is not given, the newest installed JDK that the wrapper version can start on is
 * picked. Needs JDK 17+ to run this script itself.
 */
public class BuildReport {

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = Args.parse(args);
        Path project = Path.of(opt.getOrDefault("project", ".")).toAbsolutePath().normalize();
        Tool tool = Tool.detect(project, opt.get("build"));
        String task = opt.getOrDefault("task", "build");
        if (opt.containsKey("tests") && task.equals("build")) {
            task = "test"; // a test filter belongs to the test task; "build --tests" is rejected by Gradle
        }

        Json report = new Json();
        report.put("project", project.toString());
        report.put("tool", tool.name);
        if (tool.name.equals("none")) {
            report.put("verdict", "ERROR");
            report.put("error", "no pom.xml or build.gradle(.kts) / settings.gradle(.kts) in " + project);
            System.out.println(report);
            System.exit(2);
        }

        Jdk jdk = Jdk.choose(opt.get("java-home"), tool.gradleVersion(project));
        report.put("javaHome", jdk.home);
        if (jdk.note != null) {
            report.put("jdkNote", jdk.note);
        }

        List<String> command = tool.command(project, task, opt);
        report.put("command", String.join(" ", command));
        Path log = Files.createTempFile("build-report-", ".log");
        Instant started = Instant.now();
        int exit = Run.process(command, project, jdk.home, log);
        Duration took = Duration.between(started, Instant.now());

        // old JDKs write the log in the platform charset; decode leniently instead of failing
        List<String> lines = new String(Files.readAllBytes(log), StandardCharsets.UTF_8).lines().collect(Collectors.toList());
        // the build tool's own verdict decides: old gradlew.bat versions can exit non-zero after BUILD SUCCESSFUL
        boolean saidSuccess = lines.stream().anyMatch(l -> l.startsWith("BUILD SUCCESSFUL") || l.contains("] BUILD SUCCESS"));
        boolean saidFailure = lines.stream().anyMatch(l -> l.startsWith("BUILD FAILED") || l.contains("] BUILD FAILURE"));
        boolean success = saidSuccess || saidFailure ? saidSuccess && !saidFailure : exit == 0;
        report.put("verdict", success ? "SUCCESS" : "FAILED");
        report.put("exitCode", exit);
        report.put("seconds", took.toSeconds());
        report.put("log", log.toString());
        report.put("logLines", lines.size());

        if (!success) {
            report.put("whatWentWrong", Logs.whatWentWrong(lines));
        }
        Compile compile = Compile.parse(lines, project);
        if (!compile.errors.isEmpty()) {
            report.put("compileErrors", compile.toJson());
        }
        // Gradle skips an unchanged test task; then the last reports are still the current ones
        boolean testsUpToDate = lines.stream().anyMatch(l -> l.matches("> Task :\\S*test (UP-TO-DATE|FROM-CACHE)"));
        Tests tests = Tests.collect(project, testsUpToDate ? Instant.EPOCH : started.minusSeconds(2));
        if (tests.total > 0) {
            Json testJson = tests.toJson();
            if (testsUpToDate) {
                testJson.put("note", "test task was up to date: results of the last run, tests were not re-executed");
            }
            report.put("tests", testJson);
        } else if (opt.containsKey("tests") && success) {
            report.put("testsNote", "no test matched --tests " + opt.get("tests"));
        }
        List<String> deprecations = Logs.buildScriptDeprecations(lines);
        if (!deprecations.isEmpty()) {
            report.put("buildScriptDeprecations", deprecations);
        }
        report.put("summary", summary(success, compile, tests));
        System.out.println(report);
        System.exit(success ? 0 : 1);
    }

    static String summary(boolean success, Compile compile, Tests tests) {
        if (!compile.errors.isEmpty()) {
            String where = compile.byLocation.entrySet().stream().map(e -> e.getKey() + " " + e.getValue())
                .collect(Collectors.joining(", "));
            return String.format("FAILED: %d compile errors (%s)%s", compile.errors.size(), where,
                compile.javacCount != null && compile.javacCount >= 100 ? " - javac stopped at 100, more behind" : "");
        }
        if (tests.failed > 0) {
            return String.format("FAILED: %d of %d tests failed", tests.failed, tests.total);
        }
        if (success) {
            return tests.total > 0 ? String.format("SUCCESS: %d tests, %d skipped", tests.total, tests.skipped) : "SUCCESS";
        }
        return "FAILED: see whatWentWrong";
    }

    // ---------------------------------------------------------------- build tool

    record Tool(String name) {
        static Tool detect(Path project, String forced) {
            if (forced != null) {
                return new Tool(forced);
            }
            boolean gradle = Stream.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")
                .anyMatch(f -> Files.exists(project.resolve(f)));
            if (gradle) {
                return new Tool("gradle");
            }
            return new Tool(Files.exists(project.resolve("pom.xml")) ? "maven" : "none");
        }

        String gradleVersion(Path project) throws IOException {
            if (!name.equals("gradle")) {
                return null;
            }
            Path props = Optional.ofNullable(Run.findUp(project, "gradle/wrapper/gradle-wrapper.properties"))
                .orElse(project.resolve("gradle/wrapper/gradle-wrapper.properties"));
            if (!Files.exists(props)) {
                return null;
            }
            Matcher m = Pattern.compile("gradle-(\\d+(?:\\.\\d+)*)-").matcher(Files.readString(props));
            return m.find() ? m.group(1) : null;
        }

        List<String> command(Path project, String task, Map<String, String> opt) throws IOException {
            List<String> cmd = new ArrayList<>(Run.launcher(project, name));
            if (name.equals("gradle")) {
                switch (task) {
                    // lifecycle tasks: compile every language of main and test (Java, Kotlin, Groovy) in every module
                    case "compile" -> cmd.addAll(List.of("classes", "testClasses"));
                    case "test" -> cmd.add("test");
                    default -> cmd.add("build");
                }
                if (opt.containsKey("tests")) {
                    cmd.addAll(List.of("--tests", opt.get("tests")));
                    // in a multi-module build most modules have no matching test; that must not fail them
                    Path init = Files.createTempFile("build-report-", ".gradle");
                    Files.writeString(init, "allprojects { tasks.withType(Test).configureEach { filter.failOnNoMatchingTests = false } }\n");
                    cmd.addAll(List.of("--init-script", init.toString()));
                }
                // --continue: the other modules still compile and test after one fails
                // --warning-mode all: deprecations used by the build scripts, with file and line
                cmd.addAll(List.of("--continue", "--warning-mode", "all", "--console=plain", "--no-daemon"));
                if (opt.containsKey("offline")) {
                    cmd.add("--offline");
                }
            } else {
                cmd.addAll(List.of("-B", "-fae"));
                switch (task) {
                    case "compile" -> cmd.add("test-compile");
                    case "test" -> cmd.add("test");
                    default -> cmd.add("verify");
                }
                if (opt.containsKey("tests")) {
                    // modules without a matching test must not fail (property name differs between Surefire versions)
                    cmd.addAll(List.of("-Dtest=" + opt.get("tests"), "-Dsurefire.failIfNoSpecifiedTests=false",
                        "-DfailIfNoTests=false"));
                }
                if (opt.containsKey("offline")) {
                    cmd.add("-o");
                }
            }
            return cmd;
        }
    }

    // ---------------------------------------------------------------- JDK choice

    record Jdk(String home, String note) {
        /** Newest JDK each Gradle version can start on (the code itself is compiled by the toolchain). */
        static int maxJdkFor(String gradle) {
            if (gradle == null) {
                return Integer.MAX_VALUE;
            }
            String[] p = gradle.split("\\.");
            double v = Integer.parseInt(p[0]) + (p.length > 1 ? Integer.parseInt(p[1]) / 100.0 : 0);
            if (v >= 9.01) return 25;
            if (v >= 8.14) return 24;
            if (v >= 8.10) return 23;
            if (v >= 8.08) return 22;
            if (v >= 8.05) return 21;
            if (v >= 8.03) return 20;
            if (v >= 7.06) return 19;
            if (v >= 7.05) return 18;
            if (v >= 7.03) return 17;
            if (v >= 7.00) return 16;
            if (v >= 6.07) return 15;
            if (v >= 6.03) return 14;
            if (v >= 6.00) return 13;
            return 11;
        }

        static Jdk choose(String forced, String gradleVersion) {
            if (forced != null) {
                return new Jdk(forced, null);
            }
            int max = maxJdkFor(gradleVersion);
            List<Map.Entry<Integer, String>> installed = discover();
            Optional<Map.Entry<Integer, String>> best = installed.stream()
                .filter(e -> e.getKey() <= max)
                .max(Map.Entry.comparingByKey());
            String current = Optional.ofNullable(System.getenv("JAVA_HOME")).orElse(System.getProperty("java.home"));
            if (best.isPresent()) {
                String note = gradleVersion == null ? null
                    : "Gradle " + gradleVersion + " runs on JDK <= " + max + "; picked JDK " + best.get().getKey();
                return new Jdk(best.get().getValue(), note);
            }
            return new Jdk(current, "no installed JDK <= " + max + " found for Gradle " + gradleVersion
                + "; using " + current + " - pass --java-home");
        }

        static List<Map.Entry<Integer, String>> discover() {
            Set<Path> roots = new LinkedHashSet<>();
            String home = System.getProperty("user.home");
            for (String dir : List.of("C:/Program Files/Eclipse Adoptium", "C:/Program Files/Java", "C:/Program Files/Microsoft",
                    "C:/Program Files/Amazon Corretto", "C:/Program Files/BellSoft", "C:/Program Files/Zulu",
                    "C:/Program Files/OpenJDK", "C:/Program Files/RedHat", "C:/Program Files/Semeru", home + "/scoop/apps",
                    "/usr/lib/jvm", "/usr/java", "/opt", "/opt/java", "/Library/Java/JavaVirtualMachines",
                    "/opt/homebrew/opt", "/usr/local/opt", home + "/.sdkman/candidates/java", home + "/.asdf/installs/java",
                    home + "/.local/share/mise/installs/java", home + "/.gradle/jdks", home + "/.jdks")) {
                Path root = Path.of(dir);
                if (Files.isDirectory(root)) {
                    try (Stream<Path> children = Files.list(root)) {
                        children.forEach(roots::add);
                    } catch (IOException ignored) {
                        // unreadable directory: skip
                    }
                }
            }
            // JAVA_HOME and CI variables such as JAVA_HOME_21_X64
            System.getenv().forEach((k, v) -> {
                if (k.toUpperCase(Locale.ROOT).startsWith("JAVA_HOME") && !v.isBlank()) {
                    roots.add(Path.of(v));
                }
            });
            roots.add(Path.of(System.getProperty("java.home")));
            Map<Integer, String> byVersion = new LinkedHashMap<>();
            for (Path root : roots) {
                // plain layout, macOS bundle, Homebrew formula, scoop "current"
                for (Path candidate : List.of(root, root.resolve("Contents/Home"), root.resolve("libexec/openjdk.jdk/Contents/Home"),
                        root.resolve("current"))) {
                    Integer version = releaseVersion(candidate);
                    if (version != null) {
                        byVersion.putIfAbsent(version, candidate.toString());
                    }
                }
            }
            return new ArrayList<>(byVersion.entrySet());
        }

        static Integer releaseVersion(Path jdk) {
            Path release = jdk.resolve("release");
            if (!Files.isRegularFile(release)) {
                return null;
            }
            try {
                Matcher m = Pattern.compile("JAVA_VERSION=\"(\\d+)(?:\\.(\\d+))?").matcher(Files.readString(release));
                if (!m.find()) {
                    return null;
                }
                int major = Integer.parseInt(m.group(1));
                return major == 1 && m.group(2) != null ? Integer.parseInt(m.group(2)) : major;
            } catch (IOException e) {
                return null;
            }
        }
    }

    // ---------------------------------------------------------------- running

    static class Run {
        static boolean windows() {
            return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        }

        /** The file at this relative path in dir or its nearest parent: a module of a bigger build has no wrapper of its own. */
        static Path findUp(Path dir, String relative) {
            for (Path d = dir; d != null; d = d.getParent()) {
                if (Files.exists(d.resolve(relative))) {
                    return d.resolve(relative);
                }
            }
            return null;
        }

        static List<String> launcher(Path project, String tool) {
            String wrapper = tool.equals("gradle") ? "gradlew" : "mvnw";
            if (windows()) {
                Path bat = findUp(project, wrapper + (tool.equals("gradle") ? ".bat" : ".cmd"));
                return List.of("cmd", "/c", bat != null ? bat.toString() : (tool.equals("gradle") ? "gradle" : "mvn"));
            }
            Path script = findUp(project, wrapper);
            if (script != null) {
                return Files.isExecutable(script) ? List.of(script.toString()) : List.of("sh", script.toString());
            }
            return List.of(tool.equals("gradle") ? "gradle" : "mvn");
        }

        static int process(List<String> command, Path dir, String javaHome, Path log) throws IOException, InterruptedException {
            ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile());
            if (javaHome != null) {
                pb.environment().put("JAVA_HOME", javaHome);
                String path = pb.environment().getOrDefault("PATH", pb.environment().getOrDefault("Path", ""));
                pb.environment().put(pb.environment().containsKey("Path") ? "Path" : "PATH",
                    javaHome + File.separator + "bin" + File.pathSeparator + path);
            }
            return pb.start().waitFor();
        }
    }

    // ---------------------------------------------------------------- log parsing

    static class Logs {
        static List<String> whatWentWrong(List<String> lines) {
            List<String> out = new ArrayList<>();
            boolean in = false;
            for (String line : lines) {
                String t = line.strip();
                if (t.startsWith("* What went wrong")) {
                    in = true;
                    continue;
                }
                if (in && (t.startsWith("* Try") || t.startsWith("* Exception"))) {
                    break;
                }
                // Gradle: the first line names the failure, causes follow as "> ..."; compiler
                // output repeated here is skipped (it is reported as compileErrors)
                if (in && !t.isEmpty() && (out.isEmpty() || t.startsWith(">"))) {
                    out.add(t);
                }
                if (out.size() >= 6) {
                    break;
                }
            }
            if (out.isEmpty()) {
                lines.stream().map(String::strip).filter(l -> l.startsWith("[ERROR]") && !l.contains(".java:"))
                    .limit(6).forEach(out::add);
            }
            return out;
        }

        /**
         * Kotlin DSL: compiler warnings on the build script ("w: .../build.gradle.kts:49:21: ... deprecated").
         * Both DSLs: Gradle's own deprecation messages (--warning-mode all), with the location Gradle prints
         * on the line before ("Build file '/x/build.gradle': line 12").
         */
        static List<String> buildScriptDeprecations(List<String> lines) {
            Pattern kts = Pattern.compile("^w: .*[/\\\\]([^/\\\\]+\\.gradle(?:\\.kts)?:\\d+:\\d+: .*deprecated.*)$");
            Pattern where = Pattern.compile("^(?:Build|Settings|Init) file '(?:.*[/\\\\])?([^/\\\\']+)': line (\\d+)$");
            List<String> out = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                String t = lines.get(i).strip();
                Matcher k = kts.matcher(t);
                if (k.matches()) {
                    out.add(k.group(1));
                    continue;
                }
                if (t.contains(" has been deprecated") && t.contains("scheduled to be removed")) {
                    String message = t.replaceFirst("\\s+(Consult the upgrading guide|For more information|Documentation:).*$", "");
                    Matcher w = i > 0 ? where.matcher(lines.get(i - 1).strip()) : null;
                    out.add((w != null && w.matches() ? w.group(1) + ":" + w.group(2) + ": " : "")
                        + (message.length() > 200 ? message.substring(0, 200) + "..." : message));
                }
            }
            return out.stream().distinct().limit(15).collect(Collectors.toList());
        }
    }

    record CompileError(String file, int line, String message, String symbol, String location) {
    }

    static class Compile {
        final List<CompileError> errors = new ArrayList<>();
        final Map<String, Integer> byLocation = new LinkedHashMap<>();
        Integer javacCount;

        static final Pattern ERROR = Pattern.compile("^(?:\\[ERROR\\] )?(.+?\\.(?:java|kt)):(?:\\[(\\d+),\\d+\\]|(\\d+)):? (?:error: )?(.+)$");
        // kotlinc: "e: file:///x/A.kt:12:5 msg" (Kotlin 1.8+), "e: /x/A.kt: (12, 5): msg" (older),
        // Maven: "[ERROR] /x/A.kt: (12, 5) msg"; also build scripts (.gradle.kts)
        static final Pattern KOTLIN = Pattern.compile(
            "^(?:\\[ERROR\\] )?(?:e: )?(?:file://)?(.+?\\.kts?)(?::(\\d+):\\d+|: \\((\\d+), \\d+\\)|:\\[(\\d+),\\d+\\]):? (.+)$");
        static final Pattern COUNT = Pattern.compile("^(\\d+) errors?$");
        static final Pattern TEST_SOURCES = Pattern.compile(".*/src/\\w*[Tt]est\\w*/.*");

        static Compile parse(List<String> lines, Path project) {
            Compile c = new Compile();
            Set<String> seen = new LinkedHashSet<>();
            int stop = lines.size();
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).strip().startsWith("* What went wrong")) {
                    stop = i; // Gradle repeats the compiler output below this line
                    break;
                }
            }
            for (int i = 0; i < stop; i++) {
                String t = lines.get(i).strip();
                Matcher count = COUNT.matcher(t);
                if (count.matches()) {
                    c.javacCount = Integer.parseInt(count.group(1));
                    continue;
                }
                // javac "error:", Maven "[ERROR]" (its lines carry no lowercase "error"), kotlinc "e: "
                if (!(t.contains("error") || t.startsWith("[ERROR]") || t.startsWith("e: "))) {
                    continue;
                }
                String file;
                int line;
                String message;
                Matcher m = ERROR.matcher(t);
                Matcher k = KOTLIN.matcher(t);
                if (!t.startsWith("e: ") && m.matches()) {
                    file = m.group(1);
                    line = Integer.parseInt(m.group(2) != null ? m.group(2) : m.group(3));
                    message = m.group(4).strip();
                } else if (k.matches() && (t.startsWith("e: ") || t.startsWith("[ERROR]"))) {
                    file = k.group(1).replaceFirst("^/([A-Za-z]:)", "$1"); // file:///D:/x -> D:/x
                    line = Integer.parseInt(k.group(2) != null ? k.group(2) : k.group(3) != null ? k.group(3) : k.group(4));
                    message = k.group(5).strip();
                } else {
                    continue;
                }
                file = file.replace('\\', '/');
                String symbol = null;
                for (int j = i + 1; message.contains("cannot find symbol") && j < Math.min(stop, i + 5); j++) {
                    String s = lines.get(j).strip().replaceFirst("^\\[ERROR\\]\\s*", "");
                    if (s.startsWith("symbol:")) {
                        symbol = s.substring("symbol:".length()).strip().replaceAll("\\s+", " ");
                        break;
                    }
                }
                String location = file.endsWith(".gradle.kts") ? "build script"
                    : file.contains("/build/generated/") || file.contains("/target/generated") || file.contains("/build/tmp/kapt")
                    ? "generated" : TEST_SOURCES.matcher(file).matches() ? "test" : "main";
                String relative = relativize(project, file);
                if (seen.add(relative + ":" + line + ":" + message + ":" + symbol)) {
                    c.errors.add(new CompileError(relative, line, message, symbol, location));
                    c.byLocation.merge(location, 1, Integer::sum);
                }
            }
            return c;
        }

        static String relativize(Path project, String file) {
            String root = project.toString().replace('\\', '/');
            return file.startsWith(root) ? file.substring(root.length() + 1) : file;
        }

        Json toJson() {
            Json j = new Json();
            j.put("total", errors.size());
            if (javacCount != null) {
                j.put("javacReported", javacCount);
            }
            j.put("byLocation", byLocation);
            j.put("byMessage", top(errors.stream().map(CompileError::message), 15));
            j.put("missingSymbols", top(errors.stream().map(CompileError::symbol).filter(s -> s != null), 15));
            j.put("byFile", top(errors.stream().map(e -> e.file().substring(e.file().lastIndexOf('/') + 1)), 15));
            List<Json> first = new ArrayList<>();
            errors.stream().filter(e -> !e.location().equals("generated")).limit(15).forEach(e -> {
                Json x = new Json();
                x.put("file", e.file());
                x.put("line", e.line());
                x.put("message", e.message());
                if (e.symbol() != null) {
                    x.put("symbol", e.symbol());
                }
                first.add(x);
            });
            j.put("firstErrorsOutsideGenerated", first);
            return j;
        }

        static List<Json> top(Stream<String> values, int limit) {
            Map<String, Long> counts = values.collect(Collectors.groupingBy(v -> v, LinkedHashMap::new, Collectors.counting()));
            return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .limit(limit)
                .map(e -> {
                    Json x = new Json();
                    x.put("count", e.getValue());
                    x.put("value", e.getKey());
                    return x;
                })
                .collect(Collectors.toList());
        }
    }

    // ---------------------------------------------------------------- test reports

    static class Tests {
        int total;
        int failed;
        int skipped;
        final List<Json> failures = new ArrayList<>();

        static final Set<String> SKIP = Set.of("src", "node_modules", "gradle", "out", "bin");

        /** JUnit XML reports of every module: build/test-results/* (Gradle), target/surefire-reports and failsafe-reports (Maven). */
        static Tests collect(Path project, Instant notBefore) {
            Tests t = new Tests();
            List<Path> dirs = new ArrayList<>();
            try {
                Files.walkFileTree(project, Set.of(), 12, new java.nio.file.SimpleFileVisitor<>() {
                    @Override
                    public java.nio.file.FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes a) {
                        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                        if (!dir.equals(project) && (name.startsWith(".") || SKIP.contains(name))) {
                            return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                        }
                        if (name.equals("build") || name.equals("target")) {
                            try (Stream<Path> s = Files.list(dir.resolve("test-results"))) {
                                s.filter(Files::isDirectory).forEach(dirs::add);
                            } catch (IOException ignored) {
                                // not a Gradle build directory
                            }
                            dirs.add(dir.resolve("surefire-reports"));
                            dirs.add(dir.resolve("failsafe-reports"));
                            return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                        }
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException ignored) {
                // unreadable tree: report what was found
            }
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                String module = project.relativize(dir).toString().replace('\\', '/').replaceFirst("(^|/)(build|target)/.*$", "");
                try (Stream<Path> files = Files.list(dir)) {
                    for (Path xml : files.filter(f -> f.getFileName().toString().matches("TEST-.*\\.xml")).collect(Collectors.toList())) {
                        if (Files.getLastModifiedTime(xml).toInstant().isBefore(notBefore)) {
                            continue; // stale report from an earlier build
                        }
                        t.modules.add(module.isEmpty() ? "." : module);
                        t.read(xml, module);
                    }
                } catch (Exception ignored) {
                    // unreadable report: skip
                }
            }
            return t;
        }

        final Set<String> modules = new LinkedHashSet<>();

        void read(Path xml, String module) throws Exception {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Element suite = factory.newDocumentBuilder().parse(xml.toFile()).getDocumentElement();
            total += intAttr(suite, "tests");
            skipped += intAttr(suite, "skipped");
            NodeList cases = suite.getElementsByTagName("testcase");
            for (int i = 0; i < cases.getLength(); i++) {
                Element tc = (Element) cases.item(i);
                Element problem = first(tc, "failure");
                if (problem == null) {
                    problem = first(tc, "error");
                }
                if (problem == null) {
                    continue;
                }
                failed++;
                if (failures.size() < 20) {
                    String message = problem.getAttribute("message");
                    if (message.isBlank()) {
                        message = problem.getTextContent().strip().lines().findFirst().orElse("");
                    }
                    message = message.lines().findFirst().orElse("");
                    Json f = new Json();
                    String cls = tc.getAttribute("classname");
                    f.put("test", cls.substring(cls.lastIndexOf('.') + 1) + "." + tc.getAttribute("name"));
                    if (!module.isEmpty()) {
                        f.put("module", module);
                    }
                    f.put("type", problem.getAttribute("type"));
                    f.put("message", message.length() > 300 ? message.substring(0, 300) + "..." : message);
                    failures.add(f);
                }
            }
        }

        static Element first(Element parent, String tag) {
            NodeList list = parent.getElementsByTagName(tag);
            return list.getLength() == 0 ? null : (Element) list.item(0);
        }

        static int intAttr(Element e, String name) {
            String v = e.getAttribute(name);
            return v.isBlank() ? 0 : Integer.parseInt(v);
        }

        Json toJson() {
            Json j = new Json();
            j.put("total", total);
            j.put("failed", failed);
            j.put("skipped", skipped);
            if (modules.size() > 1) {
                j.put("modules", new ArrayList<>(modules));
            }
            if (!failures.isEmpty()) {
                j.put("failures", failures);
            }
            return j;
        }
    }

    // ---------------------------------------------------------------- tiny helpers

    static class Args {
        static Map<String, String> parse(String[] args) {
            Map<String, String> out = new LinkedHashMap<>();
            for (int i = 0; i < args.length; i++) {
                if (!args[i].startsWith("--")) {
                    continue;
                }
                String key = args[i].substring(2);
                boolean hasValue = i + 1 < args.length && !args[i + 1].startsWith("--");
                out.put(key, hasValue ? args[++i] : "true");
            }
            return out;
        }
    }

    /** Minimal ordered JSON object; values: String, Number, Boolean, Json, Map, List. */
    static class Json {
        private final Map<String, Object> values = new LinkedHashMap<>();

        void put(String key, Object value) {
            values.put(key, value);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            write(sb, this);
            return sb.toString();
        }

        static void write(StringBuilder sb, Object v) {
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Json j) {
                write(sb, j.values);
            } else if (v instanceof Map<?, ?> m) {
                sb.append('{');
                boolean firstEntry = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!firstEntry) {
                        sb.append(',');
                    }
                    firstEntry = false;
                    quote(sb, String.valueOf(e.getKey()));
                    sb.append(':');
                    write(sb, e.getValue());
                }
                sb.append('}');
            } else if (v instanceof List<?> l) {
                sb.append('[');
                for (int i = 0; i < l.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    write(sb, l.get(i));
                }
                sb.append(']');
            } else if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else {
                quote(sb, v.toString());
            }
        }

        static void quote(StringBuilder sb, String s) {
            sb.append('"');
            for (char ch : s.toCharArray()) {
                switch (ch) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (ch < 0x20) {
                            sb.append(String.format("\\u%04x", (int) ch));
                        } else {
                            sb.append(ch);
                        }
                    }
                }
            }
            sb.append('"');
        }
    }
}
