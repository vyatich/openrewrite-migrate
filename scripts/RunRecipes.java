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
 *                      [--repos URL,URL] [--plugin-repo URL]
 *                      [--dry-run] [--java-home DIR] [--maven-plugin-version V]
 *                      [--commit "recipe: ..."] [--author "Name &lt;email&gt;"] [--build gradle|maven]
 * </pre>
 *
 * Gradle uses rewrite-init.gradle next to this script (it pins the OpenRewrite Gradle plugin) and
 * runs on the newest installed JDK that the project's wrapper supports. --repos: extra Maven
 * repositories for the recipe artifacts (Gradle; for Maven declare them in the pom or settings.xml).
 * --plugin-repo: where to get the OpenRewrite Gradle plugin itself when Maven Central and the
 * Gradle Plugin Portal are not reachable (a company mirror). Needs JDK 17+ to run.
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
            if (opt.containsKey("repos")) {
                command.add("-Drewrite.recipeRepositories=" + opt.get("repos"));
            }
            // old Gradle versions give the build 512 MB; the recipe run needs more or the daemon hangs
            command.addAll(List.of("-Dorg.gradle.jvmargs=-Xmx2g", "--no-daemon", "--console=plain"));
            // the recipe run is not configuration-cache compatible; the flag exists since Gradle 6.6
            if (gradleAtLeast(gradleVersion, 6, 6)) {
                command.add("--no-configuration-cache");
            }
        } else {
            String version = opt.getOrDefault("maven-plugin-version", MAVEN_PLUGIN_VERSION);
            command.addAll(List.of("-B", "org.openrewrite.maven:rewrite-maven-plugin:" + version + (dryRun ? ":dryRun" : ":run"),
                "-Drewrite.activeRecipes=" + recipes));
            if (opt.containsKey("artifacts")) {
                command.add("-Drewrite.recipeArtifactCoordinates=" + opt.get("artifacts"));
            }
            if (opt.containsKey("repos")) {
                out.put("reposNote", "--repos is ignored for Maven: recipe artifacts resolve through the repositories of the pom"
                    + " and ~/.m2/settings.xml - declare the repository there");
            }
        }
        out.put("command", String.join(" ", command));
        Map<String, String> env = new LinkedHashMap<>();
        if (opt.containsKey("plugin-repo")) {
            env.put("REWRITE_PLUGIN_REPOSITORY", opt.get("plugin-repo")); // read by rewrite-init.gradle
        }

        Path log = Files.createTempFile("run-recipes-", ".log");
        Instant started = Instant.now();
        int exit = run(command, project, jdk[0], env, log);
        List<String> lines = read(log);
        if (!succeeded(lines, exit) && lines.stream().anyMatch(l -> l.contains("SocketException") || l.contains("Connection reset")
                || l.contains("Read timed out") || l.contains("Could not GET"))) {
            out.put("retried", "network error on the first attempt");
            exit = run(command, project, jdk[0], env, log);
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
        List<String> buildFiles = buildFiles(project);
        List<String> buildDiff = new ArrayList<>();
        if (!buildFiles.isEmpty()) {
            List<String> diffArgs = new ArrayList<>(List.of("diff", "-U0", "--no-color", "--relative", "--"));
            diffArgs.addAll(buildFiles);
            String file = null;
            for (String l : git(project, diffArgs.toArray(String[]::new))) {
                if (l.startsWith("+++ ")) {
                    file = l.substring(4).replaceFirst("^b/", "");
                } else if ((l.startsWith("+") || l.startsWith("-")) && !l.startsWith("---") && buildDiff.size() < 60) {
                    buildDiff.add(file + ": " + l);
                }
            }
        }
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

    /**
     * Build files of every module, relative to the project: Gradle scripts in both DSLs (including
     * buildSrc and convention plugins), version catalogs, gradle.properties, Maven poms. Tracked
     * files only, so build output and vendored code are left out.
     */
    static List<String> buildFiles(Path project) throws IOException, InterruptedException {
        return git(project, "ls-files", "--", ".").stream()
            .filter(f -> {
                String name = f.substring(f.lastIndexOf('/') + 1);
                return name.matches("(build|settings)\\.gradle(\\.kts)?|.*\\.gradle(\\.kts)?|gradle\\.properties|.*\\.versions\\.toml|pom\\.xml");
            })
            .filter(f -> Files.exists(project.resolve(f)))
            .collect(Collectors.toList());
    }

    /** Group prefixes whose versions a framework BOM manages, by a marker that the BOM is in use. */
    static final Map<String, List<String>> BOMS = Map.of(
        "org.springframework.boot", List.of("org.springframework", "jakarta.", "com.fasterxml.jackson", "tools.jackson",
            "org.hibernate", "io.micrometer", "org.junit", "org.mockito", "org.assertj", "org.flywaydb", "org.liquibase",
            "ch.qos.logback", "org.slf4j", "org.apache.tomcat", "io.projectreactor", "com.zaxxer", "org.postgresql"),
        "io.quarkus", List.of("io.quarkus", "jakarta.", "org.hibernate", "io.smallrye", "com.fasterxml.jackson", "org.junit"),
        "io.micronaut", List.of("io.micronaut", "jakarta.", "com.fasterxml.jackson", "org.junit"));

    /** Code generators and JDK-sensitive tools: their version decides whether they support the target stack. */
    static final Pattern GENERATOR = Pattern.compile(
        ".*(openapi-generator|org\\.openapi\\.generator|swagger-codegen|jsonschema2pojo|protobuf|jaxb2?-maven-plugin|maven-jaxb2|"
            + "cxf-codegen|com\\.github\\.bjornvester\\.xjc|avro|jooq-codegen|nu\\.studer\\.jooq|wsimport|jaxws-maven-plugin).*");
    static final Pattern JDK_TOOL = Pattern.compile(
        ".*(org\\.projectlombok|io\\.freefair\\.lombok|org\\.mapstruct|jacoco|errorprone|net\\.bytebuddy|kotlin).*");

    /** Things the cleanup step should look at; the agent decides what to do with them. */
    static List<String> cleanupHints(Path project, List<String> buildFiles) throws IOException {
        Map<String, List<String>> contents = new LinkedHashMap<>();
        for (String f : buildFiles) {
            contents.put(f, read(project.resolve(f)));
        }
        // the BOM is usually applied once (root, parent pom, convention plugin) and manages every module
        String all = contents.values().stream().flatMap(List::stream).collect(Collectors.joining("\n"));
        List<String> managed = BOMS.entrySet().stream().filter(e -> all.contains(e.getKey()))
            .flatMap(e -> e.getValue().stream()).distinct().collect(Collectors.toList());

        List<String> hints = new ArrayList<>();
        contents.forEach((f, lines) -> {
            if (f.endsWith("pom.xml")) {
                pomHints(f, String.join("\n", lines), managed, hints);
                return;
            }
            for (String l : lines) {
                String t = l.strip();
                if (t.startsWith("//") || t.startsWith("#")) {
                    continue;
                }
                // "g:a:v" / 'g:a:v', group: 'g', name: 'a', version: 'v', catalog module = "g:a", version = "v"
                Optional<String> group = coordinateGroup(t);
                boolean versioned = t.matches(".*[\"'][^\"':\\s]+:[^\"':\\s]+:[0-9$][^\"']*[\"'].*")
                    || t.matches(".*\\bversion(\\.ref)?\\s*[:=]\\s*[\"'].*");
                if (group.isPresent() && versioned && managed.stream().anyMatch(group.get()::startsWith)) {
                    hints.add(f + ": explicit version on a dependency the framework BOM probably manages - " + t);
                }
                if (group.isPresent() && group.get().startsWith("javax.")) {
                    hints.add(f + ": javax dependency - after a jakarta migration usually not needed - " + t);
                }
                if (GENERATOR.matcher(t).matches() && !t.startsWith("import")) {
                    hints.add(f + ": code generator - check its version supports the target stack natively - " + t);
                } else if (JDK_TOOL.matcher(t).matches() && (versioned || t.matches(".*\\d+\\.\\d+.*")) && !t.startsWith("import")) {
                    hints.add(f + ": tool with JDK limits pinned - check it supports the target JDK - " + t);
                }
                if (f.contains(".gradle") && t.matches(".*(\\$buildDir|\\$\\{buildDir\\}|\\bbuildDir\\b|getBuildDir\\(\\)).*")
                        && !t.contains("buildDirectory")) {
                    hints.add(f + ": deprecated buildDir - use layout.buildDirectory - " + t);
                }
            }
        });
        return hints.stream().distinct().limit(25).collect(Collectors.toList());
    }

    static Optional<String> coordinateGroup(String t) {
        Matcher m = Pattern.compile("[\"']([\\w.-]+):[\\w.-]+(?::[^\"']*)?[\"']").matcher(t);
        if (m.find() && m.group(1).contains(".")) {
            return Optional.of(m.group(1));
        }
        m = Pattern.compile("\\b(?:group|module)\\s*[:=]\\s*[\"']([\\w.-]+)").matcher(t);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /** Dependencies and plugins of a pom: same checks as for Gradle, from the XML elements. */
    static void pomHints(String f, String xml, List<String> managed, List<String> hints) {
        // the BOM's own entries in dependencyManagement carry versions on purpose
        String body = xml.replaceAll("(?s)<dependencyManagement>.*?</dependencyManagement>", "");
        Matcher dep = Pattern.compile("(?s)<(dependency|plugin)>(.*?)</\\1>").matcher(body);
        while (dep.find()) {
            String block = dep.group(2);
            String group = tag(block, "groupId").orElse(dep.group(1).equals("plugin") ? "org.apache.maven.plugins" : "");
            String artifact = tag(block, "artifactId").orElse("");
            Optional<String> version = tag(block, "version");
            String coords = group + ":" + artifact + version.map(v -> ":" + v).orElse("");
            if (dep.group(1).equals("dependency") && version.isPresent() && managed.stream().anyMatch(group::startsWith)) {
                hints.add(f + ": explicit version on a dependency the framework BOM probably manages - " + coords);
            }
            if (group.startsWith("javax.")) {
                hints.add(f + ": javax dependency - after a jakarta migration usually not needed - " + coords);
            }
            if (GENERATOR.matcher(coords).matches()) {
                hints.add(f + ": code generator - check its version supports the target stack natively - " + coords);
            } else if (JDK_TOOL.matcher(coords).matches() && version.isPresent()) {
                hints.add(f + ": tool with JDK limits pinned - check it supports the target JDK - " + coords);
            }
        }
    }

    static Optional<String> tag(String block, String name) {
        Matcher m = Pattern.compile("<" + name + ">\\s*([^<]+?)\\s*</" + name + ">").matcher(block);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
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
        Path props = findUp(project, "gradle/wrapper/gradle-wrapper.properties");
        if (props == null) {
            return null;
        }
        Matcher m = Pattern.compile("gradle-(\\d+(?:\\.\\d+)*)-").matcher(Files.readString(props));
        return m.find() ? m.group(1) : null;
    }

    static boolean gradleAtLeast(String gradle, int major, int minor) {
        if (gradle == null) {
            return false;
        }
        String[] p = gradle.split("\\.");
        int ma = Integer.parseInt(p[0]);
        int mi = p.length > 1 ? Integer.parseInt(p[1]) : 0;
        return ma > major || ma == major && mi >= minor;
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
        for (Path root : roots) {
            // plain layout, macOS bundle, Homebrew formula, scoop "current"
            for (Path candidate : List.of(root, root.resolve("Contents/Home"), root.resolve("libexec/openjdk.jdk/Contents/Home"),
                    root.resolve("current"))) {
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
            Path bat = findUp(project, wrapper + (tool.equals("gradle") ? ".bat" : ".cmd"));
            return List.of("cmd", "/c", bat != null ? bat.toString() : (tool.equals("gradle") ? "gradle" : "mvn"));
        }
        Path script = findUp(project, wrapper);
        if (script != null) {
            return Files.isExecutable(script) ? List.of(script.toString()) : List.of("sh", script.toString());
        }
        return List.of(tool.equals("gradle") ? "gradle" : "mvn");
    }

    static int run(List<String> command, Path dir, String javaHome, Map<String, String> env, Path log)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true)
            .redirectOutput(log.toFile());
        pb.environment().putAll(env);
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
