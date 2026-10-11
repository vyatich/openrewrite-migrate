# Maven projects

## Run recipes without editing the pom

```
./mvnw -B org.openrewrite.maven:rewrite-maven-plugin:6.46.1:run \
  -Drewrite.activeRecipes=<recipe>,<recipe> \
  -Drewrite.recipeArtifactCoordinates=<group:artifact:version>,<group:artifact:version>
```

(`mvn` instead of `./mvnw` if the project has no wrapper.) Use `:dryRun` instead of `:run` to get a patch in `target/rewrite/rewrite.patch` without changing files. Check the output for `BUILD SUCCESS`. Your own `rewrite.yml` in the project root is picked up automatically; another location: `-Drewrite.configLocation=<path>`.

## Which JDK

Maven 3.9 runs on any JDK from 8 to 25, so run it on the target JDK. If the project must still be compiled and tested on its old JDK (an old Spring Boot does not run its tests on a new JVM), use Maven toolchains: `maven-toolchains-plugin` in the pom and the JDKs listed in `~/.m2/toolchains.xml`. After the Java recipe changes `java.version`, the toolchain switches to the new JDK by itself.

## Build and errors

Send the output to a file outside the repository and read only extracts (checked on a real log: 1,102 lines → the verdict and two errors):

```bash
LOG="${TMPDIR:-/tmp}/migrate-build.log"
./mvnw -B verify > "$LOG" 2>&1; echo "exit=$?"

# 1. Verdict
grep -E "^\[INFO\] BUILD (SUCCESS|FAILURE)" "$LOG"

# 2. Compile errors (Maven prints them twice — sort -u), where, by message, by missing symbol
grep -E "^\[ERROR\] .*\.java:\[[0-9]+,[0-9]+\]" "$LOG" | sort -u > "${TMPDIR:-/tmp}/migrate-errors.log"
wc -l < "${TMPDIR:-/tmp}/migrate-errors.log"
awk '/[\\\/](build|target)[\\\/]generated/{g++; next} /[\\\/]src[\\\/]test/{t++; next} {m++} END{print "generated", g+0, "| main", m+0, "| test", t+0}' "${TMPDIR:-/tmp}/migrate-errors.log"
grep -oE "error: .*" "${TMPDIR:-/tmp}/migrate-errors.log" | sort | uniq -c | sort -rn | head -15
grep -A2 "cannot find symbol" "$LOG" | grep -oE "symbol: +.*" | tr -s ' ' | sort | uniq -c | sort -rn | head -15

# 3. Failed tests: summary lines and the first line of each failure
grep -E "Tests run:.*(Failures: [1-9]|Errors: [1-9])|<<< (FAILURE|ERROR)!" "$LOG" | head -20
```

How to read the extracts: see the same section in `gradle.md`.

- One test class only: `-Dtest=OrderServiceTest`.
- Versions to watch: `pom.xml` (dependencies, `dependencyManagement`, properties, parent).
