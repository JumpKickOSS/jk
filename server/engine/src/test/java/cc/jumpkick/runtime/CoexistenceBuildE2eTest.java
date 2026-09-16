// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.workspace.ExplainReport;
import cc.jumpkick.wire.runtime.TaskForecast;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end: a directory with a {@code pom.xml} and no {@code jk.toml} builds and tests in place.
 * The manifest is the shadow rendered from the effective POM under {@code target/jk/shadow/}, the
 * lock sits beside it, the repository gains no {@code jk.toml} or {@code jk-lock.toml}, the POM's
 * direct versions win ({@code pins = "nearest"}), a Tier-3 row of the import report is one warning
 * with the {@code jk import} remedy, and {@code jk explain} plans the same steps a {@code jk.toml}
 * module with the same manifest gets.
 *
 * <p>Network: jspecify and JUnit come from Maven Central into the cache under {@code build/}, which
 * persists across runs so repeats are warm.
 */
@Tag("integration")
class CoexistenceBuildE2eTest {

    private static final String POM =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>greeter</artifactId>
              <version>1.0.0</version>
              <properties>
                <maven.compiler.release>25</maven.compiler.release>
                <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
              </properties>
              <dependencies>
                <dependency>
                  <groupId>org.jspecify</groupId>
                  <artifactId>jspecify</artifactId>
                  <version>1.0.0</version>
                </dependency>
                <dependency>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter</artifactId>
                  <version>6.1.3</version>
                  <scope>test</scope>
                </dependency>
              </dependencies>
              <build>
                <extensions>
                  <extension>
                    <groupId>org.apache.maven.wagon</groupId>
                    <artifactId>wagon-ssh</artifactId>
                    <version>3.5.3</version>
                  </extension>
                </extensions>
              </build>
            </project>
            """;

    @BeforeAll
    static void engineShadowSource() {
        ShadowManifests.install();
    }

    @Test
    void a_pom_only_module_builds_tests_and_explains_in_place(@TempDir Path tmp) throws Exception {
        Path project = writeProject(tmp.resolve("greeter"));
        Path cache = cache();

        // The first reader renders the shadow; `jk explain` then plans the module the way it plans
        // a jk.toml module with the same manifest.
        Path shadow = ManifestPaths.manifestIn(project);
        assertThat(shadow).isEqualTo(ManifestPaths.shadowManifestPath(project)).isRegularFile();
        String toml = Files.readString(shadow);
        assertThat(toml).startsWith("# shadow of pom.xml ");
        assertThat(toml).contains("pins = \"nearest\"");
        Path twin = tmp.resolve("twin");
        copyTree(project.resolve("src"), twin.resolve("src"));
        Files.writeString(twin.resolve("jk.toml"), toml.substring(toml.indexOf('\n') + 1));
        assertThat(stepNames(explain(project, cache))).isNotEmpty().isEqualTo(stepNames(explain(twin, cache)));

        BuildPlan first = plan(project, cache, false);
        BuildPlanResult built = first.run();

        assertThat(built.errors()).isEmpty();
        assertThat(built.success()).isTrue();
        assertThat(project.resolve("target/classes/main/com/example/Greeter.class")).exists();
        TestSummary tests = first.get(BuildPlanner.TEST_RESULT).orElseThrow();
        assertThat(tests.total()).isEqualTo(1);
        assertThat(tests.failed()).isZero();

        // The repository is not dirtied; the lock lives beside the shadow under target/.
        assertThat(project.resolve("jk.toml")).doesNotExist();
        assertThat(project.resolve("jk-lock.toml")).doesNotExist();
        assertThat(LockPaths.lockFile(project)).isEqualTo(shadow.resolveSibling("jk-lock.toml")).isRegularFile();

        // What the effective POM declares that the in-place build does not carry is one warning
        // with the remedy, on the build after the POM changed, and not again.
        assertThat(messages(built))
                .filteredOn(w -> w.contains("<build><extensions>"))
                .hasSize(1)
                .allSatisfy(w -> assertThat(w).contains("jk import pom.xml"));

        BuildPlan second = plan(project, cache, false);
        BuildPlanResult again = second.run();
        assertThat(again.success()).isTrue();
        assertThat(messages(again)).noneMatch(w -> w.contains("<build><extensions>"));
        assertThat(Files.readString(shadow)).isEqualTo(toml);
    }

    @Test
    void a_reactor_root_is_refused_with_the_import_remedy(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("reactor"));
        Files.writeString(root.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>reactor</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>api</module>
                  </modules>
                </project>
                """);

        assertThatThrownBy(() -> ShadowManifests.materialize(root))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("<modules>")
                .hasMessageContaining("jk import pom.xml");
        assertThat(ManifestPaths.shadowManifestPath(root)).doesNotExist();
    }

    private static Path writeProject(Path project) throws Exception {
        Files.createDirectories(project);
        Files.writeString(project.resolve("pom.xml"), POM);
        Path main = Files.createDirectories(project.resolve("src/main/java/com/example"));
        Files.writeString(main.resolve("Greeter.java"), """
                package com.example;

                import org.jspecify.annotations.Nullable;

                public final class Greeter {
                    public static String greet(@Nullable String name) {
                        return "hello " + (name == null ? "jk" : name);
                    }
                }
                """);
        Path test = Files.createDirectories(project.resolve("src/test/java/com/example"));
        Files.writeString(test.resolve("GreeterTest.java"), """
                package com.example;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                class GreeterTest {
                    @Test
                    void greets_the_default_name() {
                        assertEquals("hello jk", Greeter.greet(null));
                    }
                }
                """);
        return project;
    }

    private static List<String> messages(BuildPlanResult result) {
        return result.warnings().stream()
                .map(BuildPlanResult.Diagnostic::message)
                .map(m -> m == null ? "" : m)
                .toList();
    }

    private static void copyTree(Path from, Path to) throws Exception {
        try (var walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target);
            }
        }
    }

    private static Path cache() throws Exception {
        return Files.createDirectories(Path.of("build/test-cache/coexistence"));
    }

    private static BuildPlan plan(Path project, Path cache, boolean skipTests) {
        Path buildFile = ManifestPaths.manifestIn(project);
        Path lockFile = LockPaths.lockFile(project);
        BuildPlanner.Inputs in = new BuildPlanner.Inputs(
                project,
                cache,
                buildFile,
                lockFile,
                Objects.requireNonNull(lockFile.getParent(), "lock dir"),
                1,
                0,
                null,
                null,
                skipTests,
                false,
                false,
                false,
                Set.of(),
                SessionContext.current());
        return BuildPlanner.fullPlan(in);
    }

    private static ExplainReport explain(Path dir, Path cache) throws Exception {
        JkBuild build = JkBuildParser.parse(ManifestPaths.manifestIn(dir));
        Session session = Session.defaults().withWorkingDir(dir).withCacheDir(cache);
        return ExplainReport.compute(dir, build, cache, session, ExplainReport.Knobs.defaults());
    }

    private static List<String> stepNames(ExplainReport report) {
        assertThat(report.plan().errors()).isEmpty();
        return report.plan().modules().stream()
                .flatMap(m -> m.steps().stream())
                .map(TaskForecast.Task::name)
                .toList();
    }
}
