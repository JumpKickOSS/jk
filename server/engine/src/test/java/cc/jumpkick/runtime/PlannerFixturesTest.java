// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.ActionKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fixtures compile to a directory; a consumer's compile-test key must move when that directory
 * changes, and the forecast request is the same body the build uses.
 */
class PlannerFixturesTest {

    @Test
    void editing_producer_fixtures_moves_the_consumer_compile_test_key(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.ex"
                name = "ws"
                version = "0.1.0"
                java = 25

                [workspace]
                modules = ["lib", "app"]
                """);
        Path lib = Files.createDirectories(root.resolve("lib"));
        Files.writeString(lib.resolve("jk.toml"), """
                group = "com.ex"
                name = "lib"
                version = "0.1.0"
                java = 25

                [test]
                fixtures = true
                """);
        Path app = Files.createDirectories(root.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), """
                group = "com.ex"
                name = "app"
                version = "0.1.0"
                java = 25

                [dependencies]
                lib = { workspace = true }

                [test-dependencies]
                lib = { workspace = true, fixtures = true }
                """);
        Path appTest = Files.createDirectories(app.resolve("src/test/java/com/ex"));
        Path suite = Files.writeString(appTest.resolve("AppTest.java"), "class AppTest {}\n");

        JkBuild libBuild = JkBuildParser.parse(lib.resolve("jk.toml"));
        BuildLayout libLayout = BuildLayout.of(lib, libBuild);
        Files.createDirectories(libLayout.mainJar().getParent());
        Files.writeString(libLayout.mainJar(), "jar");
        Path fixturesOut = libLayout.testFixturesClassesDir();
        Files.createDirectories(fixturesOut);
        Path helper = Files.writeString(fixturesOut.resolve("Helper.class"), "one");

        JkBuild appBuild = JkBuildParser.parse(app.resolve("jk.toml"));
        String before = consumerCompileTestKey(app, appBuild, suite);
        Files.writeString(helper, "two");
        assertThat(consumerCompileTestKey(app, appBuild, suite))
                .as("consumer compile-test key after editing only the sibling fixtures output")
                .isNotEqualTo(before);
    }

    @Test
    void fixtures_compile_request_is_the_forecast_request(@TempDir Path tmp) throws Exception {
        Path module = Files.createDirectories(tmp.resolve("m"));
        Files.writeString(module.resolve("jk.toml"), """
                group = "t"
                name = "m"
                version = "0.1.0"
                java = 25

                [test]
                fixtures = true
                """);
        Path src = Files.createDirectories(module.resolve("src/fixtures/java"));
        Path helper = Files.writeString(src.resolve("Helper.java"), "class Helper {}\n");
        JkBuild project = JkBuildParser.parse(module.resolve("jk.toml"));
        BuildLayout layout = BuildLayout.of(module, project);
        Path jdk = Files.createDirectories(tmp.resolve("jdk"));
        Files.writeString(jdk.resolve("release"), "JAVA_VERSION=\"25\"\n");
        List<Path> sources = PlannerFixtures.sources(project, module);
        assertThat(sources).containsExactly(helper);

        CompileRequest req = PlannerFixtures.fixturesCompileRequest(
                sources,
                List.of(layout.classesDir()),
                List.of(),
                layout.testFixturesClassesDir(),
                25,
                List.of("-Xlint:all"),
                project.build().javac(),
                jdk);
        assertThat(req.javaHome()).isEqualTo(jdk);
        assertThat(req.sources()).containsExactly(helper);
        assertThat(req.outputDir()).isEqualTo(layout.testFixturesClassesDir());
        assertThat(req.classpath()).containsExactly(layout.classesDir());
        String key = ActionKey.forJavac(
                ActionKey.qualifiedTaskId(TaskNames.COMPILE_TEST_FIXTURES, req.outputDir()),
                req,
                BuildIdentity.cacheKeyVersion());
        CompileRequest again = PlannerFixtures.fixturesCompileRequest(
                PlannerFixtures.forecastSources(project, module),
                List.of(layout.classesDir()),
                List.of(),
                layout.testFixturesClassesDir(),
                25,
                List.of("-Xlint:all"),
                project.build().javac(),
                jdk);
        assertThat(ActionKey.forJavac(
                        ActionKey.qualifiedTaskId(TaskNames.COMPILE_TEST_FIXTURES, again.outputDir()),
                        again,
                        BuildIdentity.cacheKeyVersion()))
                .isEqualTo(key);
    }

    private static String consumerCompileTestKey(Path appDir, JkBuild app, Path suite) throws Exception {
        var sib = WorkspaceClasspath.resolve(appDir, app, Set.of(Scope.EXPORT, Scope.MAIN, Scope.TEST));
        List<Path> cp = new ArrayList<>(sib.siblingClosureClasses());
        CompileRequest req = CompileRequest.builder()
                .sources(List.of(suite))
                .classpath(cp)
                .outputDir(BuildLayout.of(appDir, app).testClassesDir())
                .release(25)
                .build();
        return ActionKey.forJavac("compile-test", req, "test");
    }

    @Test
    void fixtures_run_the_main_javac_plugins_not_the_test_view(@TempDir Path tmp) throws Exception {
        Path module = Files.createDirectories(tmp.resolve("m"));
        Files.writeString(module.resolve("jk.toml"), """
                group = "t"
                name = "m"
                version = "0.1.0"
                java = 25

                [test]
                fixtures = true

                [javac]
                plugins = { ErrorProne = { options = ["-Xep:NullAway:ERROR"] } }

                [javac.test]
                plugins = {}
                """);
        JkBuild project = JkBuildParser.parse(module.resolve("jk.toml"));
        BuildLayout layout = BuildLayout.of(module, project);
        Path jdk = Files.createDirectories(tmp.resolve("jdk"));
        Files.writeString(jdk.resolve("release"), "JAVA_VERSION=\"25\"\n");
        CompileRequest req = PlannerFixtures.fixturesCompileRequest(
                List.of(),
                List.of(layout.classesDir()),
                List.of(),
                layout.testFixturesClassesDir(),
                25,
                List.of("-Xlint:all"),
                project.build().javac(),
                jdk);
        assertThat(req.extraOptions())
                .as("fixtures are production code for the suites that take them")
                .containsExactly("-Xlint:all", "-Xplugin:ErrorProne -Xep:NullAway:ERROR");
        assertThat(project.build().javac().forTests().isEmpty()).isTrue();
    }
}
