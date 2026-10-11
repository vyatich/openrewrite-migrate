# Turning repeated fixes into a recipe

A declarative recipe is a few lines of YAML in `rewrite.yml` at the project root. It is the cheapest kind of recipe: no code, no build, and both the Maven and Gradle plugins pick it up automatically.

## When it fits

Pure renames and version bumps — the generic recipes from `rewrite-java`, `rewrite-gradle`, `rewrite-maven`:

```yaml
---
type: specs.openrewrite.org/v1beta/recipe
name: com.corp.MigrateCorpCommons_2_0
displayName: Migrate to corp-commons 2.0
description: Package util → core, CorpLogger → CorpLog, getConfig() → config(), dependency 1.x → 2.0.0.
recipeList:
  - org.openrewrite.java.ChangePackage:
      oldPackageName: com.corp.commons.util
      newPackageName: com.corp.commons.core
  - org.openrewrite.java.ChangeType:
      oldFullyQualifiedTypeName: com.corp.commons.core.CorpLogger
      newFullyQualifiedTypeName: com.corp.commons.core.CorpLog
  - org.openrewrite.java.ChangeMethodName:
      methodPattern: com.corp.commons.core.CorpContext getConfig()
      newMethodName: config
  - org.openrewrite.gradle.UpgradeDependencyVersion:
      groupId: com.corp
      artifactId: corp-commons
      newVersion: 2.0.0
```

Order matters: after `ChangePackage` the old class lives in the new package, so the following steps refer to the new package name. Use `org.openrewrite.maven.UpgradeDependencyVersion` for Maven projects.

## How to check it

1. Check it on the **pre-migration** commit, in a scratch worktree or branch: the OpenRewrite plugins compile the sources before running recipes, and the pre-migration code still compiles on its old JDK, while the bare recipe commit usually does not.
2. Run only this recipe with a dry run (`rewriteDryRun` / `rewrite-maven-plugin:dryRun`, `-Drewrite.activeRecipe(s)=com.corp.MigrateCorpCommons_2_0`) and compare the patch with your hand fixes for that library.
3. If it reproduces them, the recipe is good; put it into the report as a proposal. The user decides whether it goes into the shared recipe catalog.

One dry run is enough. If it does not match on the first try, put the recipe into the report as a draft instead of iterating on it — the migration itself is the job.

## When it does not fit

Changes of types or semantics — `long` milliseconds → `Duration`, a new required argument, a different call sequence — need a Refaster template or an imperative recipe with tests (`RewriteTest`). That is a separate piece of work for the library owners: do not write it as part of the migration. Put two or three before/after examples into the report instead:

```java
// before (corp-commons 1.x)
new CorpHttpClient(baseUrl, config.getLong("timeout-ms", 2000))
// after (corp-commons 2.0)
new CorpHttpClient(baseUrl, Duration.ofMillis(config.getLong("timeout-ms", 2000)))
```
