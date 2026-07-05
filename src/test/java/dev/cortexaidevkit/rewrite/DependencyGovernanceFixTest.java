package dev.cortexaidevkit.rewrite;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.maven.Assertions.pomXml;
import static org.openrewrite.test.SourceSpecs.text;

/**
 * Verifies the declarative {@code dev.cortexaidevkit.DependencyGovernanceFix} recipe wired in
 * audit.yml: it runs the report and then removes redundant explicit versions via the built-in
 * {@code org.openrewrite.maven.RemoveRedundantDependencyVersions}.
 *
 * <p>Requires network access to resolve {@code commons-io} from Maven Central.
 */
class DependencyGovernanceFixTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        // Load the declarative recipe from its specific YAML rather than scanning the whole runtime
        // classpath: scanRuntimeClasspath() (used by recipeFromResources) is a parallel scan whose
        // races can intermittently fail to link a declarative recipe that references other recipes.
        spec.recipeFromResource("/META-INF/rewrite/audit.yml", "dev.cortexaidevkit.DependencyGovernanceFix");
    }

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
            "  </dependencies>\n" +
            "</project>\n";

    @Test
    void removesRedundantExplicitVersion() {
        rewriteRun(
            pomXml(POM, spec -> spec.after(actual -> {
                // Version 2.15.1 appears twice before (dependencyManagement + dependency);
                // the redundant one on the <dependency> is removed, leaving exactly one.
                assertThat(countOccurrences(actual, "2.15.1")).isEqualTo(1);
                return actual;
            })),
            text(null, spec -> spec.path("target/rewrite/maven-audit-recipes/DEPENDENCY-GOVERNANCE.md").after(actual -> {
                assertThat(actual).contains("Dependency Governance Report");
                return actual;
            }))
        );
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
