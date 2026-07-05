package dev.cortexaidevkit.rewrite.table;

import org.openrewrite.Column;
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

/**
 * One row per resolved dependency describing how it is governed: whether it is declared, whether a
 * version is managed for it, which BOM supplied that version, whether it is reachable directly and/or
 * transitively, and a plain-language recommendation.
 *
 * <p>Exported as CSV under {@code target/rewrite/datatables/...} when the recipe runs.
 *
 * <p>The {@code Row} is a hand-written immutable value type (no Lombok) so the module builds cleanly
 * across JDKs regardless of annotation-processor compatibility.
 */
public class DependencyGovernanceTable extends DataTable<DependencyGovernanceTable.Row> {

    public DependencyGovernanceTable(Recipe recipe) {
        super(recipe,
                "Dependency governance report",
                "For every resolved dependency: declared/managed status, managing BOM, direct/transitive " +
                        "reachability, explicit-version usage, and a governance recommendation.");
    }

    public static class Row {
        @Column(displayName = "Project",
                description = "The Maven project (groupId:artifactId) whose POM was analyzed.")
        private final String project;

        @Column(displayName = "Group",
                description = "The dependency groupId.")
        private final String groupId;

        @Column(displayName = "Artifact",
                description = "The dependency artifactId.")
        private final String artifactId;

        @Column(displayName = "Resolved version",
                description = "The version Maven resolved after version mediation.")
        private final String resolvedVersion;

        @Column(displayName = "Requested version",
                description = "The version literally requested in the POM, if any (may be a property/range).")
        private final String requestedVersion;

        @Column(displayName = "Declared",
                description = "Whether this dependency is explicitly declared in this POM's <dependencies>.")
        private final boolean declared;

        @Column(displayName = "Managed",
                description = "Whether a version is supplied for this dependency via dependencyManagement/BOM.")
        private final boolean managed;

        @Column(displayName = "Managed by",
                description = "The BOM (groupId:artifactId:version) that supplied the managed version, if known.")
        private final String managedBy;

        @Column(displayName = "BOM chain",
                description = "Best-effort chain of imported BOMs leading to the managed version.")
        private final String bomChain;

        @Column(displayName = "Direct",
                description = "Whether the dependency is a direct dependency of this project.")
        private final boolean direct;

        @Column(displayName = "Transitive",
                description = "Whether the dependency is also reachable transitively via another dependency.")
        private final boolean transitive;

        @Column(displayName = "Explicit version",
                description = "Whether the declaration pins an explicit <version> (rather than inheriting one).")
        private final boolean explicitVersion;

        @Column(displayName = "Recommendation",
                description = "Governance recommendation, e.g. remove a redundant <version>.")
        private final String recommendation;

        public Row(String project, String groupId, String artifactId, String resolvedVersion,
                   String requestedVersion, boolean declared, boolean managed, String managedBy,
                   String bomChain, boolean direct, boolean transitive, boolean explicitVersion,
                   String recommendation) {
            this.project = project;
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.resolvedVersion = resolvedVersion;
            this.requestedVersion = requestedVersion;
            this.declared = declared;
            this.managed = managed;
            this.managedBy = managedBy;
            this.bomChain = bomChain;
            this.direct = direct;
            this.transitive = transitive;
            this.explicitVersion = explicitVersion;
            this.recommendation = recommendation;
        }

        public String getProject() {
            return project;
        }

        public String getGroupId() {
            return groupId;
        }

        public String getArtifactId() {
            return artifactId;
        }

        public String getResolvedVersion() {
            return resolvedVersion;
        }

        public String getRequestedVersion() {
            return requestedVersion;
        }

        public boolean isDeclared() {
            return declared;
        }

        public boolean isManaged() {
            return managed;
        }

        public String getManagedBy() {
            return managedBy;
        }

        public String getBomChain() {
            return bomChain;
        }

        public boolean isDirect() {
            return direct;
        }

        public boolean isTransitive() {
            return transitive;
        }

        public boolean isExplicitVersion() {
            return explicitVersion;
        }

        public String getRecommendation() {
            return recommendation;
        }
    }
}
