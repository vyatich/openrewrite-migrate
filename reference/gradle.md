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

```
./gradlew build --no-daemon --console=plain
```

- After the recipes the wrapper is new (for example 9.1) — run it on the target JDK.
- Compile errors: lines containing `error:`.
- Test results: `build/test-results/test/TEST-*.xml` (JUnit XML: `<testcase>` with `<failure>` or `<error>`).
- One test class only: `./gradlew test --tests 'demo.shop.order.OrderServiceTest'`.

## Where versions live

When checking that dependency versions were not changed by hand, look at all of them: `build.gradle(.kts)`, `gradle/libs.versions.toml`, `gradle.properties`, `settings.gradle(.kts)` (plugin management).
