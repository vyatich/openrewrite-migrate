import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
 * Runs OpenRewrite recipes on a Maven or Gradle project without editing its build files, checks
 * that the run really finished, and prints a compact JSON summary: which recipes changed what, the
 * build-file diff, and hints for the cleanup step. Optionally commits the result.
 *
 * <pre>
 * java RunRecipes.java --recipes R1,R2 [--artifacts g:a:v,g:a:v] [--project DIR]
 *                      [--dry-run] [--java-home DIR] [--maven-plugin-version V]
 *                      [--commit "recipe: ..."] [--author "Name &lt;email&gt;"] [--build gradle|maven]
 * </pre>
 *
 * Gradle uses rewrite-init.gradle next to this script (it pins the OpenRewrite Gradle plugin) and
 * runs on the newest installed JDK that the project's wrapper supports. Needs JDK 17+ to run.
 */
public class RunRecipes {

    static final String MAVEN_PLUGIN_VERSION = "6.46.1";

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parse(args);
        Path project = Path.of(opt.getOrDefault("project", ".")).toAbsolutePath().normalize();
        String recipes = opt.get("recipes");
        Map<String, Object> out = new LinkedHashMap<>();
        if (recipes == null) {
            out.put("verdict", "ERROR");
            out.put("error", "--recipes is required");
            print(out);
            System.exit(2);
        }
        String tool = detectTool(project, opt.get("build"));
        boolean dryRun = opt.containsKey("dry-run");
        out.put("tool", tool);
        out.put("recipes", List.of(recipes.split(",")));

        String gradleVersion = tool.equals("gradle") ? gradleVersion(project) : null;
        String[] jdk = chooseJdk(opt.get("java-home"), gradleVersion);
        out.put("javaHome", jdk[0]);
        if (jdk[1] != null) {
            out.put("jdkNote", jdk[1]);
        }

        List<String> command = new ArrayList<>(launcher(project, tool));
        if (tool.equals("gradle")) {
            Path initScript = Path.of(System.getProperty("jdk.launcher.sourcefile", "RunRecipes.java"))
                .toAbsolutePath().getParent().resolve("rewrite-init.gradle");
            command.add(dryRun ? "rewriteDryRun" : "rewriteRun");
            command.addAll(List.of("--init-script", initScript.toString(), "-Drewrite.activeRecipe=" + recipes));
            if (opt.containsKey("artifacts")) {
                command.add("-Drewrite.recipeArtifactCoordinates=" + opt.get("artifacts"));
            }
            // old Gradle versions give the build 512 MB; the recipe run needs more or the daemon hangs
            command.addAll(List.of("-Dorg.gradle.jvmargs=-Xmx2g", "--no-daemon", "--console=plain"));
        } else {
            String version = opt.getOrDefault("maven-plugin-version", MAVEN_PLUGIN_VERSION);
            command.addAll(List.of("-B", "org.openrewrite.maven:rewrite-maven-plugin:" + version + (dryRun ? ":dryRun" : ":run"),
                "-Drewrite.activeRecipes=" + recipes));
            if (opt.containsKey("artifacts")) {
                command.add("-Drewrite.recipeArtifactCoordinates=" + opt.get("artifacts"));
            }
        }
        out.put("command", String.join(" ", command));

