# maven-audit-recipes

OpenRewrite recipes that produce a **Dependency Governance Report** for Maven projects, driven
entirely by OpenRewrite's fully-resolved Maven model (`MavenResolutionResult`) — no manual XML
parsing, no guessing.

## What it answers

For every resolved dependency (direct and transitive) the report captures:

| Column               | Meaning                                                                     |
|----------------------|-----------------------------------------------------------------------------|
| **Declared**         | Explicitly listed in this POM's `<dependencies>`                            |
| **Managed**          | A version is supplied via `dependencyManagement` / an imported BOM          |
| **Managed By**       | The BOM (`groupId:artifactId:version`) that supplied the managed version    |
| **BOM Chain**        | Best-effort chain of imported BOMs (see [*Limitations*(#)](#limitations)  ) |
| **Direct**           | A direct dependency of this project                                         |
| **Transitive**       | Also reachable transitively via another dependency                          |
| **Explicit Version** | The declaration pins its own `<version>`                                    |
| **Recommendation**   | e.g. *Remove `<version>` (managed by …)*                                    |

### Recommendation logic

- **Remove `<version>`** — declared with an explicit version that exactly matches the version
  already managed by a BOM/`dependencyManagement`.
- **Already supplied transitively** — declared directly but also reachable transitively; consider
  dropping the explicit declaration.
- **OK** — nothing to do.

## Outputs

Running the recipe produces two things:

1. **Data table (CSV)** — under `target/rewrite/datatables/…` when `exportDatatables` is enabled.
2. **`target/rewrite/maven-audit-recipes/DEPENDENCY-GOVERNANCE.md`** — a generated markdown table,
   regenerated on each run alongside the build outputs.

## Recipes

| Recipe                                          | Effect                                                                                                                                     |
|-------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------|
| `dev.cortexaidevkit.DependencyGovernanceReport` | Report only (data table + markdown). No POM changes.                                                                                       |
| `dev.cortexaidevkit.DependencyGovernanceAudit`  | Alias for report-only (declarative).                                                                                                       |
| `dev.cortexaidevkit.DependencyGovernanceFix`    | Report **and** remove redundant explicit `<version>` tags (reuses the built-in `org.openrewrite.maven.RemoveRedundantDependencyVersions`). |

## Usage

Build and install the recipe module locally:

```bash
mvn -DskipTests install
```

Then run it against any Maven project by adding the plugin (with this module as a recipe
dependency) and invoking it — or run it ad hoc without editing the target build:

```bash
mvn org.openrewrite.maven:rewrite-maven-plugin:run \
    -Drewrite.recipeArtifactCoordinates=dev.cortexaidevkit:maven-audit-recipes:0.1.0-SNAPSHOT \
    -Drewrite.activeRecipes=dev.cortexaidevkit.DependencyGovernanceAudit \
    -Drewrite.exportDatatables=true
```

Use `rewrite:dryRun` instead of `rewrite:run` to preview changes without writing them.

## Limitations

- **Unused dependencies are out of scope.** OpenRewrite sees the AST plus the Maven model, not
  bytecode, so it cannot tell whether a dependency's classes are actually referenced. Keep using
  `mvn dependency:analyze` (or `jdeps`) for that.
- **BOM chain is best-effort.** The resolved model reliably exposes the BOM that supplied a managed
  version (`ResolvedManagedDependency.getBomGav()`), but reconstructing a full multi-hop import
  chain (e.g. `spring-boot-dependencies → junit-bom`) is not always possible; the report shows the
  supplying BOM as a single hop.

## Development

- Java 8 bytecode target for broad JDK compatibility.
- `MavenResolutionResult`, `ResolvedPom`, `ResolvedDependency`, `ResolvedManagedDependency`, and
  `Scope` do the heavy lifting — see [
  `DependencyGovernanceReport`](src/main/java/dev/cortexaidevkit/rewrite/DependencyGovernanceReport.java).
- Tests use the OpenRewrite `RewriteTest` harness. Because they resolve real BOMs from Maven
  Central, they require network access (or a warm `~/.m2`).

```bash
mvn test
```
