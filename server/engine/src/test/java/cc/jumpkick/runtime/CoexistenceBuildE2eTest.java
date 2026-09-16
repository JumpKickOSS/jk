// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.workspace.ExplainReport;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.TaskForecast;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
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
 * module with the same manifest gets. A reactor builds as a workspace whose root shadow lists the
 * leaves and whose lock sits beside the root shadow; a shadow lists the POM files it read, so an
 * edit to a relative-path parent re-renders the child.
 *
 * <p>Network: jspecify and JUnit come from Maven Central into the cache under {@code build/}, which
 * persists across runs so repeats are warm.
 */
@Tag("integration")
class CoexistenceBuildE2eTest {

    private static final String POM = """
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
        assertThat(project.resolve("target/classes/main/com/example/Greeter.class"))
                .exists();
        TestSummary tests = first.get(BuildPlanner.TEST_RESULT).orElseThrow();
        assertThat(tests.total()).isEqualTo(1);
        assertThat(tests.failed()).isZero();

        // The repository is not dirtied; the lock lives beside the shadow under target/.
        assertThat(project.resolve("jk.toml")).doesNotExist();
        assertThat(project.resolve("jk-lock.toml")).doesNotExist();
        assertThat(LockPaths.lockFile(project))
                .isEqualTo(shadow.resolveSibling("jk-lock.toml"))
                .isRegularFile();

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
    void a_reactor_builds_as_a_workspace_with_the_lock_beside_the_root_shadow(@TempDir Path tmp) throws Exception {
        Path root = writeReactor(tmp.resolve("reactor"));
        Path cache = cache();

        Path rootShadow = ManifestPaths.manifestIn(root);
        assertThat(rootShadow).isEqualTo(ManifestPaths.shadowManifestPath(root)).isRegularFile();
        assertThat(Files.readString(rootShadow)).contains("[workspace]").contains("modules = [\"api\", \"app\"]");
        assertThat(ManifestPaths.manifestIn(root.resolve("app")))
                .isEqualTo(ManifestPaths.shadowManifestPath(root.resolve("app")))
                .isRegularFile();
        assertThat(Files.readString(ManifestPaths.manifestIn(root.resolve("app"))))
                .contains("api.workspace = true");
        assertThat(LockPaths.lockOwnerDir(root.resolve("app"))).isEqualTo(root);
        assertThat(LockPaths.lockFile(root.resolve("app"))).isEqualTo(rootShadow.resolveSibling("jk-lock.toml"));

        Steps steps = new Steps();
        WorkspaceResult built = WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(root, cache, null, 0, null, false, false, 2, null, false, true), steps);

        assertThat(built.errors()).isEmpty();
        assertThat(built.success()).isTrue();
        assertThat(built.modules())
                .extracting(m -> m.dir().getFileName().toString())
                .containsExactlyInAnyOrder("api", "app");
        for (String module : List.of("api", "app")) {
            assertThat(steps.status(module, TaskNames.COMPILE_JAVA)).isEqualTo(TaskStatus.SUCCESS);
            assertThat(steps.status(module, TaskNames.RUN_TESTS)).isEqualTo(TaskStatus.SUCCESS);
        }
        Path appDir = root.resolve("app");
        BuildLayout app = BuildLayout.of(root, appDir, JkBuildParser.parse(ManifestPaths.manifestIn(appDir)));
        assertThat(app.classesDir().resolve("com/example/app/Main.class")).exists();

        assertThat(LockPaths.lockFile(root))
                .isEqualTo(rootShadow.resolveSibling("jk-lock.toml"))
                .isRegularFile();
        assertThat(root.resolve("jk.toml")).doesNotExist();
        assertThat(root.resolve("jk-lock.toml")).doesNotExist();
        assertThat(root.resolve("api/jk.toml")).doesNotExist();
        assertThat(root.resolve("app/jk.toml")).doesNotExist();
        assertThat(root.resolve("app/jk-lock.toml")).doesNotExist();
        assertThat(ManifestPaths.shadowDir(appDir).resolve("jk-lock.toml")).doesNotExist();
    }

    @Test
    void editing_a_relative_path_parent_re_renders_the_child_shadow(@TempDir Path tmp) throws Exception {
        Path parent = Files.createDirectories(tmp.resolve("parent"));
        Path child = writeProject(tmp.resolve("child"));
        Files.writeString(parent.resolve("pom.xml"), parentPom("1.0.0"));
        Files.writeString(child.resolve("pom.xml"), CHILD_OF_PARENT);

        Path shadow = ManifestPaths.manifestIn(child);
        String first = Files.readString(shadow);
        assertThat(first).contains("# read pom.xml\n").contains("# read ../parent/pom.xml\n");
        assertThat(first).contains("jspecify").doesNotContain("\"0.3.0\"");
        assertThat(ManifestPaths.manifestIn(child)).isEqualTo(shadow);
        assertThat(Files.readString(shadow)).isEqualTo(first);

        Files.writeString(parent.resolve("pom.xml"), parentPom("0.3.0"));

        String second = Files.readString(ManifestPaths.manifestIn(child));
        assertThat(second).isNotEqualTo(first).contains("\"0.3.0\"");
    }

    /** A parent that manages jspecify at {@code version}; the child inherits it without a version of its own. */
    private static String parentPom(String version) {
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <properties>
                    <maven.compiler.release>25</maven.compiler.release>
                  </properties>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.jspecify</groupId>
                        <artifactId>jspecify</artifactId>
                        <version>%s</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """.formatted(version);
    }

    private static final String CHILD_OF_PARENT = """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <parent>
                <groupId>com.example</groupId>
                <artifactId>parent</artifactId>
                <version>1.0.0</version>
                <relativePath>../parent/pom.xml</relativePath>
              </parent>
              <artifactId>child</artifactId>
              <dependencies>
                <dependency>
                  <groupId>org.jspecify</groupId>
                  <artifactId>jspecify</artifactId>
                </dependency>
              </dependencies>
            </project>
            """;

    /** Two leaves under one root: {@code app} depends on {@code api}; both have a JUnit test. */
    private static Path writeReactor(Path root) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>reactor</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>api</module>
                    <module>app</module>
                  </modules>
                  <properties>
                    <maven.compiler.release>25</maven.compiler.release>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>org.junit.jupiter</groupId>
                      <artifactId>junit-jupiter</artifactId>
                      <version>6.1.3</version>
                      <scope>test</scope>
                    </dependency>
                  </dependencies>
                </project>
                """);
        Path api = Files.createDirectories(root.resolve("api"));
        Files.writeString(api.resolve("pom.xml"), leafPom("api", ""));
        Files.writeString(
                Files.createDirectories(api.resolve("src/main/java/com/example/api"))
                        .resolve("Greeting.java"),
                """
                package com.example.api;

                public final class Greeting {
                    public static String text() {
                        return "hello";
                    }
                }
                """);
        Files.writeString(
                Files.createDirectories(api.resolve("src/test/java/com/example/api"))
                        .resolve("GreetingTest.java"),
                """
                package com.example.api;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                class GreetingTest {
                    @Test
                    void says_hello() {
                        assertEquals("hello", Greeting.text());
                    }
                }
                """);
        Path app = Files.createDirectories(root.resolve("app"));
        Files.writeString(app.resolve("pom.xml"), leafPom("app", """
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>api</artifactId>
                      <version>1.0.0</version>
                    </dependency>
                """));
        Files.writeString(
                Files.createDirectories(app.resolve("src/main/java/com/example/app"))
                        .resolve("Main.java"),
                """
                package com.example.app;

                import com.example.api.Greeting;

                public final class Main {
                    public static String shout() {
                        return Greeting.text().toUpperCase();
                    }
                }
                """);
        Files.writeString(
                Files.createDirectories(app.resolve("src/test/java/com/example/app"))
                        .resolve("MainTest.java"),
                """
                package com.example.app;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                class MainTest {
                    @Test
                    void shouts() {
                        assertEquals("HELLO", Main.shout());
                    }
                }
                """);
        return root;
    }

    private static String leafPom(String artifactId, String dependencies) {
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.example</groupId>
                    <artifactId>reactor</artifactId>
                    <version>1.0.0</version>
                  </parent>
                  <artifactId>%s</artifactId>
                  <dependencies>
                %s  </dependencies>
                </project>
                """.formatted(artifactId, dependencies);
    }

    /** Step statuses per module directory name, from the workspace build's listener. */
    private static final class Steps implements WorkspaceBuildListener {
        private final Map<String, TaskStatus> statusByModuleStep = new ConcurrentHashMap<>();

        @Override
        public BuildPlanListener onModuleStart(ModulePlan module) {
            String name = module.dir().getFileName().toString();
            return new BuildPlanListener() {
                @Override
                public void stepFinish(
                        String step, @Nullable String group, TaskStatus status, Duration duration, Duration waited) {
                    statusByModuleStep.put(name + "/" + step, status);
                }
            };
        }

        @Nullable
        TaskStatus status(String module, String step) {
            return statusByModuleStep.get(module + "/" + step);
        }
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
                LockPaths.lockOwnerDir(project),
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
