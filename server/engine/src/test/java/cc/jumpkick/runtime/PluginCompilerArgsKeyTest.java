// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.manifest.PluginContributions;
import cc.jumpkick.plugin.manifest.PluginDescriptorStore;
import cc.jumpkick.task.ActionKey;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A pinned plugin's {@code [[contribute.compiler-args]]} reaches the consumer's compile: the parse
 * that follows the manifest's materialization sees the plugin without being told to re-read, and
 * the contributed javac args are part of the compile action key.
 */
class PluginCompilerArgsKeyTest {

    private static final String WITH_PARAMETERS = manifest("[\"-parameters\"]");
    private static final String WITHOUT_PARAMETERS = manifest("[]");

    private static String manifest(String javac) {
        return """
                [plugin]
                id      = "hello"
                table   = "hello"
                version = "1.0.0"

                [schema]
                greeting = { type = "string", default = "hi" }

                [[contribute.compiler-args]]
                javac = %s
                """.formatted(javac);
    }

    @Test
    void the_parse_after_materialization_sees_the_plugin_without_a_reparse(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("proj"));
        String hex = pin(project, "hello-a");
        Path toml = project.resolve("jk.toml");

        // Declared, not yet locked: the table validates softly and contributes nothing.
        JkBuild before = JkBuildParser.parse(toml);
        assertThat(before.pluginConfig("hello")).isEmpty();
        assertThat(PluginContributions.javacArgs(before, project, Set.of())).doesNotContain("-parameters");

        // The lock pins the plugin and the engine extracts its manifest; jk.toml is untouched.
        lockAndMaterialize(project, hex, WITH_PARAMETERS);

        JkBuild after = JkBuildParser.parse(toml);
        assertThat(after.pluginConfig("hello"))
                .as("a plain parse sees the materialized plugin")
                .isPresent();
        assertThat(PluginContributions.javacArgs(after, project, Set.of())).contains("-parameters");
    }

    @Test
    void toggling_a_plugins_compiler_args_changes_the_compile_action_key(@TempDir Path tmp) throws Exception {
        String with = compileKey(tmp.resolve("with"), "hello-a", WITH_PARAMETERS);
        String withAgain = compileKey(tmp.resolve("with-again"), "hello-b", WITH_PARAMETERS);
        String without = compileKey(tmp.resolve("without"), "hello-c", WITHOUT_PARAMETERS);

        assertThat(with)
                .as("the plugin jar's identity is not the key; its contribution is")
                .isEqualTo(withAgain);
        assertThat(with).as("a contribution change recompiles").isNotEqualTo(without);
    }

    /** The compile-main action key of a one-class module under a plugin whose manifest is {@code manifest}. */
    private static String compileKey(Path project, String jarContent, String manifest) throws Exception {
        Files.createDirectories(project);
        String hex = pin(project, jarContent);
        lockAndMaterialize(project, hex, manifest);
        Path src = Files.createDirectories(project.resolve("src/main/java/app"));
        Files.writeString(src.resolve("App.java"), """
                package app;
                public class App { public static String greet(String who) { return "hi " + who; } }
                """);
        JkBuild build = JkBuildParser.reparse(project.resolve("jk.toml"));
        Lockfile lock = LockfileReader.read(project.resolve("jk-lock.toml"));
        List<String> javacArgs = PlannerSetup.effectiveJavacArgs(build, project, lock, null);
        CompileRequest request = PlannerCompile.mainCompileRequest(new PlannerCompile.MainCompile(
                List.of(src.resolve("App.java")),
                List.of(),
                List.of(),
                BuildLayout.of(project, build),
                project.resolve("target/classes/main"),
                25,
                javacArgs,
                build.build().javac(),
                Path.of(Objects.requireNonNull(System.getProperty("java.home"), "java.home")),
                false,
                false,
                null,
                null));
        return ActionKey.forJavac("compile-java", request, "test");
    }

    /** Write the pinned jar stand-in and a jk.toml declaring it under [plugins]; returns the pin. */
    private static String pin(Path project, String jarContent) throws Exception {
        Path jar = Files.writeString(project.resolve("hello.jar"), jarContent, StandardCharsets.UTF_8);
        String hex = Hashing.sha256Hex(jar);
        Files.writeString(project.resolve("jk.toml"), """
                name    = "demo"
                group   = "com.demo"
                version = "0.1.0"
                java    = 25

                [plugins]
                hello = { path = "hello.jar", sha256 = "%s" }

                [hello]
                greeting = "yo"
                """.formatted(hex));
        return hex;
    }

    /** What `jk lock` leaves behind for a path pin: the lock row and the materialized manifest. */
    private static void lockAndMaterialize(Path project, String hex, String manifest) throws Exception {
        LockfileWriter.write(
                new Lockfile(
                        Lockfile.CURRENT_VERSION,
                        "test",
                        Lockfile.RESOLUTION_ALGORITHM,
                        null,
                        null,
                        List.of(),
                        List.of(new Lockfile.PluginEntry("path:hello", "local", "sha256:" + hex))),
                project.resolve("jk-lock.toml"));
        Files.createDirectories(PluginDescriptorStore.storeDir(project));
        Files.writeString(PluginDescriptorStore.fileFor(project, hex), manifest);
    }
}