        Path log = Files.createTempFile("run-recipes-", ".log");
        Instant started = Instant.now();
        int exit = run(command, project, jdk[0], log);
        List<String> lines = read(log);
        if (!succeeded(lines, exit) && lines.stream().anyMatch(l -> l.contains("SocketException") || l.contains("Connection reset")
                || l.contains("Read timed out") || l.contains("Could not GET"))) {
            out.put("retried", "network error on the first attempt");
            exit = run(command, project, jdk[0], log);
            lines = read(log);
        }
        // the build tool's own verdict decides: old gradlew.bat versions can exit non-zero after BUILD SUCCESSFUL
        boolean finished = succeeded(lines, exit);
        out.put("verdict", finished ? "SUCCESS" : "FAILED");
        out.put("exitCode", exit);
        out.put("seconds", Duration.between(started, Instant.now()).toSeconds());
        out.put("log", log.toString());
        if (!finished) {
            out.put("whatWentWrong", whatWentWrong(lines));
            out.put("hint", "nothing is committed; check the log around the error - a run that dies halfway can leave"
                + " the project half-migrated: git status / git checkout before retrying");
            print(out);
            System.exit(1);
        }
        lines.stream().filter(l -> l.startsWith("Estimate time saved")).findFirst().ifPresent(l -> out.put("estimate", l));

        out.put("filesWhereRecipeAppears", changesByRecipe(lines, recipes));
        if (dryRun) {
            Path patch = project.resolve(tool.equals("gradle") ? "build/reports/rewrite/rewrite.patch" : "target/rewrite/rewrite.patch");
            out.put("patch", patch.toString());
            if (Files.exists(patch)) {
                out.put("patchFiles", read(patch).stream().filter(l -> l.startsWith("diff --git")).count());
            }
            print(out);
            return;
        }

        List<String> changed = git(project, "status", "--porcelain", "--", ".");
        out.put("changedFiles", changed.size());
        out.put("files", changed.stream().map(l -> l.substring(3)).limit(40).collect(Collectors.toList()));
        List<String> buildFiles = Stream.of("build.gradle.kts", "build.gradle", "settings.gradle.kts", "settings.gradle",
                "gradle/libs.versions.toml", "gradle.properties", "pom.xml")
            .filter(f -> Files.exists(project.resolve(f))).collect(Collectors.toList());
        List<String> diffArgs = new ArrayList<>(List.of("diff", "-U0", "--"));
        diffArgs.addAll(buildFiles);
        List<String> buildDiff = git(project, diffArgs.toArray(String[]::new)).stream()
            .filter(l -> (l.startsWith("+") || l.startsWith("-")) && !l.startsWith("+++") && !l.startsWith("---"))
            .limit(40).collect(Collectors.toList());
        out.put("buildFileDiff", buildDiff);
        out.put("cleanupHints", cleanupHints(project, buildFiles));

