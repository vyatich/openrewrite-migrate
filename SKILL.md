---
name: openrewrite-migrate
description: Migrates a Java project (Maven or Gradle) to new versions of Spring Boot, Java, Jakarta EE or an internal library with breaking changes. OpenRewrite recipes do the bulk of the work; the agent then fixes only the remaining compile and test errors with minimal patches, without weakening tests, and reports what it changed. Use this skill whenever the user asks to upgrade or migrate Spring Boot, the Java version, javax to jakarta, bump a library to a new major version, apply a migration guide, or repair a build broken by such an upgrade — even if OpenRewrite is not mentioned.
---

# OpenRewrite migration

The migration has two kinds of workers. **Recipes** (OpenRewrite) make the bulk of the changes deterministically and cheaply. **You** fix only the tail the recipes could not handle. The **compiler and the tests** decide whether the result is right, not you and not the user's hopes. Keep that split: every change you make by hand costs review time and can be wrong, every change a recipe makes is reproducible.

Work through the steps in order. Commit after each step so any step can be rolled back and the reviewer can see who did what.

## 1. Look before you touch

Find out, without changing anything:

- **Build tool and wrapper.** `pom.xml` → Maven, `build.gradle(.kts)` / `settings.gradle(.kts)` → Gradle. Use the project's wrapper (`gradlew`, `mvnw`) if there is one; otherwise the tool from PATH. A system Gradle that is much newer than the project usually cannot build it — see `reference/gradle.md`.
- **Current versions:** Spring Boot (plugin or parent), Java (toolchain, `release`, `java.version`), internal libraries and their target versions.
- **Which JDK runs the build today.** An old Gradle refuses to start on a new JDK (Gradle 6.8 runs on JDK ≤ 15). Pick `JAVA_HOME` by the wrapper version — table in `reference/gradle.md`.
- **Migration notes of internal libraries:** `MIGRATION.md`, `CHANGELOG.md`, release notes next to the library sources or in its sources jar. Public recipes know nothing about internal libraries; these notes are your main source there.
- **Git state.** The working tree must be clean — otherwise your changes mix with the user's. Work on a new branch.

Then run the full build with tests **before** migrating and write down the result (green or not, number of tests). If the baseline is already red, stop and tell the user: on a red baseline you cannot tell migration errors from old ones.

## 2. Plan the upgrade path

A target version drags its prerequisites along, and the user rarely names them. "Spring Boot 4" means at least Java 17; "Java 25" means a Gradle that can run on JDK 25 and annotation processors and plugins that understand Java 25 class files. Work the chain out before running anything:

1. **From the targets, derive the prerequisites** — minimum and maximum Java of the framework, the build tool version that runs on the target JDK, plugins and annotation processors with JDK limits (Lombok, MapStruct, code generators, Kotlin, Jacoco, Mockito/ByteBuddy). `reference/compatibility.md` lists the usual chain and where to check the current numbers.
2. **Check what the recipes already cover.** Composite recipes include many steps — `UpgradeSpringBoot_4_0` brings Java to 17 and the Gradle wrapper to 8.14+, but not Java to 25. Read their `recipeList` and mark each prerequisite as "recipe" or "by hand".
3. **Add the missing recipes to the same run** — one pass with several recipes is the normal case; their internal order is handled by OpenRewrite. Run in separate passes only when a later recipe needs the project to build after an earlier one.
4. **If a requested target is impossible** (the framework does not support the requested Java yet, a library has no compatible version), say so before migrating instead of finding out halfway.

Write the plan down in two or three lines — it goes into the report and tells the user what will happen before it happens.

## 3. Run the recipes

Find the recipes and their current versions — `reference/recipe-catalog.md` says where to look (OpenRewrite docs, the recipe YAML on GitHub, Maven Central) and lists the last versions that worked, as a fallback. Run them **without editing the build file** — through the Maven plugin coordinates or a Gradle init script (`reference/maven.md`, `reference/gradle.md`).

Internal library bump: change its version in the build file yourself — this is the one dependency version you are allowed to touch by hand, because no public recipe knows it.

Check that the recipe run really finished (`BUILD SUCCESSFUL` / `BUILD SUCCESS`). A run that died halfway (for example out of memory) can leave the project half-migrated. Then commit:

```
recipe: Spring Boot 2.7 → 4.0, Java 11 → 25 (OpenRewrite)
```

