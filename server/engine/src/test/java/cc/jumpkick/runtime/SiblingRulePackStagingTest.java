// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.runtime.base.TestEnv;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A rule pack listed in {@code test-plugin-jars} is staged into the module's sandbox store, where a
 * scaffolded project's lock finds a first-party pack; a worker listed beside it is not.
 */
class SiblingRulePackStagingTest {

    private static void writeManifest(Path dir, String toml) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("jk.toml"), toml);
    }

    @Test
    void a_sibling_rule_pack_lands_on_the_sandbox_store_s_jk_local_shelf(@TempDir Path tmp) throws IOException {
        Path root = tmp.resolve("ws");
        writeManifest(root, """
                group   = "cc.jumpkick"
                name    = "jk"
                version = "1.2.3"

                [workspace]
                modules = ["packs/quarkus", "host"]
                """);
        writeManifest(root.resolve("packs/quarkus"), """
                group   = "cc.jumpkick.guards"
                name    = "quarkus"
                version = "1.2.3"
                """);
        writeManifest(root.resolve("host"), """
                group   = "cc.jumpkick"
                name    = "host"
                version = "1.2.3"

                [build]
                test-plugin-jars = ["test-runner", "cc.jumpkick.guards:quarkus"]
                """);
        Path packDir = root.resolve("packs/quarkus");
        Path packJar = BuildLayout.of(packDir, JkBuildParser.parse(packDir.resolve("jk.toml")))
                .mainJar();
        Files.createDirectories(packJar.getParent());
        Files.write(packJar, new byte[] {1, 2, 3, 4});

        Path home = tmp.resolve("home");
        Map<String, String> env = Map.of(TestEnv.JK_HOME, home.toString());
        JkBuild host = JkBuildParser.parse(root.resolve("host/jk.toml"));

        PlannerSupport.stageSiblingRulePacks(root.resolve("host"), host, env);

        Path store = JkDirs.of(env::get, tmp.toString()).storeDir();
        Path staged = store.resolve("repos/jk-local/cc/jumpkick/guards/quarkus/1.2.3/quarkus-1.2.3.jar");
        assertThat(staged).exists().hasBinaryContent(new byte[] {1, 2, 3, 4});
        assertThat(store.resolve("repos/jk-local/cc/jumpkick/jk-test-runner"))
                .as("a worker is wired by property, not staged")
                .doesNotExist();
    }

    @Test
    void a_module_outside_a_workspace_stages_nothing(@TempDir Path tmp) throws IOException {
        writeManifest(tmp.resolve("solo"), """
                group   = "com.example"
                name    = "solo"
                version = "1.0.0"

                [build]
                test-plugin-jars = ["cc.jumpkick.guards:quarkus"]
                """);
        JkBuild solo = JkBuildParser.parse(tmp.resolve("solo/jk.toml"));
        Path home = tmp.resolve("home");
        PlannerSupport.stageSiblingRulePacks(tmp.resolve("solo"), solo, Map.of(TestEnv.JK_HOME, home.toString()));
        assertThat(home).doesNotExist();
    }
}
