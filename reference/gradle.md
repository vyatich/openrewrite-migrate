# Gradle projects

## Which JDK starts which Gradle

Gradle refuses to start on a JDK newer than it knows. Pick `JAVA_HOME` by the version in `gradle/wrapper/gradle-wrapper.properties`:

| Gradle | Newest JDK it runs on |
| --- | --- |
| 6.7 – 6.9 | 15 |
| 7.0 – 7.2 | 16 |
| 7.3 – 7.4 | 17 |
| 7.6 | 19 |
| 8.5 | 21 |
| 8.10 | 23 |
| 8.14 | 24 |
| 9.1+ | 25 |

The JDK that **compiles and tests** the code is a different thing: it comes from the Gradle toolchain (`java { toolchain { languageVersion ... } }`) and Gradle finds installed JDKs by itself. So the old wrapper may run on JDK 11 while the code is compiled for 11, and after the migration the new wrapper runs on JDK 25 and compiles for 25.

No wrapper in the project? A system Gradle several majors newer than the project usually fails (Spring Boot 2.7 plugins do not work on Gradle 9). Generate a wrapper of a compatible version in an empty directory (`gradle wrapper --gradle-version <v>`), copy it in, and say so in the report.

## Run recipes without editing the build

`scripts/rewrite-init.gradle` applies the OpenRewrite plugin through an init script, so `build.gradle(.kts)` stays untouched:

```
./gradlew rewriteRun --init-script <skill>/scripts/rewrite-init.gradle \
  -Drewrite.activeRecipe=<recipe>,<recipe> \
  -Drewrite.recipeArtifactCoordinates=<group:artifact:version>,<group:artifact:version> \
  -Dorg.gradle.jvmargs=-Xmx2g --no-daemon
```

- `-Dorg.gradle.jvmargs=-Xmx2g` is needed: old Gradle versions give the build 512 MB, the recipe run dies with `OutOfMemoryError` and the daemon hangs (kill leftover `java` processes of the run if that happens).
- `--no-daemon`: one clean JVM per run; nothing is left hanging between steps.
- Dry run first if you want to see the diff without changing files: `rewriteDryRun` instead of `rewriteRun`; the patch is written to `build/reports/rewrite/rewrite.patch`.
- Check the end of the output for `BUILD SUCCESSFUL`. The recipe that upgrades the wrapper downloads the list of Gradle versions during the run; a network error there fails the whole run — just retry.
- Your own `rewrite.yml` in the project root is picked up automatically.

## Build and errors

After the recipes the wrapper is new (for example 9.1) — run it on the target JDK. Send the output to a file outside the repository and read only extracts of it. Commands for Git Bash / any POSIX shell (checked on real logs: 1,100 lines of output reduce to about 20):

```bash
LOG="${TMPDIR:-/tmp}/migrate-build.log"
./gradlew build --no-daemon --console=plain > "$LOG" 2>&1; echo "exit=$?"

# 1. Verdict and the reason
grep -E "^BUILD (SUCCESSFUL|FAILED)" "$LOG"
grep -A4 "^\* What went wrong" "$LOG" | head -6

# Gradle prints compiler output twice; keep only the first copy
ERRS="${TMPDIR:-/tmp}/migrate-errors.log"
sed '/^\* What went wrong/,$d' "$LOG" > "$ERRS"

# 2. Compile errors: total (javac stops at 100), where, by message, by missing symbol
grep -E "^[0-9]+ errors?$" "$ERRS"
grep ": error:" "$ERRS" | awk '/[\\\/](build|target)[\\\/]generated/{g++; next} /[\\\/]src[\\\/]test/{t++; next} {m++} END{print "generated", g+0, "| main", m+0, "| test", t+0}'
grep -oE "error: .*" "$ERRS" | sort | uniq -c | sort -rn | head -15
grep -A3 ": error: cannot find symbol" "$ERRS" | grep -oE "symbol: +.*" | tr -s ' ' | sort | uniq -c | sort -rn | head -15

# 3. One concrete error with its source line, when you need it
grep -A3 ": error:" "$ERRS" | grep -A3 "DeliveryClient.java" | head -8

# 4. Failed tests: names and the first line of each failure, no stack traces
grep -A1 -E " FAILED$" "$LOG" | grep -vE "^--$|Task :" | head -30
grep -ohE 'tests="[0-9]+"|failures="[0-9]+"|errors="[0-9]+"|skipped="[0-9]+"' build/test-results/test/TEST-*.xml \
  | awk -F'"' '{s[$1]+=$2} END{for (k in s) printf "%s%d ", k, s[k]; print ""}'
```

Reading the extracts:

- **Most errors in `generated`** — the cause is the generator configuration or version, not the code (see "Generated code is fixed at its source" in SKILL.md).
- **"package X does not exist" + "cannot find symbol: class Y"** for one library — a package or type was renamed; the library's migration notes or `javap` on the new jar tell you the new name.
- **Exactly 100 errors** — javac stopped counting; expect another wave after the first fix.

PowerShell equivalents, if there is no POSIX shell:

```powershell
$log = "$env:TEMP\migrate-build.log"
.\gradlew.bat build --no-daemon --console=plain *> $log
Select-String -Path $log -Pattern '^BUILD (SUCCESSFUL|FAILED)'
$cut = (Select-String -Path $log -Pattern '^\* What went wrong' | Select-Object -First 1).LineNumber
$errs = if ($cut) { Get-Content $log -TotalCount ($cut - 1) } else { Get-Content $log }
$errs | Select-String ': error: (.*)' | ForEach-Object { $_.Matches[0].Groups[1].Value } | Group-Object | Sort-Object Count -Descending | Select-Object -First 15 Count, Name
Select-String -Path $log -Pattern ' FAILED$' -Context 0,1 | Select-Object -First 30
```

One test class only: `./gradlew test --tests 'demo.shop.order.OrderServiceTest'` — cheaper than the full build while you fix one failing test.

## Cleaning up after the recipes

Which version wins for a dependency, and why (use the configuration where the dependency lives — `runtimeClasspath`, `testRuntimeClasspath`):

```bash
./gradlew dependencyInsight --dependency spring-boot-starter-webmvc-test --configuration testRuntimeClasspath --console=plain \
  | grep -E "^[a-z].*:.*:|selected|requested|By constraint|By conflict" | head -15
```

An explicit version shows up as `requested` next to the module. After removing it, the version should be chosen by the BOM: "selected by rule" with the `io.spring.dependency-management` plugin, "By constraint" with a Gradle `platform(...)`.

Deprecated build DSL that the next Gradle major will remove. The Kotlin DSL reports it while compiling the build script — that happens in the first build after the build file changed (the recipe commit), so look in that build's log:

```bash
grep -E "^w: .*\.gradle(\.kts)?:[0-9]+:[0-9]+: .*deprecated" "$LOG" | sed -E 's|.*[/\\]||' | sort -u
# example: build.gradle.kts:49:21: 'var buildDir: File' is deprecated. Deprecated in Java.
```

Deprecated Gradle features used at run time (plugins, task APIs): `./gradlew help --warning-mode all --console=plain`, then grep the output for `deprecat`.

Typical fix: `"$buildDir/x"` → `layout.buildDirectory.dir("x")` (a `Provider`; plugin properties of type `DirectoryProperty` accept it directly, plain strings need `.get().asFile.path`).

## Where versions live

When checking that dependency versions were not changed by hand, look at all of them: `build.gradle(.kts)`, `gradle/libs.versions.toml`, `gradle.properties`, `settings.gradle(.kts)` (plugin management).
