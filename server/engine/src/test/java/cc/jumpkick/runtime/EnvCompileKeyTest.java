// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.RequestShell;
import cc.jumpkick.engine.plugin.ModuleEnvKeys;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.task.ActionKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A name a module declares in {@code [env]} reaches its compiler worker, so an annotation processor
 * that reads it compiles differently per value: the compile key moves with the value when a
 * processor may run, never carries the value in clear, and stays put for a compile nothing in the
 * worker can read the environment from, or for a variable only the tests get.
 */
class EnvCompileKeyTest {

    private static final String MANIFEST = """
            name    = "demo"
            group   = "com.demo"
            version = "0.1.0"
            java    = 25

            [env]
            optional = ["GEN_MODE"]

            [test]
            env = ["TEST_ONLY"]
            """;

    @Test
    void a_declared_env_value_the_compiler_worker_sees_moves_the_key_of_a_compile_that_runs_processors(
            @TempDir Path project) throws Exception {
        JkBuild build = parse(project);
        Path src = source(project);
        Map<String, String> a = Map.of("PATH", "/bin", "GEN_MODE", "A");
        Map<String, String> b = Map.of("PATH", "/bin", "GEN_MODE", "B");

        Map<String, String> envA = RequestShell.where(a, () -> workerEnv(build, project));
        assertThat(envA).as("the compiler worker sees the declared value").containsEntry("GEN_MODE", "A");

        List<String> processing = List.of("-proc:full");
        String keyA = RequestShell.where(a, () -> compileKey(build, project, src, processing));
        String keyB = RequestShell.where(b, () -> compileKey(build, project, src, processing));
        assertThat(keyA)
                .as("two values of a declared name the processors can read")
                .isNotEqualTo(keyB);
        assertThat(RequestShell.where(a, () -> compileKey(build, project, src, processing)))
                .as("an unchanged value keys alike")
                .isEqualTo(keyA);
    }

    @Test
    void a_compile_that_runs_no_processor_keys_no_environment(@TempDir Path project) throws Exception {
        JkBuild build = parse(project);
        Path src = source(project);

        String keyA = RequestShell.where(Map.of("GEN_MODE", "A"), () -> compileKey(build, project, src, List.of()));
        String keyB = RequestShell.where(Map.of("GEN_MODE", "B"), () -> compileKey(build, project, src, List.of()));

        assertThat(keyA).as("plain javac reads nothing of the environment").isEqualTo(keyB);
    }

    @Test
    void the_tokens_carry_only_what_reaches_the_workers_and_never_a_value_in_clear(@TempDir Path project)
            throws Exception {
        JkBuild build = parse(project);

        List<String> tokens = RequestShell.where(
                Map.of("GEN_MODE", "secret-mode-value", "TEST_ONLY", "x"), () -> ModuleEnvKeys.worker(build, project));
        List<String> withoutTestOnly =
                RequestShell.where(Map.of("GEN_MODE", "secret-mode-value"), () -> ModuleEnvKeys.worker(build, project));

        assertThat(tokens)
                .singleElement()
                .asString()
                .startsWith("env:GEN_MODE=")
                .doesNotContain("secret-mode-value");
        assertThat(withoutTestOnly)
                .as("a [test] env name moves no worker key but the suite's")
                .isEqualTo(tokens);
        assertThat(RequestShell.where(Map.of(), () -> ModuleEnvKeys.worker(build, project)))
                .as("an unset forwarded name keys apart from a set one")
                .isNotEqualTo(tokens);
    }

    @Test
    void a_module_that_declares_nothing_has_no_tokens(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("jk.toml"), "name = \"demo\"\ngroup = \"com.demo\"\nversion = \"0.1.0\"\n");
        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));

        assertThat(ModuleEnvKeys.worker(build, project)).isEmpty();
    }

    private static JkBuild parse(Path project) throws Exception {
        Files.writeString(project.resolve("jk.toml"), MANIFEST);
        return JkBuildParser.parse(project.resolve("jk.toml"));
    }

    private static Path source(Path project) throws Exception {
        Path src = Files.createDirectories(project.resolve("src/main/java/app"));
        Files.writeString(src.resolve("App.java"), "package app; public class App {}\n");
        return src;
    }

    private static Map<String, String> workerEnv(JkBuild build, Path project) {
        return WorkerEnv.forModule(build.build().env(), project, project.resolve("target"))
                .compilerEnvironment();
    }

    private static String compileKey(JkBuild build, Path project, Path src, List<String> javacArgs) throws Exception {
        CompileRequest request = PlannerCompile.mainCompileRequest(new CompileInputs.MainCompile(
                List.of(src.resolve("App.java")),
                List.of(),
                List.of(),
                List.of(),
                BuildLayout.of(project, build),
                project.resolve("target/classes"),
                25,
                javacArgs,
                build.build().javac(),
                Path.of(Objects.requireNonNull(System.getProperty("java.home"), "java.home")),
                false,
                false,
                null,
                null,
                null,
                false,
                ModuleEnvKeys.worker(build, project)));
        return ActionKey.forJavac("compile-java", request, "test");
    }
}