Look through the recipe diff before going on. Recipes sometimes add things a reviewer should know about — explicit versions for dependencies the Spring Boot BOM already manages, compatibility dependencies like old `javax` APIs. Do not "fix" them silently; list them in the report.

## 4. Build and read the errors

Build with tests on the **target** JDK. Never let the build output into your context — a failed build is hundreds of lines (one migration produced 1,100 lines for two errors), and a test report carries full stack traces. Send the output to a log file outside the repository and pull out only what you need with the commands in `reference/gradle.md` / `reference/maven.md`:

- the build verdict and the first lines of "What went wrong";
- compile errors grouped by message and by file — the counts tell you the causes;
- names of failed tests and the first lines of each failure, without stack traces.

Open more of the log only for one specific error you cannot understand from those lines, and only around it.

Group errors by cause. One missing package usually produces a dozen "cannot find symbol" errors; fix the cause, not every line. javac stops at 100 errors — a count of exactly 100 means there are more behind them.

javac reports errors in waves: while a package is missing it does not look deeper. Expect new errors after each fix — that is progress, not a regression, as long as the old ones are gone.

## 5. Fix the tail in a loop

Each iteration: pick a group of errors → make the **minimal** change → build again → commit:

```
llm: corp-commons 2.0 — util → core package, CorpLogger → CorpLog
```

- **Use the real API, not memory.** For a new library version, read its migration notes and the actual signatures (`javap -public -cp <jar> <class>` or the sources jar). New Spring Boot / Spring / Hibernate versions move and rename classes; check before you guess — the framework migration guides on GitHub list most of the moves.
- **Generated code is fixed at its source.** Errors in generated sources (OpenAPI, protobuf, JAXB, MapStruct output under `build/` or `target/`) are not fixed by editing the generated files — they are overwritten on the next build. Change the generator's configuration (for example the option that switches it from `javax` to `jakarta`) or upgrade the generator plugin, and say so in the report.
- **Build scripts are code too.** A new Gradle major removes old DSL (`buildDir`, conventions, some task properties); fix the build script with the same care as Java code, and keep values that pin tool versions (like the wrapper task's `gradleVersion`) in sync with what the recipes changed.
- **Budget:** at most 10 iterations. Stop early if the number of errors has not decreased for two iterations in a row — you are going in circles; report where you are stuck instead of trying random things.
- **Stay on the task.** Do not refactor, reformat or "improve" code that compiles.

### What you must not do, and why

The goal is a migrated project that is as correct as it was before. A green build obtained by weakening the checks is worse than a red build, because it hides the breakage.

- Do not delete tests, add `@Disabled` / `@Ignore`, or comment tests out.
- Do not weaken assertions: no removing `assert`s, no loosening expected values, no catching and swallowing exceptions in tests.
- Change a test only to adapt it to a changed API (a type or method was renamed, a constructor takes `Duration` instead of `long`) and keep what it checks the same. Mention every such change in the report.
- Do not change dependency versions by hand, except the target version of the library being migrated. Versions are the recipes' job; a hand-picked version is invisible in the recipe log and easy to get wrong.
- Do not silence problems: no `@SuppressWarnings` to hide removals, no `-Xlint` tweaks, no skipping tests in the build.

If the only way forward breaks one of these rules, stop and ask the user.

## 6. Turn repeated fixes into a recipe

If you made the same kind of fix three or more times (the same package rename, type rename, method rename), propose a declarative recipe in `rewrite.yml` at the project root, so the next service with the same library is migrated without you. How to write and check it: `reference/recipe-authoring.md`.

A fix that is not a pure rename (for example `long` milliseconds → `Duration`) does not fit a declarative recipe. Do not write a complex recipe yourself; put two or three before/after examples into the report — they are a ready task for whoever writes the recipe.

## 7. Report

Finish with a report for the reviewer:

```
## Migration report

**Result:** build green / red, tests N passed of M (baseline: M)
**Versions:** Spring Boot A → B, Java X → Y, <library> P → Q, Gradle/Maven wrapper R → S

### Upgrade plan
- <target> → requires <prerequisite> — covered by <recipe> / done by hand

### Done by recipes
- <recipe> — <what it changed, in one line>

### Fixed by hand (commits `llm:`)
- <file>: <what and why>

### Tests changed to follow an API change
- <test>: <old call> → <new call>; the check itself is unchanged

### For the reviewer
- <things recipes added that deserve a look: explicit versions, compatibility dependencies>
- <anything left unfinished and why>

### Recipe candidates
- <proposed rewrite.yml recipe, or before/after examples for a non-declarative change>
```
