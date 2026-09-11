// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.mvn.PomImporter;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Maven import → render → parse round trips. Whatever the POM throws at the importer, the emitted
 * jk.toml must be one jk itself accepts, and package identities (g:a:type:classifier) must survive.
 */
class MavenImportRoundTripTest {

    @Test
    void jar_plus_test_jar_of_one_ga_round_trips_as_two_packages() {
        JkBuild imported = importPom("""
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.apache.logging.log4j</groupId>
                      <artifactId>log4j-core</artifactId>
                      <version>2.24.0</version>
                      <scope>test</scope>
                    </dependency>
                    <dependency>
                      <groupId>org.apache.logging.log4j</groupId>
                      <artifactId>log4j-core</artifactId>
                      <version>2.24.0</version>
                      <type>test-jar</type>
                      <scope>test</scope>
                    </dependency>
                  </dependencies>
                </project>
                """);

        String rendered = JkBuildRenderer.render(imported);
        JkBuild reparsed = JkBuildParser.parse(rendered);

        // Both packages of the GA make it to the lock input — this is what the
        // resolver roots on, so losing one here silently drops a test classpath entry.
        assertThat(reparsed.dependencies().of(Scope.TEST))
                .extracting(Dependency::packageKey)
                .containsExactlyInAnyOrder(
                        "org.apache.logging.log4j:log4j-core:jar:",
                        "org.apache.logging.log4j:log4j-core:test-jar:tests");
    }

    @Test
    void compile_scope_tests_classifier_dep_round_trips() {
        // `<classifier>tests</classifier>` in compile scope is not kind=tests; classifier
        // alone does not imply test-jar. The renderer must not write `kind = "tests"` under
        // [dependencies], which JkBuildParser hard-rejects.
        JkBuild imported = importPom(pom("compile", "tests", null));
        JkBuild reparsed = JkBuildParser.parse(JkBuildRenderer.render(imported));
        assertThat(reparsed.dependencies().of(Scope.MAIN))
                .extracting(Dependency::packageKey)
                .containsExactly("com.acme:helpers:jar:");
    }

    @Test
    void non_test_scope_test_jar_moves_to_test_dependencies() {
        var result =
                PomImporter.importFromBytes(pom("compile", null, "test-jar").getBytes(StandardCharsets.UTF_8));
        JkBuild reparsed = JkBuildParser.parse(JkBuildRenderer.render(result.jkBuild()));
        assertThat(reparsed.dependencies().of(Scope.MAIN)).isEmpty();
        assertThat(reparsed.dependencies().of(Scope.TEST))
                .extracting(Dependency::packageKey)
                .containsExactly("com.acme:helpers:test-jar:tests");
        assertThat(result.report().issues()).anyMatch(i -> i.message().contains("moved to [test-dependencies]"));
    }

    @Test
    void every_scope_classifier_type_combination_renders_toml_jk_accepts() {
        // Property-style sweep: whatever Maven shape comes in, `jk lock` must not choke on
        // the jk.toml the importer wrote. (system scope is rejected up front — no dep emitted —
        // and that still yields a parseable file, so it belongs in the sweep.)
        String[] scopes = {null, "compile", "runtime", "provided", "test", "system"};
        String[] classifiers = {null, "tests", "sources", "linux-x86_64"};
        String[] types = {null, "jar", "test-jar", "pom"};
        for (String scope : scopes) {
            for (String classifier : classifiers) {
                for (String type : types) {
                    String xml = pom(scope, classifier, type);
                    String rendered = JkBuildRenderer.render(importPom(xml));
                    try {
                        JkBuildParser.parse(rendered);
                    } catch (RuntimeException e) {
                        throw new AssertionError(
                                "import emitted jk.toml that jk rejects for scope="
                                        + scope + " classifier=" + classifier + " type=" + type + "\n---\n"
                                        + rendered,
                                e);
                    }
                }
            }
        }
    }

    private static String pom(String scope, @Nullable String classifier, @Nullable String type) {
        StringBuilder dep = new StringBuilder("""
                    <dependency>
                      <groupId>com.acme</groupId>
                      <artifactId>helpers</artifactId>
                      <version>1.2.3</version>
                """);
        if (scope != null) dep.append("      <scope>").append(scope).append("</scope>\n");
        if (classifier != null)
            dep.append("      <classifier>").append(classifier).append("</classifier>\n");
        if (type != null) dep.append("      <type>").append(type).append("</type>\n");
        dep.append("    </dependency>");
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                %s
                  </dependencies>
                </project>
                """.formatted(dep.toString().indent(0).stripTrailing());
    }

    private static JkBuild importPom(String xml) {
        return PomImporter.importFromBytes(xml.getBytes(StandardCharsets.UTF_8)).jkBuild();
    }
}
