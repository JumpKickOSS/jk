// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.runtime.base.IdeOps;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The IDE model carries the flags and variables {@code jk run} and {@code jk test} give a module's
 * JVMs: its {@code [application] jvm-args} and its packager's run system properties, and its {@code
 * [test]} and suites' JVM settings.
 */
class IdeOpsRunJvmArgsTest {

    @Test
    void each_module_s_jvm_args_are_rows_under_its_own_index_in_order(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.ex"
                name = "ws"
                version = "0.1.0"

                [workspace]
                modules = ["lib", "app"]
                """);
        module(root, "lib", "");
        module(root, "app", """
                [application]
                main = "com.ex.Main"
                jvm-args = ["-Xmx512m", "--enable-native-access=ALL-UNNAMED"]
                """);
        Map<Path, JkBuild> modules = WorkspaceLoader.loadModules(root, JkBuildParser.parse(root.resolve("jk.toml")));
        List<Path> dirs = new ArrayList<>(List.of(root.resolve("lib"), root.resolve("app")));

        assertThat(IdeOps.runJvmArgs(dirs, modules))
                .containsExactly("1|-Xmx512m", "1|--enable-native-access=ALL-UNNAMED");
    }

    @Test
    void a_packaging_plugin_s_run_system_properties_follow_the_jvm_args(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("jk.toml"), """
                name = "gnotes"
                group = "com.ex"
                version = "0.1.0"

                [application]
                main = "com.ex.Application"
                jvm-args = ["-Xmx512m"]

                [grails]
                version = "8.0.0"
                """);
        Map<Path, JkBuild> modules = Map.of(root, JkBuildParser.parse(root.resolve("jk.toml")));

        assertThat(IdeOps.runJvmArgs(List.of(root), modules))
                .containsExactly("0|-Xmx512m", "0|-Dgrails.project.target.dir=target");
    }

    @Test
    void the_test_jvm_s_flags_and_env_are_the_module_s_then_each_suite_s(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("jk.toml"), """
                name = "app"
                group = "com.ex"
                version = "0.1.0"

                [test]
                jvm-args = ["-Xmx1g"]
                system-properties = { "spring.profiles.active" = "test" }
                env = [{ OUT = "${target}/out" }, { MISSING = "${JK_IDE_OPS_TEST_UNSET_VAR}" }, "FORWARDED"]

                [test.suites.it]
                tags = ["it"]
                jvm-args = ["-Xmx2g"]
                system-properties = { mode = "it" }
                env = { DB = "h2" }
                """);
        Map<Path, JkBuild> modules = Map.of(root, JkBuildParser.parse(root.resolve("jk.toml")));

        assertThat(IdeOps.testJvmArgs(List.of(root), modules))
                .containsExactly(
                        "0||-Duser.language=en",
                        "0||-Duser.country=US",
                        "0||-Duser.timezone=UTC",
                        "0||-Xmx1g",
                        "0||-Dspring.profiles.active=test",
                        "0|it|-Xmx2g",
                        "0|it|-Dmode=it");
        assertThat(IdeOps.testEnv(List.of(root), modules))
                .as("an unset ${VAR} and a forwarded name are left to the IDE")
                .containsExactly("0||OUT=" + root.resolve("target") + "/out", "0|it|DB=h2");
    }

    private static void module(Path root, String name, String tables) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.ex"
                name = "%s"
                version = "0.1.0"

                %s""".formatted(name, tables));
    }
}
