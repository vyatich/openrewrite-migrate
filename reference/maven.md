# Maven projects

> The scripts in `scripts/` (`BuildReport.java`, `RunRecipes.java`) do all of this in one call each. Read this file only if a script cannot run, or to understand what it does.

## Run recipes without editing the pom

```
./mvnw -B org.openrewrite.maven:rewrite-maven-plugin:6.46.1:run \
  -Drewrite.activeRecipes=<recipe>,<recipe> \
  -Drewrite.recipeArtifactCoordinates=<group:artifact:version>,<group:artifact:version>
```

(`mvn` instead of `./mvnw` if the project has no wrapper.) Use `:dryRun` instead of `:run` to get a patch in `target/rewrite/rewrite.patch` without changing files. Check the output for `BUILD SUCCESS`. Your own `rewrite.yml` in the project root is picked up automatically; another location: `-Drewrite.configLocation=<path>`.

Run it from the reactor root (the parent `pom.xml`): the plugin then migrates every module in one pass. Recipe artifacts resolve through the repositories of the pom and `~/.m2/settings.xml` (mirrors included); Maven has no command-line switch to add a repository, so put an internal one there.

## Which JDK

Maven 3.9 runs on any JDK from 8 to 25, so run it on the target JDK. If the project must still be compiled and tested on its old JDK (an old Spring Boot does not run its tests on a new JVM), use Maven toolchains: `maven-toolchains-plugin` in the pom and the JDKs listed in `~/.m2/toolchains.xml`. After the Java recipe changes `java.version`, the toolchain switches to the new JDK by itself.

## Build and errors

Send the output to a file outside the repository and read only extracts (checked on a real log: 1,102 lines → the verdict and two errors):

```bash
LOG="${TMPDIR:-/tmp}/migrate-build.log"
# -fae: modules that do not depend on a failed one still build
./mvnw -B -fae verify > "$LOG" 2>&1; echo "exit=$?"

# 1. Verdict
grep -E "^\[INFO\] BUILD (SUCCESS|FAILURE)" "$LOG"

# 2. Compile errors (Maven prints them twice — sort -u), where, by message, by missing symbol
grep -E "^\[ERROR\] .*\.java:\[[0-9]+,[0-9]+\]" "$LOG" | sort -u > "${TMPDIR:-/tmp}/migrate-errors.log"
wc -l < "${TMPDIR:-/tmp}/migrate-errors.log"
awk '/[\\\/](build|target)[\\\/]generated/{g++; next} /[\\\/]src[\\\/]test/{t++; next} {m++} END{print "generated", g+0, "| main", m+0, "| test", t+0}' "${TMPDIR:-/tmp}/migrate-errors.log"
grep -oE "error: .*" "${TMPDIR:-/tmp}/migrate-errors.log" | sort | uniq -c | sort -rn | head -15
grep -A2 "cannot find symbol" "$LOG" | grep -oE "symbol: +.*" | tr -s ' ' | sort | uniq -c | sort -rn | head -15

# Kotlin (kotlin-maven-plugin): "[ERROR] /x/A.kt: (12, 5) message" or "[ERROR] file:///x/A.kt:12:5 message"
grep -E "^\[ERROR\] .*\.kt" "$LOG" | sort -u | head -20

# 3. Failed tests: summary lines and the first line of each failure
grep -E "Tests run:.*(Failures: [1-9]|Errors: [1-9])|<<< (FAILURE|ERROR)!" "$LOG" | head -20
# totals over every module
find . -path '*/target/*-reports/*' -name 'TEST-*.xml' | xargs grep -ohE 'tests="[0-9]+"|failures="[0-9]+"|errors="[0-9]+"' \
  | awk -F'"' '{s[$1]+=$2} END{for (k in s) printf "%s%d ", k, s[k]; print ""}'
```

How to read the extracts: see the same section in `gradle.md`.

- One test class only: `-Dtest=SomeServiceTest -Dsurefire.failIfNoSpecifiedTests=false` (the second flag keeps modules without that test from failing; Surefire before 2.12 calls it `-DfailIfNoTests=false`). Add `-pl <module> -am` to build only that module and what it needs.
- Versions to watch: every `pom.xml` (dependencies, `dependencyManagement`, properties, parent), the parent pom first — modules usually inherit their versions from it. `RunRecipes.java` lists all of them in `buildFileDiff`.
