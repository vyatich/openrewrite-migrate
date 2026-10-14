# Internal recipe catalog

Maintained by the platform team: one line per published recipe artifact for an internal library. Library teams add their line when they release a breaking version together with its recipe (the `openrewrite-recipe-author` skill does that as its last step).

The artifacts live on the company's artifact server. Usually every service already has it among its repositories, and then you only add the coordinates to the recipe run. If the build does not know that repository, pass its URL to `RunRecipes.java --repos` (Gradle) or add it to `~/.m2/settings.xml` (Maven).

A library that is not in the table may still have a recipe: look in its migration notes and on the artifact server (`recipe-catalog.md`, "Internal libraries").

| Library | From → to | Recipe | Artifact | Not covered by the recipe |
| --- | --- | --- | --- | --- |
| `com.corp:corp-commons` (example: the demo library of this repository) | 1.x → 2.0 | `com.corp.commons.MigrateToCorpCommons2` | `com.corp:corp-commons-recipes:2.0.0` | `CorpHttpClient` timeouts `long` → `Duration` |
