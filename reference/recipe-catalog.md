# Recipe catalog

Pinned versions, checked on 2026-10-11. The newest OpenRewrite release is not always resolvable from Maven Central (Gradle plugin 7.40.0 and 7.41.0 point to a `rewrite-bom` that was never published), so prefer these versions and move them forward only after a successful run.

| Artifact | Version | License |
| --- | --- | --- |
| `org.openrewrite.maven:rewrite-maven-plugin` | 6.46.1 | Apache 2.0 |
| Gradle plugin `org.openrewrite:plugin` (used by `scripts/rewrite-init.gradle`) | 7.39.0 | Apache 2.0 |
| `org.openrewrite.recipe:rewrite-spring` | 6.37.1 | Moderne Source Available |
| `org.openrewrite.recipe:rewrite-migrate-java` | 3.42.1 | Moderne Source Available |

Moderne Source Available: free to use on your own code, not to resell as a service.

## Which recipe for which migration

| Migration | Recipe | Artifact |
| --- | --- | --- |
| Spring Boot → 4.0 (from any 2.x or 3.x; chains 2.7 → 3.0 → … → 3.5 → 4.0, includes javax → jakarta, Spring Security 7, Hibernate 7.1, Jackson 3, `@MockBean` → `@MockitoBean`, property renames, wrapper bump to Gradle 9.1) | `org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_0` | `rewrite-spring` |
| Spring Boot → 3.5 | `org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_5` | `rewrite-spring` |
| Java → 25 | `org.openrewrite.java.migrate.UpgradeToJava25` | `rewrite-migrate-java` |
| Java → 21 | `org.openrewrite.java.migrate.UpgradeToJava21` | `rewrite-migrate-java` |
| javax → jakarta only | `org.openrewrite.java.migrate.jakarta.JakartaEE10` | `rewrite-migrate-java` |

Spring Boot and Java recipes are usually run together in one pass, for example
`UpgradeSpringBoot_4_0,UpgradeToJava25` with both artifacts on the recipe classpath.

Checked end to end: Spring Boot 2.7.18 / Java 11 → Spring Boot 4.0.8 / Java 25, on Maven and on Gradle 6.8.3. What was left for hand fixes: `@Override` on a method of a class that no longer extends `WebSecurityConfigurerAdapter`; `getReasonPhrase()` called on `HttpStatusCode` (now an interface — use `HttpStatus.valueOf(code.value())`).

## Internal libraries

There are no public recipes for internal libraries. Read the library's `MIGRATION.md` / changelog and either fix by hand or — for pure renames — use the generic recipes from `rewrite-java` (available without extra artifacts):

| Change | Recipe |
| --- | --- |
| Package renamed | `org.openrewrite.java.ChangePackage` |
| Class renamed | `org.openrewrite.java.ChangeType` |
| Method renamed | `org.openrewrite.java.ChangeMethodName` |
| Dependency version (Gradle) | `org.openrewrite.gradle.UpgradeDependencyVersion` |
| Dependency version (Maven) | `org.openrewrite.maven.UpgradeDependencyVersion` |

These take parameters, so they go into a `rewrite.yml` — see `recipe-authoring.md`.
