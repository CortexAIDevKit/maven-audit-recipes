package dev.cortexaidevkit.rewrite;

import dev.cortexaidevkit.rewrite.table.DependencyGovernanceTable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.maven.tree.Dependency;
import org.openrewrite.maven.tree.MavenResolutionResult;
import org.openrewrite.maven.tree.ResolvedDependency;
import org.openrewrite.maven.tree.ResolvedGroupArtifactVersion;
import org.openrewrite.maven.tree.ResolvedManagedDependency;
import org.openrewrite.maven.tree.ResolvedPom;
import org.openrewrite.maven.tree.Scope;
import org.openrewrite.text.PlainText;
import org.openrewrite.text.PlainTextParser;
import org.openrewrite.text.PlainTextVisitor;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Produces a dependency governance report for every Maven module, driven entirely by the
 * fully-resolved Maven model ({@link MavenResolutionResult}) rather than by parsing XML.
 *
 * <p>Two outputs are produced:
 * <ul>
 *     <li>An OpenRewrite {@link DependencyGovernanceTable} (exported as CSV).</li>
 *     <li>A generated {@code DEPENDENCY-GOVERNANCE.md} markdown table at the repository root.</li>
 * </ul>
 *
 * <p>This recipe never modifies POMs; it only reports. Removing redundant explicit versions is a
 * separate, opt-in step (see {@code dev.cortexaidevkit.DependencyGovernanceFix} in audit.yml).
 */
public class DependencyGovernanceReport extends ScanningRecipe<DependencyGovernanceReport.Accumulator> {

    private static final Path REPORT_PATH = Paths.get("DEPENDENCY-GOVERNANCE.md");

    transient DependencyGovernanceTable table = new DependencyGovernanceTable(this);

    @Override
    public String getDisplayName() {
        return "Dependency governance report";
    }

    @Override
    public String getDescription() {
        return "Reports, for every resolved dependency, whether it is declared, whether its version is " +
                "managed and by which BOM, whether it is reachable directly and/or transitively, and a " +
                "governance recommendation (e.g. remove a redundant explicit `<version>`). " +
                "Emits a data table and generates a `DEPENDENCY-GOVERNANCE.md` summary.";
    }

