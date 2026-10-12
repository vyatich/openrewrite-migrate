# Recipes and versions

## Use by default

Checked end to end on 2026-10-11/12 (Spring Boot 2.7.18 / Java 11 → Spring Boot 4.0.8 / Java 25, Gradle 6.8.3 and Maven). Use these unless they fail or the user asked for the newest.

| Artifact | Version | License |
| --- | --- | --- |
| `org.openrewrite.maven:rewrite-maven-plugin` (default in `RunRecipes.java`) | 6.46.1 | Apache 2.0 |
| Gradle plugin `org.openrewrite:plugin` (pinned in `scripts/rewrite-init.gradle`) | 7.39.0 | Apache 2.0 |
| `org.openrewrite.recipe:rewrite-spring` | 6.37.1 | Moderne Source Available |
| `org.openrewrite.recipe:rewrite-migrate-java` | 3.42.1 | Moderne Source Available |

| Migration | Recipe | Artifact |
| --- | --- | --- |
| Spring Boot → 4.0 (chains every step from 2.x: javax → jakarta, Security 7, Hibernate 7.1, Jackson 3, Gradle wrapper to 8.14+, Java to 17) | `org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_0` | `rewrite-spring` |
| Spring Boot → 3.5 | `org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_5` | `rewrite-spring` |
| Java → 25 / 21 | `org.openrewrite.java.migrate.UpgradeToJava25` / `UpgradeToJava21` | `rewrite-migrate-java` |
| javax → jakarta only | `org.openrewrite.java.migrate.jakarta.JakartaEE10` | `rewrite-migrate-java` |

Spring Boot and Java recipes run together in one pass, with internal recipes added to the same run.

Moderne Source Available: free to use on your own code, not to resell as a service. Mention it in the report if the user might care.

## When to look things up, and where

Only when the versions above fail, a needed recipe is not listed, or the user asked for the newest:

| Question | Where |
| --- | --- |
| Which recipe migrates to X, and what does it include? | `https://docs.openrewrite.org/recipes` (for example `.../recipes/java/spring/boot4/upgradespringboot_4_0`): sub-recipes and the artifact. |
| What exactly does a recipe do? | Recipe YAML on GitHub: `openrewrite/rewrite-spring`, `openrewrite/rewrite-migrate-java`, folder `src/main/resources/META-INF/rewrite/`. |
| What is in an artifact I already have? | `rewrite:discover` (Maven) / `rewriteDiscover` (Gradle). |
| Latest version | `https://repo.maven.apache.org/maven2/org/openrewrite/recipe/<artifact>/maven-metadata.xml`; Gradle plugin: `https://plugins.gradle.org/m2/org/openrewrite/plugin/maven-metadata.xml`. |
| What changed in the framework (things recipes miss) | Spring Boot migration guides in the `spring-projects/spring-boot` GitHub wiki; Spring Framework / Security "What's New". |

**Check a new plugin version before using it.** The newest OpenRewrite plugin is sometimes published before its dependencies: Gradle plugin 7.40.0 and 7.41.0 point to a `rewrite-bom` 8.91.0 that is not on Maven Central. Make sure the `rewrite-bom` its POM references exists; if not, take the previous version.

## Internal libraries

There are no public recipes for internal libraries, but there may be internal ones: the library's team publishes a recipe artifact with each breaking release (often `<group>:<library>-recipes:<version>`), once for every team. Where to find it:

1. The library's migration notes (`MIGRATION.md`, changelog, release notes) — they name the recipe and its artifact.
2. The organisation's catalog: `internal-recipes.md` next to this file.
3. The company's artifact server: an artifact with `recipes` in its name next to the library.

What an artifact contains — the recipe names and what each one covers — is in the YAML files under `META-INF/rewrite/` inside the jar:

```bash
unzip -l <jar> | grep META-INF/rewrite/
unzip -p <jar> 'META-INF/rewrite/*.yml' | grep -E "^name:|^displayName:|^description:|^  [^ -]|^  - "
```

Read the recipe's description: it usually says which changes it does **not** cover — those stay for you.

No recipe for an internal library: fix by hand following its migration notes, and report the repeated fixes as recipe candidates (SKILL.md, step 6).
