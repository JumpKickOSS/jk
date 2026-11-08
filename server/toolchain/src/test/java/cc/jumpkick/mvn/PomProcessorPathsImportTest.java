// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.compat.JkBuildRenderer;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Maven configures compile and testCompile's processor paths apart: an execution's {@code
 * <configuration>} is merged over the plugin's for that execution alone, and {@code
 * default-testCompile} reads the plugin's when the POM does not reconfigure it. The import writes
 * compile-main's path to {@code [processor-dependencies]} and, when compile-test's differs other than
 * by trailing additions, compile-test's own to {@code [test-processor-dependencies]} with {@code
 * inherit = false}, so a processor Maven never runs over the tests (Helidon's API-stability
 * enforcer) stays off them under jk.
 */
class PomProcessorPathsImportTest {

    private static final String HEAD = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.ex</groupId>
              <artifactId>app</artifactId>
              <version>1.0.0</version>
              <build><plugins><plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.14.0</version>
            """;

    private static final String TAIL = """
              </plugin></plugins></build>
            </project>
            """;

    private static final String A = path("proc-a");
    private static final String B = path("proc-b");
    private static final String ENFORCER = path("enforcer");

    @Test
    void a_default_compile_execution_that_adds_a_processor_leaves_the_tests_the_plugins_path(@TempDir Path tempDir)
            throws Exception {
        PomImporter.Result result = importXml(tempDir, HEAD + """
                <configuration><annotationProcessorPaths>%s%s</annotationProcessorPaths></configuration>
                <executions><execution>
                  <id>default-compile</id>
                  <configuration><annotationProcessorPaths>%s%s%s</annotationProcessorPaths></configuration>
                </execution></executions>
                """.formatted(A, B, A, B, ENFORCER) + TAIL);
        JkBuild build = result.jkBuild();

        assertThat(modules(build.dependencies().of(Scope.PROCESSOR)))
                .as("default-compile's own list")
                .containsExactly("com.ex:proc-a", "com.ex:proc-b", "com.ex:enforcer");
        assertThat(modules(build.dependencies().of(Scope.TEST_PROCESSOR)))
                .as("default-testCompile runs the plugin-level list")
                .containsExactly("com.ex:proc-a", "com.ex:proc-b");
        assertThat(build.dependencies().testProcessorsInherit()).isFalse();
        assertThat(messages(result)).anySatisfy(m -> assertThat(m)
                .contains("testCompile runs com.ex:proc-a, com.ex:proc-b, not compile's path")
                .contains("inherit = false"));

        String rendered = JkBuildRenderer.render(build);
        assertThat(rendered).contains("[test-processor-dependencies]\ninherit = false\nproc-a = ");
        JkBuild reparsed = JkBuildParser.parse(rendered);
        assertThat(reparsed.dependencies().testProcessorsInherit()).isFalse();
        assertThat(modules(reparsed.dependencies().of(Scope.TEST_PROCESSOR)))
                .containsExactly("com.ex:proc-a", "com.ex:proc-b");
    }

    @Test
    void paths_on_the_compile_execution_alone_leave_the_tests_discovering(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = importXml(tempDir, HEAD + """
                <executions><execution>
                  <id>default-compile</id>
                  <configuration><annotationProcessorPaths>%s%s</annotationProcessorPaths></configuration>
                </execution></executions>
                """.formatted(A, ENFORCER) + TAIL);
        JkBuild build = result.jkBuild();

        assertThat(modules(build.dependencies().of(Scope.PROCESSOR)))
                .containsExactly("com.ex:proc-a", "com.ex:enforcer");
        assertThat(build.dependencies().of(Scope.TEST_PROCESSOR))
                .as("testCompile has no annotationProcessorPaths at all")
                .isEmpty();
        assertThat(build.dependencies().testProcessorsInherit()).isFalse();
        assertThat(messages(result)).anySatisfy(m -> assertThat(m).contains("testCompile runs no declared processor"));

        String rendered = JkBuildRenderer.render(build);
        assertThat(rendered).contains("[test-processor-dependencies]\ninherit = false\n");
        assertThat(JkBuildParser.parse(rendered).dependencies().testProcessorsInherit())
                .isFalse();
    }

    @Test
    void a_test_compile_execution_appending_to_the_plugins_list_stays_additive(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = importXml(tempDir, HEAD + """
                <configuration><annotationProcessorPaths>%s</annotationProcessorPaths></configuration>
                <executions><execution>
                  <id>default-testCompile</id>
                  <configuration>
                    <annotationProcessorPaths combine.children="append">%s</annotationProcessorPaths>
                  </configuration>
                </execution></executions>
                """.formatted(A, B) + TAIL);
        JkBuild build = result.jkBuild();

        assertThat(modules(build.dependencies().of(Scope.PROCESSOR))).containsExactly("com.ex:proc-a");
        assertThat(modules(build.dependencies().of(Scope.TEST_PROCESSOR)))
                .as("compile-main's path followed by more: the extras ride on the shared one")
                .containsExactly("com.ex:proc-b");
        assertThat(build.dependencies().testProcessorsInherit()).isTrue();
        assertThat(JkBuildRenderer.render(build)).doesNotContain("inherit");
    }

    @Test
    void the_plugins_paths_alone_serve_both_compiles(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = importXml(tempDir, HEAD + """
                <configuration><annotationProcessorPaths>%s</annotationProcessorPaths></configuration>
                """.formatted(A) + TAIL);
        JkBuild build = result.jkBuild();

        assertThat(modules(build.dependencies().of(Scope.PROCESSOR))).containsExactly("com.ex:proc-a");
        assertThat(build.dependencies().of(Scope.TEST_PROCESSOR)).isEmpty();
        assertThat(build.dependencies().testProcessorsInherit()).isTrue();
        assertThat(JkBuildRenderer.render(build)).doesNotContain("[test-processor-dependencies]");
    }

    private static String path(String artifact) {
        return "<path><groupId>com.ex</groupId><artifactId>" + artifact + "</artifactId><version>1.0</version></path>";
    }

    private static PomImporter.Result importXml(Path tempDir, String xml) throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("project"));
        Path pom = project.resolve("pom.xml");
        Files.writeString(pom, xml, StandardCharsets.UTF_8);
        return TestImporters.offline(tempDir).importFrom(pom);
    }

    private static List<String> messages(PomImporter.Result result) {
        return result.report().issues().stream()
                .map(ImportReport.Issue::message)
                .toList();
    }

    private static List<String> modules(List<Dependency> deps) {
        return deps.stream().map(Dependency::module).toList();
    }
}