    public static class Accumulator {
        final List<DependencyGovernanceTable.Row> rows = new ArrayList<>();
        boolean reportFileExists;
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    SourceFile sf = (SourceFile) tree;
                    if (REPORT_PATH.equals(sf.getSourcePath())) {
                        acc.reportFileExists = true;
                    }
                    sf.getMarkers().findFirst(MavenResolutionResult.class)
                            .ifPresent(mrr -> collectRows(mrr, acc, ctx));
                }
                return tree;
            }
        };
    }

    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        if (acc.reportFileExists || acc.rows.isEmpty()) {
            return Collections.emptyList();
        }
        String markdown = renderMarkdown(acc.rows);
        return PlainTextParser.builder().build()
                .parse(markdown)
                .map(sf -> (SourceFile) sf.withSourcePath(REPORT_PATH))
                .collect(Collectors.toList());
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        if (acc.rows.isEmpty()) {
            return TreeVisitor.noop();
        }
        String markdown = renderMarkdown(acc.rows);
        return new PlainTextVisitor<ExecutionContext>() {
            @Override
            public PlainText visitText(PlainText text, ExecutionContext ctx) {
                if (REPORT_PATH.equals(text.getSourcePath()) && !markdown.equals(text.getText())) {
                    return text.withText(markdown);
                }
                return text;
            }
        };
    }

    // ---------------------------------------------------------------------------------------------
    // Analysis
    // ---------------------------------------------------------------------------------------------

    private void collectRows(MavenResolutionResult mrr, Accumulator acc, ExecutionContext ctx) {
        ResolvedPom pom = mrr.getPom();
        String project = pom.getGroupId() + ":" + pom.getArtifactId();

        // Dependencies explicitly declared in this POM, keyed by "groupId:artifactId".
        Map<String, Dependency> declared = new HashMap<>();
        for (Dependency d : pom.getRequestedDependencies()) {
            declared.put(ga(d.getGroupId(), d.getArtifactId()), d);
        }

        // Managed dependencies (from this POM's dependencyManagement and imported BOMs).
        Map<String, ResolvedManagedDependency> managed = new HashMap<>();
        for (ResolvedManagedDependency m : pom.getDependencyManagement()) {
            managed.putIfAbsent(ga(m.getGroupId(), m.getArtifactId()), m);
        }

        // Walk the resolved graph across all scopes: record direct vs transitive reachability.
        Map<String, ResolvedDependency> byGa = new LinkedHashMap<>();
        Set<String> directGas = new HashSet<>();
        Set<String> transitiveGas = new HashSet<>();
        for (Map.Entry<Scope, List<ResolvedDependency>> e : mrr.getDependencies().entrySet()) {
            for (ResolvedDependency rd : e.getValue()) {
                String key = ga(rd.getGroupId(), rd.getArtifactId());
                byGa.putIfAbsent(key, rd);
                if (rd.getDepth() == 0) {
                    directGas.add(key);
                } else {
                    transitiveGas.add(key);
                }
            }
        }

        for (Map.Entry<String, ResolvedDependency> e : byGa.entrySet()) {
            String key = e.getKey();
            ResolvedDependency rd = e.getValue();
            String groupId = rd.getGroupId();
            String artifactId = rd.getArtifactId();
            String resolvedVersion = rd.getVersion();

            Dependency dec = declared.get(key);
            Dependency requested = dec != null ? dec : rd.getRequested();
            boolean isDeclared = dec != null;
            String requestedVersion = requested != null ? requested.getVersion() : null;
            boolean explicitVersion = isDeclared && dec.getVersion() != null;

            String type = requested != null ? requested.getType() : null;
            String classifier = requested != null ? requested.getClassifier() : null;
            String managedVersion = pom.getManagedVersion(groupId, artifactId, type, classifier);
            boolean isManaged = managedVersion != null;

            String managedBy = managedByLabel(managed.get(key), isManaged);
            String bomChain = managedBy; // best-effort single hop; see README limitations

            boolean direct = directGas.contains(key);
            boolean transitive = transitiveGas.contains(key);

            String recommendation = recommend(
                    isDeclared, explicitVersion, isManaged, requestedVersion, managedVersion, transitive, managedBy);

            DependencyGovernanceTable.Row row = new DependencyGovernanceTable.Row(
                    project, groupId, artifactId, resolvedVersion, requestedVersion,
                    isDeclared, isManaged, managedBy, bomChain, direct, transitive, explicitVersion, recommendation);

            table.insertRow(ctx, row);
            acc.rows.add(row);
        }
    }

    private static String managedByLabel(ResolvedManagedDependency mgd, boolean isManaged) {
        if (!isManaged) {
            return "";
        }
        if (mgd != null && mgd.getBomGav() != null) {
            ResolvedGroupArtifactVersion bom = mgd.getBomGav();
            return bom.getGroupId() + ":" + bom.getArtifactId() + ":" + bom.getVersion();
        }
        return "this pom";
    }

    private static String recommend(boolean declared, boolean explicitVersion, boolean managed,
                                    String requestedVersion, String managedVersion,
                                    boolean transitive, String managedBy) {
        if (declared && explicitVersion && managed
                && requestedVersion != null && requestedVersion.equals(managedVersion)) {
            return "Remove <version> (managed by " + managedBy + ")";
        }
        if (declared && transitive) {
            return "Already supplied transitively; consider removing explicit declaration";
        }
        return "OK";
    }

    private static String ga(String groupId, String artifactId) {
        return groupId + ":" + artifactId;
    }

    // ---------------------------------------------------------------------------------------------
    // Markdown rendering
    // ---------------------------------------------------------------------------------------------

    private static String renderMarkdown(List<DependencyGovernanceTable.Row> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Dependency Governance Report\n\n");
        sb.append("_Generated by the `dev.cortexaidevkit.DependencyGovernanceReport` OpenRewrite recipe " +
                "from the resolved Maven model._\n\n");
        sb.append("| Project | Dependency | Declared | Managed | Managed By | BOM Chain | Direct | Transitive | Explicit Version | Recommendation |\n");
        sb.append("| --- | --- | :---: | :---: | --- | --- | :---: | :---: | :---: | --- |\n");
        for (DependencyGovernanceTable.Row r : rows) {
            sb.append("| ").append(nz(r.getProject()))
                    .append(" | `").append(r.getGroupId()).append(":").append(r.getArtifactId()).append("`")
                    .append(" | ").append(check(r.isDeclared()))
                    .append(" | ").append(check(r.isManaged()))
                    .append(" | ").append(code(r.getManagedBy()))
                    .append(" | ").append(code(r.getBomChain()))
                    .append(" | ").append(check(r.isDirect()))
                    .append(" | ").append(check(r.isTransitive()))
                    .append(" | ").append(check(r.isExplicitVersion()))
                    .append(" | ").append(nz(r.getRecommendation()))
                    .append(" |\n");
        }
        return sb.toString();
    }

    private static String check(boolean b) {
        return b ? "✓" : "✗";
    }

    private static String code(String s) {
        return s == null || s.isEmpty() ? "—" : "`" + s + "`";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
