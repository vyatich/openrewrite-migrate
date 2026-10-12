# Internal recipe catalog

Maintained by the platform team: one line per published recipe artifact for an internal library. Library teams add their line when they release a breaking version together with its recipe (the `openrewrite-recipe-author` skill does that as its last step).

The artifacts live on the company's artifact server, which every service already has among its repositories — add the coordinates to the recipe run, nothing else.

| Library | From → to | Recipe | Artifact | Not covered by the recipe |
| --- | --- | --- | --- | --- |
| `com.corp:corp-commons` | 1.x → 2.0 | `com.corp.commons.MigrateToCorpCommons2` | `com.corp:corp-commons-recipes:2.0.0` | `CorpHttpClient` timeouts `long` → `Duration` |
