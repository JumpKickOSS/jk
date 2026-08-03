// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.ide;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
        Files.createDirectories(mod.resolve("test").resolve("src"));
        Files.writeString(mod.resolve("test/src/T.java"), "class T {}");
        Files.createDirectories(mod.resolve("integration").resolve("src"));
        Files.writeString(mod.resolve("integration/src/I.java"), "class I {}");
        Files.writeString(mod.resolve("jk.toml"), """
                [project]
                group = "t"
                name = "mod"
                version = "1.0.0"
                jdk = 25
                java = 25
                layout = "simple"
                """);
        Path runDir = tmp.resolve(".idea/runConfigurations");
        int n = IntellijIdeGenerator.writeJkTestRunConfigs(runDir, tmp, Set.of(mod));
        assertThat(n).isGreaterThanOrEqualTo(3);
        assertThat(Files.isRegularFile(runDir.resolve("jk_test.xml"))).isTrue();
        assertThat(Files.isRegularFile(runDir.resolve("jk_test_all.xml"))).isTrue();
        assertThat(Files.isRegularFile(runDir.resolve("jk_test_integration.xml")))
                .isTrue();
        String integ = Files.readString(runDir.resolve("jk_test_integration.xml"));
        assertThat(integ).contains("jk test --suite integration");
    }

    @Test
    void vscode_tasks_json_lists_default_all_and_suite(@TempDir Path tmp) throws Exception {
        Path mod = tmp.resolve("m");
        Files.createDirectories(mod.resolve("test").resolve("src"));
        Files.writeString(mod.resolve("test/src/T.java"), "class T {}");
        Files.createDirectories(mod.resolve("integration").resolve("src"));
        Files.writeString(mod.resolve("integration/src/I.java"), "class I {}");
        Files.writeString(mod.resolve("jk.toml"), """
                [project]
                group = "t"
                name = "m"
                version = "1.0.0"
                jdk = 25
                java = 25
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
                Map.of(mod, im),
                Map.of(mod, im),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                null,
                List.of(),
                null,
                null,
                null);
        String tasks = VscodeIdeGenerator.tasksJson(model);
        assertThat(tasks).contains("jk: test");
        assertThat(tasks).contains("jk test --all");
        assertThat(tasks).contains("jk test --suite integration");
    }
}
