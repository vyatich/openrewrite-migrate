---
name: openrewrite-migrate
description: Migrates a Java project (Maven or Gradle) to new versions of Spring Boot, Java, Jakarta EE or an internal library with breaking changes. OpenRewrite recipes do the bulk of the work; the agent then fixes only the remaining compile and test errors with minimal patches, without weakening tests, and reports what it changed. Use this skill whenever the user asks to upgrade or migrate Spring Boot, the Java version, javax to jakarta, bump a library to a new major version, apply a migration guide, or repair a build broken by such an upgrade — even if OpenRewrite is not mentioned.
---

# OpenRewrite migration

**Recipes** (OpenRewrite) make the bulk of the changes — deterministic, cheap, reproducible. **You** fix only the tail they leave. The **compiler and the tests** decide whether the result is right. Every line you write by hand costs review time; every line a recipe writes does not.

Three scripts in `scripts/` do the mechanical work in one call each and answer with short JSON — use them instead of running the build tool yourself and reading its log (a failed build is hundreds of lines; the JSON is a few dozen):

| Script | Does |
| --- | --- |
| `java <skill>/scripts/BuildReport.java --project <dir> [--task compile\|build\|test] [--tests P]` | builds; verdict, compile errors grouped by cause, failed tests without stack traces, build-script deprecations |
| `java <skill>/scripts/RunRecipes.java --project <dir> --recipes R1,R2 [--artifacts g:a:v,…] [--commit "…"] [--dry-run]` | runs recipes without editing the build, checks the run finished, lists changed files and cleanup hints, commits |
| `java <skill>/scripts/CheckDiff.java --install-hook` | installs a pre-commit hook that refuses commits weakening tests |

They need JDK 17+ to start, detect Maven or Gradle and the wrapper themselves, and run Gradle on the newest installed JDK its version supports (an old wrapper cannot start on a new JDK). Override with `--java-home`.

## 1. Baseline

1. The working tree must be clean; create a branch. Install the guard: `CheckDiff.java --install-hook`.
2. `BuildReport.java --project <dir>` — the build must be green before you change anything; note the test count. If it is red, stop and tell the user: on a red baseline migration errors cannot be told from old ones.
3. Read the build file for current versions, and the migration notes (`MIGRATION.md`, changelog) of every internal library you upgrade — they say what breaks and name the recipe the library team published.

## 2. Plan — two to four lines, before anything runs

- **Prerequisites of the targets.** Spring Boot 4 needs Java 17+; Java 25 needs a Gradle that runs on it; annotation processors, code generators and bytecode tools need versions that know the target. Read `reference/compatibility.md` only if the project uses a tool you are not sure about.
- **Recipes that cover them.** `UpgradeSpringBoot_4_0` brings Java to 17 and the Gradle wrapper along, but not Java to 25 — add `UpgradeToJava25`. Recipe names and the versions that work: `reference/recipe-catalog.md`. Look versions up online only if those fail or the user asked for the newest.
- **Internal recipes.** From the library's migration notes or `reference/internal-recipes.md`. Connect them, never write them.
- **Exact landing versions.** A recipe targets one line (4.0.x); if a newer line exists without a recipe, say so and stay on the recipe's line unless the user asked otherwise.
- If a target is impossible, say so now.

## 3. Recipes — one run, one commit

```
RunRecipes.java --project <dir> --recipes <public and internal recipes> --artifacts <their artifacts> --commit "recipe: <from> → <to> (OpenRewrite)"
```

Check `verdict` is `SUCCESS`. A requested recipe with `0` in `filesWhereRecipeAppears` usually means a wrong name or a missing artifact.

## 4. Clean up after the recipes — one commit

Work through `cleanupHints`, then commit as `llm: clean up after recipes — …`:

- explicit versions on dependencies the BOM manages → remove the version;
- dependencies the code does not need (old `javax` APIs, a servlet API pinned below the BOM) → remove if the build stays green;
- plugins and code generators the recipes left behind → raise to the release that supports the target natively (openapi-generator 7.x: `useSpringBoot4`, `useJackson3`) rather than a compatibility switch of the old one;
- build-script deprecations (`$buildDir` → `layout.buildDirectory`; `BuildReport` lists the rest under `buildScriptDeprecations` in the first build).

## 5. Fix the tail in a loop

`BuildReport.java --task compile` while there are compile errors (faster), then `--task build`. Read the JSON; open the log only around one error you cannot understand from it.

- **Fix causes, not lines.** One missing package gives a dozen "cannot find symbol". javac reports in waves and stops at 100 — new errors after a fix are progress.
- **Real API, not memory.** Migration notes, then `javap -public -cp <jar> <class>` on the new version.
- **Generated code is fixed at its source** — the generator's version or configuration, never the generated files.
- **Build scripts are code** — keep pinned tool versions (the wrapper task's `gradleVersion`) in sync with the recipes.
- **Budget:** 10 iterations; stop if the error count did not drop for two in a row and report where you are stuck.
- **Commit per wave:** `llm: <what broke> — <what you changed>`, causes listed in the message.

**Never** (the hook enforces the first three; the rest is on you):

- delete tests, add `@Disabled` / `@Ignore`, remove assertions;
- change what a test checks — adapting it to a renamed type or method is fine, and goes into the report;
- pick dependency versions by hand — exceptions, each reported: the target version of a library without a recipe; removing a version so the BOM manages it; raising a plugin or generator to a release that supports the target;
- silence problems (`@SuppressWarnings` on removals, skipping tests).

If the only way forward breaks one of these, stop and ask.

## 6. Report

```
## Migration report
**Result:** build green / red, tests N of M (baseline M)
**Versions:** <component> A → B, …
### Upgrade plan
- <target> → needs <prerequisite> — <recipe> / by hand
### Done by recipes
- <recipe> — <what it changed>
### Cleanup and fixes (commits `llm:`)
- <file or area>: <what and why>
### Tests changed to follow an API change
- <test>: <old call> → <new call>; what it checks is unchanged
### For the reviewer
- <anything uncertain or left undone>
### Recipe candidates
- <library> <version>: <kind of change>, seen N times — before `<code>` → after `<code>`
```

Recipe candidates are repeated hand fixes for a library that has no recipe for them — a ready task for the library's team (`openrewrite-recipe-author` skill), so the next service gets the fix from a recipe.

Manual commands, if a script cannot run: `reference/gradle.md`, `reference/maven.md`.
