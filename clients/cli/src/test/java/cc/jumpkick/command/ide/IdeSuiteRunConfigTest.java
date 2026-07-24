// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.ide;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IdeSuiteRunConfigTest {

    @Test
    void shell_run_config_embeds_command_and_cwd(@TempDir Path tmp) {
        String xml = IntellijIdeGenerator.shellRunConfigXml("jk test", "jk test --all", tmp);
        assertThat(xml).contains("ShConfigurationType");
        assertThat(xml).contains("jk test --all");
        assertThat(xml).contains(tmp.toAbsolutePath().normalize().toString().replace('\\', '/'));
    }

    @Test
    void write_jk_test_run_configs_includes_extra_suite(@TempDir Path tmp) throws Exception {
        Path mod = tmp.resolve("mod");
        Files.createDirectories(mod.resolve("test"));
        Files.writeString(mod.resolve("test/T.java"), "class T {}");
        Files.createDirectories(mod.resolve("integration"));
        Files.writeString(mod.resolve("integration/I.java"), "class I {}");
        Files.writeString(
                mod.resolve("jk.toml"),
                """
                [project]
                group = "t"
                name = "mod"
                version = "1.0.0"
                jdk = 21
                java = 21
                layout = "simple"
                """);
        Path runDir = tmp.resolve(".idea/runConfigurations");
        int n = IntellijIdeGenerator.writeJkTestRunConfigs(runDir, tmp, Set.of(mod));
        assertThat(n).isGreaterThanOrEqualTo(3);
        assertThat(Files.isRegularFile(runDir.resolve("jk_test.xml"))).isTrue();
        assertThat(Files.isRegularFile(runDir.resolve("jk_test_all.xml"))).isTrue();
        assertThat(Files.isRegularFile(runDir.resolve("jk_test_integration.xml"))).isTrue();
        String integ = Files.readString(runDir.resolve("jk_test_integration.xml"));
        assertThat(integ).contains("jk test --suite integration");
    }

    @Test
    void vscode_tasks_json_lists_default_all_and_suite(@TempDir Path tmp) throws Exception {
        Path mod = tmp.resolve("m");
        Files.createDirectories(mod.resolve("test"));
        Files.writeString(mod.resolve("test/T.java"), "class T {}");
        Files.createDirectories(mod.resolve("integration"));
        Files.writeString(mod.resolve("integration/I.java"), "class I {}");
        Files.writeString(
                mod.resolve("jk.toml"),
                """
                [project]
                group = "t"
                name = "m"
                version = "1.0.0"
                jdk = 21
                java = 21
                layout = "simple"
                """);
        IdeModule im = new IdeModule(
                "m",
                21,
                null,
                mod.resolve("target/classes/main"),
                mod.resolve("target/classes/test"),
                mod.resolve("target/jdt/classes/main"),
                mod.resolve("target/jdt/classes/test"),
                mod.resolve("target/generated-sources/annotations"),
                mod.resolve("target/generated-sources/annotations/test"));
        IdeModel model = new IdeModel(
                tmp,
                "ws",
                java.util.Map.of(mod, im),
                java.util.Map.of(mod, im),
                java.util.Map.of(),
                java.util.Map.of(),
                java.util.Map.of(),
                java.util.Map.of(),
                java.util.Map.of(),
                null,
                java.util.List.of(),
                null,
                null,
                null);
        String tasks = VscodeIdeGenerator.tasksJson(model);
        assertThat(tasks).contains("jk: test");
        assertThat(tasks).contains("jk test --all");
        assertThat(tasks).contains("jk test --suite integration");
    }
}