        if (opt.containsKey("commit")) {
            git(project, "add", "-A", "--", ".");
            List<String> commit = new ArrayList<>();
            Optional.ofNullable(opt.get("author")).map(a -> Pattern.compile("^(.*?)\\s*<(.+)>$").matcher(a))
                .filter(Matcher::matches).ifPresent(m -> commit.addAll(List.of("-c", "user.name=" + m.group(1),
                    "-c", "user.email=" + m.group(2))));
            commit.addAll(List.of("commit", "-q", "-m", opt.get("commit")));
            git(project, commit.toArray(String[]::new));
            out.put("commit", git(project, "log", "-1", "--format=%h %s").stream().findFirst().orElse("?"));
        }
        print(out);
    }

    static boolean succeeded(List<String> lines, int exit) {
        boolean success = lines.stream().anyMatch(l -> l.startsWith("BUILD SUCCESSFUL") || l.contains("] BUILD SUCCESS"));
        boolean failure = lines.stream().anyMatch(l -> l.startsWith("BUILD FAILED") || l.contains("] BUILD FAILURE"));
        return success && !failure;
    }

    /**
     * For each requested recipe, how many files it changed: the "Changes have been made to X by:"
     * block of a file lists the recipe tree that touched it. A requested recipe that changed nothing
     * (zero) usually means a wrong name, a missing artifact or a precondition that did not match.
     */
    static Map<String, Integer> changesByRecipe(List<String> lines, String recipes) {
        // the plugin may print a file more than once and lists the recipe tree under it: count distinct files
        Map<String, Set<String>> files = new LinkedHashMap<>();
        for (String recipe : recipes.split(",")) {
            files.put(recipe.strip(), new LinkedHashSet<>());
        }
        Pattern header = Pattern.compile("^(?:Changes have been made to (.+?) by|These recipes would make changes to (.+?)):$");
        String file = null;
        StringBuilder block = new StringBuilder();
        List<String> all = new ArrayList<>(lines);
        all.add("<end>");
        for (String raw : all) {
            String l = raw.replaceFirst("^\\[WARNING\\] ", "");
            Matcher m = header.matcher(l);
            if (m.matches() || (file != null && !l.startsWith("    "))) {
                if (file != null) {
                    String text = block.toString();
                    String current = file;
                    files.forEach((recipe, set) -> {
                        if (text.contains(recipe)) {
                            set.add(current);
                        }
                    });
                }
                file = m.matches() ? (m.group(1) != null ? m.group(1) : m.group(2)) : null;
                block.setLength(0);
                continue;
            }
            if (file != null) {
                block.append(l).append('\n');
            }
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        files.forEach((recipe, set) -> counts.put(recipe, set.size()));
        return counts;
    }

    /** Things the cleanup step should look at; the agent decides what to do with them. */
    static List<String> cleanupHints(Path project, List<String> buildFiles) throws IOException {
        List<String> hints = new ArrayList<>();
        for (String f : buildFiles) {
            List<String> lines = read(project.resolve(f));
            boolean bootPlugin = lines.stream().anyMatch(l -> l.contains("org.springframework.boot"));
            for (String l : lines) {
                String t = l.strip();
                if (bootPlugin && t.matches(".*\"(org\\.springframework[^\":]*|jakarta\\.[^\":]*):[^\":]+:[0-9][^\"]*\".*")) {
                    hints.add(f + ": explicit version on a BOM-managed dependency - " + t);
                }
                if (t.matches(".*\"javax\\.[^\":]+:[^\":]+:.*")) {
                    hints.add(f + ": javax dependency after a jakarta migration - " + t);
                }
                if (t.matches(".*id\\(\"org\\.openapi\\.generator\"\\).*version.*") || t.contains("openapi-generator-maven-plugin")) {
                    hints.add(f + ": code generator plugin - check it supports the target stack natively - " + t);
                }
                if (t.matches(".*(\\$buildDir|\\bbuildDir\\b).*") && !t.contains("buildDirectory")) {
                    hints.add(f + ": deprecated buildDir - use layout.buildDirectory - " + t);
                }
            }
        }
        return hints.stream().distinct().limit(15).collect(Collectors.toList());
    }

    // ---------------------------------------------------------------- shared with BuildReport (single-file scripts)

    static String detectTool(Path project, String forced) {
        if (forced != null) {
            return forced;
        }
        return Stream.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")
            .anyMatch(f -> Files.exists(project.resolve(f))) ? "gradle" : "maven";
    }

    static String gradleVersion(Path project) throws IOException {
        Path props = project.resolve("gradle/wrapper/gradle-wrapper.properties");
        if (!Files.exists(props)) {
            return null;
        }
        Matcher m = Pattern.compile("gradle-(\\d+(?:\\.\\d+)*)-").matcher(Files.readString(props));
        return m.find() ? m.group(1) : null;
    }

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

    static String[] chooseJdk(String forced, String gradleVersion) {
        if (forced != null) {
            return new String[] {forced, null};
        }
        int max = maxJdkFor(gradleVersion);
        Map<Integer, String> installed = new LinkedHashMap<>();
        Set<Path> roots = new LinkedHashSet<>();
        String home = System.getProperty("user.home");
        for (String dir : List.of("C:/Program Files/Eclipse Adoptium", "C:/Program Files/Java", "C:/Program Files/Microsoft",
                "C:/Program Files/Amazon Corretto", "C:/Program Files/BellSoft", "C:/Program Files/Zulu",
                "/usr/lib/jvm", "/Library/Java/JavaVirtualMachines", home + "/.sdkman/candidates/java",
                home + "/.gradle/jdks", home + "/.jdks")) {
            Path root = Path.of(dir);
            if (Files.isDirectory(root)) {
                try (Stream<Path> children = Files.list(root)) {
                    children.forEach(roots::add);
                } catch (IOException ignored) {
                    // unreadable directory: skip
                }
            }
        }
        Optional.ofNullable(System.getenv("JAVA_HOME")).map(Path::of).ifPresent(roots::add);
        roots.add(Path.of(System.getProperty("java.home")));
        for (Path root : roots) {
            for (Path candidate : List.of(root, root.resolve("Contents/Home"))) {
                Path release = candidate.resolve("release");
                if (!Files.isRegularFile(release)) {
                    continue;
                }
                try {
                    Matcher m = Pattern.compile("JAVA_VERSION=\"(\\d+)(?:\\.(\\d+))?").matcher(Files.readString(release));
                    if (m.find()) {
                        int major = Integer.parseInt(m.group(1));
                        installed.putIfAbsent(major == 1 && m.group(2) != null ? Integer.parseInt(m.group(2)) : major,
                            candidate.toString());
                    }
                } catch (IOException ignored) {
                    // unreadable release file: skip
                }
            }
        }
        Optional<Map.Entry<Integer, String>> best = installed.entrySet().stream()
            .filter(e -> e.getKey() <= max).max(Map.Entry.comparingByKey());
        if (best.isPresent()) {
            return new String[] {best.get().getValue(), gradleVersion == null ? null
                : "Gradle " + gradleVersion + " runs on JDK <= " + max + "; picked JDK " + best.get().getKey()};
        }
        String current = Optional.ofNullable(System.getenv("JAVA_HOME")).orElse(System.getProperty("java.home"));
        return new String[] {current, "no installed JDK <= " + max + " for Gradle " + gradleVersion + "; pass --java-home"};
    }

    static boolean windows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    static List<String> launcher(Path project, String tool) {
        String wrapper = tool.equals("gradle") ? "gradlew" : "mvnw";
        if (windows()) {
            Path bat = project.resolve(wrapper + (tool.equals("gradle") ? ".bat" : ".cmd"));
            return List.of("cmd", "/c", Files.exists(bat) ? bat.toString() : (tool.equals("gradle") ? "gradle" : "mvn"));
        }
        Path script = project.resolve(wrapper);
        if (Files.exists(script)) {
            return Files.isExecutable(script) ? List.of(script.toString()) : List.of("sh", script.toString());
        }
        return List.of(tool.equals("gradle") ? "gradle" : "mvn");
    }

    static int run(List<String> command, Path dir, String javaHome, Path log) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true)
            .redirectOutput(log.toFile());
        if (javaHome != null) {
            pb.environment().put("JAVA_HOME", javaHome);
            String key = pb.environment().containsKey("Path") ? "Path" : "PATH";
            pb.environment().put(key, javaHome + File.separator + "bin" + File.pathSeparator + pb.environment().getOrDefault(key, ""));
        }
        return pb.start().waitFor();
    }

    static List<String> git(Path dir, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        List<String> lines = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines()
            .filter(l -> !l.startsWith("warning: in the working copy")).collect(Collectors.toList());
        p.waitFor();
        return lines;
    }

    static List<String> read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8).lines().collect(Collectors.toList());
    }

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
            if (in && !t.isEmpty() && (out.isEmpty() || t.startsWith(">"))) {
                out.add(t);
            }
        }
        if (out.isEmpty()) {
            lines.stream().map(String::strip).filter(l -> l.startsWith("[ERROR]")).limit(6).forEach(out::add);
        }
        return out.stream().limit(6).collect(Collectors.toList());
    }

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

    static void print(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        write(sb, map);
        System.out.println(sb);
    }

    static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
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
