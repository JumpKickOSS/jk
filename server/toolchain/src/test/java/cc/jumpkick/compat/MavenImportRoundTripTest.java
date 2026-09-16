// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.mvn.PomImporter;
import cc.jumpkick.mvn.TestImporters;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Maven import → render → parse round trips. Whatever the POM throws at the importer, the emitted
 * jk.toml must be one jk itself accepts, and package identities (g:a:type:classifier) must survive.
 */
class MavenImportRoundTripTest {

    private PomImporter importer;

    @BeforeEach
    void importer(@TempDir Path tempDir) throws IOException {
        importer = TestImporters.offline(tempDir);
    }

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
        // `<classifier>tests</classifier>` on the default jar type is the classified jar, not
        // kind=tests: the entry carries `classifier = "tests"` under [dependencies], where
        // JkBuildParser hard-rejects `kind = "tests"`.
        JkBuild imported = importPom(pom("compile", "tests", null));
        JkBuild reparsed = JkBuildParser.parse(JkBuildRenderer.render(imported));
        assertThat(reparsed.dependencies().of(Scope.MAIN)).singleElement().satisfies(d -> {
            assertThat(d.packageKey()).isEqualTo("com.acme:helpers:jar:tests");
            assertThat(d.classifier()).isEqualTo("tests");
            assertThat(d.isTestsKind()).isFalse();
        });
    }

    @Test
    void non_test_scope_test_jar_moves_to_test_dependencies() {
        var result = importer.importFromBytes(pom("compile", null, "test-jar").getBytes(StandardCharsets.UTF_8));
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

    @Test
    void a_release_17_pom_writes_the_level_and_no_jdk_pin() {
        JkBuild imported = importPom(levelPom("<maven.compiler.release>17</maven.compiler.release>"));
        String rendered = JkBuildRenderer.render(imported);
        assertThat(rendered).contains("java     = 17").doesNotContain("jdk");
        assertThat(JkBuildParser.parse(rendered).project().jdk()).isNull();
    }

    @Test
    void a_source_8_pom_is_raised_to_the_floor_with_a_row() {
        var result = importer.importFromBytes(levelPom(
                        "<maven.compiler.source>1.8</maven.compiler.source><maven.compiler.target>1.8</maven.compiler.target>")
                .getBytes(StandardCharsets.UTF_8));
        String rendered = JkBuildRenderer.render(result.jkBuild());
        assertThat(rendered).contains("java     = 17").doesNotContain("jdk");
        assertThat(JkBuildParser.parse(rendered).project().java()).isEqualTo(17);
        assertThat(result.report().issues())
                .extracting(i -> i.message())
                .anyMatch(
                        m -> m.equals(
                                "`maven.compiler.target` declared 8; jk's floor is 17; bytecode level raised — written as `java = 17`."));
    }

    private static String levelPom(String properties) {
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0.0</version>
                  <properties>%s</properties>
                </project>
                """.formatted(properties);
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

    private JkBuild importPom(String xml) {
        return importer.importFromBytes(xml.getBytes(StandardCharsets.UTF_8)).jkBuild();
    }
}
