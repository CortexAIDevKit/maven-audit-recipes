package dev.cortexaidevkit.rewrite;

import dev.cortexaidevkit.rewrite.table.DependencyGovernanceTable;
import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.maven.Assertions.pomXml;
import static org.openrewrite.test.SourceSpecs.text;

/**
 * Note: these tests resolve real artifacts from Maven Central, so they need network access
 * (or a warm ~/.m2). The dependencies used ({@code commons-io}, {@code commons-lang3}) are tiny
 * and have no compile-scope transitives to keep resolution fast and deterministic.
 */
class DependencyGovernanceReportTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new DependencyGovernanceReport());
    }

    // A POM that manages commons-io locally and then redundantly re-declares the same version,
    // plus a non-managed dependency that pins its own version.
    private static final String POM =
            "<project>\n" +
            "  <modelVersion>4.0.0</modelVersion>\n" +
            "  <groupId>com.example</groupId>\n" +
            "  <artifactId>demo</artifactId>\n" +
            "  <version>1.0.0</version>\n" +
            "  <dependencyManagement>\n" +
            "    <dependencies>\n" +
            "      <dependency>\n" +
            "        <groupId>commons-io</groupId>\n" +
            "        <artifactId>commons-io</artifactId>\n" +
            "        <version>2.15.1</version>\n" +
            "      </dependency>\n" +
            "    </dependencies>\n" +
            "  </dependencyManagement>\n" +
            "  <dependencies>\n" +
            "    <dependency>\n" +
            "      <groupId>commons-io</groupId>\n" +
            "      <artifactId>commons-io</artifactId>\n" +
            "      <version>2.15.1</version>\n" +
            "    </dependency>\n" +
            "    <dependency>\n" +
            "      <groupId>org.apache.commons</groupId>\n" +
            "      <artifactId>commons-lang3</artifactId>\n" +
            "      <version>3.14.0</version>\n" +
            "    </dependency>\n" +
            "  </dependencies>\n" +
            "</project>\n";

    @Test
    void flagsRedundantManagedVersionAndEmitsReport() {
        rewriteRun(
                spec -> spec.dataTable(DependencyGovernanceTable.Row.class, rows -> {
                    // The redundantly-versioned, managed dependency is recommended for cleanup.
                    assertThat(rows)
                            .anySatisfy(r -> {
                                assertThat(r.getArtifactId()).isEqualTo("commons-io");
                                assertThat(r.isDeclared()).isTrue();
                                assertThat(r.isManaged()).isTrue();
                                assertThat(r.isExplicitVersion()).isTrue();
                                assertThat(r.getRecommendation()).startsWith("Remove <version>");
                            });
                    // The non-managed dependency keeps its explicit version: no action.
                    assertThat(rows)
                            .anySatisfy(r -> {
                                assertThat(r.getArtifactId()).isEqualTo("commons-lang3");
                                assertThat(r.isManaged()).isFalse();
                                assertThat(r.getRecommendation()).isEqualTo("OK");
                            });
                }),
                pomXml(POM),
                // The recipe generates the markdown report; content is dynamic, so validate substrings.
                text(null, spec -> spec.path("DEPENDENCY-GOVERNANCE.md").after(actual -> {
                    assertThat(actual).contains("Dependency Governance Report");
                    assertThat(actual).contains("commons-io");
                    return actual;
                }))
        );
    }
}
