# Finding recipes and versions

Do not rely on this file for what is current — look it up. Recipes and their versions change every few weeks; the sources below are the truth.

## Where to look

| Question | Where |
| --- | --- |
| Which recipe migrates to X, and what does it include? | Recipe catalog: `https://docs.openrewrite.org/recipes` (for example `.../recipes/java/spring/boot4/upgradespringboot_4_0`). The page lists the sub-recipes and the artifact the recipe lives in. |
| What exactly does a recipe do, which versions does it target? | Recipe YAML on GitHub: `openrewrite/rewrite-spring` and `openrewrite/rewrite-migrate-java`, folder `src/main/resources/META-INF/rewrite/` (for example `spring-boot-40.yml`). Read the `recipeList` of the recipe you plan to run. |
| What recipes are in an artifact I already have? | Locally: `rewrite:discover` (Maven) or `rewriteDiscover` (Gradle) with the artifact on the recipe classpath. |
| Latest version of a recipe artifact or plugin | Maven Central metadata: `https://repo.maven.apache.org/maven2/org/openrewrite/recipe/<artifact>/maven-metadata.xml`; Gradle plugin: `https://plugins.gradle.org/m2/org/openrewrite/plugin/maven-metadata.xml`. |
| What changed in the framework itself (things recipes miss) | Spring Boot migration guides in the GitHub wiki: `https://github.com/spring-projects/spring-boot/wiki` (Spring Boot 3.0 / 4.0 Migration Guide, release notes); Spring Framework and Spring Security "What's New" / migration pages. |

GitHub is reachable through any web access you have, or through `gh api` / raw.githubusercontent.com if a GitHub CLI or plain HTTP is available.

**Check a new plugin version before using it.** The newest OpenRewrite plugin is sometimes published before its dependencies: Gradle plugin 7.40.0 and 7.41.0 point to a `rewrite-bom` 8.91.0 that is not on Maven Central. Open the plugin's POM and make sure the `rewrite-bom` version it references exists; if not, take the previous plugin version.

## Last versions that worked end to end

Fallback if the lookup is not possible. Checked on 2026-10-11 on Spring Boot 2.7.18 / Java 11 → Spring Boot 4.0.8 / Java 25, Maven and Gradle 6.8.3.

| Artifact | Version | License |
| --- | --- | --- |
| `org.openrewrite.maven:rewrite-maven-plugin` | 6.46.1 | Apache 2.0 |
| Gradle plugin `org.openrewrite:plugin` (pinned in `scripts/rewrite-init.gradle`) | 7.39.0 | Apache 2.0 |
| `org.openrewrite.recipe:rewrite-spring` | 6.37.1 | Moderne Source Available |
| `org.openrewrite.recipe:rewrite-migrate-java` | 3.42.1 | Moderne Source Available |

Recipes used with them: `org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_0` (chains every Spring Boot step from 2.x, includes javax → jakarta, Security 7, Hibernate 7.1, Jackson 3, the Gradle wrapper bump) and `org.openrewrite.java.migrate.UpgradeToJava25`, run together in one pass.

Moderne Source Available: free to use on your own code, not to resell as a service. Mention it in the report if the user might care.

## Internal libraries

There are no public recipes for internal libraries. Read the library's `MIGRATION.md` / changelog; for pure renames the generic recipes from `rewrite-java` work without extra artifacts (`ChangePackage`, `ChangeType`, `ChangeMethodName`, plus `org.openrewrite.gradle.UpgradeDependencyVersion` / `org.openrewrite.maven.UpgradeDependencyVersion`) — they take parameters, so they go into a `rewrite.yml`, see `recipe-authoring.md`.
