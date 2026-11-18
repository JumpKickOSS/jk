// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JumpKick's own pins are read from every place a project writes them — the rule packs it extends,
 * the {@code cc.jumpkick} coordinates its manifests pin, a plugin manifest's {@code sdk} — and
 * called behind by one comparison against the running jk.
 */
class JumpKickPinsTest {

    @Test
    void every_jumpkick_pin_of_a_workspace_is_read_and_compared_with_the_client(@TempDir Path ws) throws Exception {
        Files.writeString(ws.resolve("jk.toml"), """
                group = "com.example"
                name = "ws"
                version = "0.1.0"

                [workspace]
                modules = ["plug"]

                [dependencies]
                guava = "com.google.guava:guava:33.4.8-jre"
                """);
        Files.writeString(ws.resolve("jk-guards.toml"), """
                [guards]
                extends = ["cc.jumpkick.guards:monorepo:0.50.2", "com.acme:house:1.0.0"]
                """);
        Path plug = Files.createDirectories(ws.resolve("plug"));
        Files.writeString(plug.resolve("jk.toml"), """
                name = "plug"
                group.workspace = true
                version.workspace = true

                [dependencies]
                jk-plugin-sdk = "cc.jumpkick:jk-plugin-sdk:0.51.1"
                """);
        Files.writeString(
                Files.createDirectories(plug.resolve("src/main/resources")).resolve("jk-plugin.toml"), """
                [plugin]
                id        = "plug"
                sdk       = "0.20.0"
                jk-compat = ">=0.13"
                """);

        assertThat(JumpKickPins.declared(ws))
                .extracting(p -> p.scope(), p -> p.coordinate(), p -> p.version())
                .containsExactly(
                        tuple("guards.extends", "cc.jumpkick.guards:monorepo", "0.50.2"),
                        tuple("main", "cc.jumpkick:jk-plugin-sdk", "0.51.1"),
                        tuple("jk-plugin.toml sdk", "cc.jumpkick:jk-plugin-sdk", "0.20.0"));

        List<JumpKickPins.Pin> behind = JumpKickPins.behind(ws, "0.51.1");
        assertThat(behind).extracting(p -> p.version()).containsExactly("0.50.2", "0.20.0");
        assertThat(JumpKickPins.describe(ws, behind, "0.51.1"))
                .contains(
                        "behind this jk (0.51.1)",
                        "monorepo 0.50.2 (jk-guards.toml)",
                        "jk-plugin.toml sdk 0.20.0 (plug/src/main/resources/jk-plugin.toml)",
                        "run jk update");
        assertThat(JumpKickPins.behind(ws, "0.20.0")).isEmpty();
    }

    @Test
    void behind_compares_numeric_cores_against_a_released_jk() {
        assertThat(JumpKickPins.behind("0.51.1", "0.51.2")).isTrue();
        assertThat(JumpKickPins.behind("0.51.2", "0.51.2")).isFalse();
        assertThat(JumpKickPins.behind("0.52.0", "0.51.2")).isFalse();
        assertThat(JumpKickPins.behind("not-a-version", "0.51.2")).isFalse();
    }

    /**
     * A checkout's {@code -SNAPSHOT} jk nags about nothing: the release before it (0.52.0 under
     * 0.52.1-SNAPSHOT) may be the newest published, and moving to the client's version would pin
     * a snapshot. The ticket's every-command line was this case.
     */
    @Test
    void a_snapshot_jk_calls_no_pin_behind_and_never_advises_its_own_version(@TempDir Path ws) throws Exception {
        assertThat(JumpKickPins.behind("0.52.0", "0.52.1-SNAPSHOT")).isFalse();
        assertThat(JumpKickPins.behind("0.20.0", "0.52.1-SNAPSHOT")).isFalse();
        Files.writeString(ws.resolve("jk.toml"), "name = \"app\"\n");
        Files.writeString(
                ws.resolve("jk-guards.toml"), "[guards]\nextends = [\"cc.jumpkick.guards:library:0.52.0\"]\n");
        assertThat(JumpKickPins.declared(ws)).hasSize(1);
        assertThat(JumpKickPins.behind(ws, "0.52.1-SNAPSHOT")).isEmpty();
        assertThat(JumpKickPins.describe(ws, JumpKickPins.declared(ws), "0.52.1-SNAPSHOT"))
                .isEmpty();
    }

    @Test
    void the_target_is_the_newest_release_not_past_the_client() {
        List<String> available = List.of("0.50.2", "0.51.0", "0.51.1", "0.52.0-rc1", "0.53.0");
        assertThat(JumpKickPins.target("0.50.2", available, "0.51.1")).isEqualTo("0.51.1");
        assertThat(JumpKickPins.target("0.50.2", available, "0.51.2-SNAPSHOT")).isEqualTo("0.51.1");
        assertThat(JumpKickPins.target("0.51.1", available, "0.51.1")).isNull();
        assertThat(JumpKickPins.target("0.53.0", available, "0.51.1"))
                .as("a pin ahead of the client stays")
                .isNull();
    }

    @Test
    void moving_a_plugin_manifest_raises_a_jk_compat_floor_below_the_new_sdk_only() {
        String text = """
                [plugin]
                sdk       = "0.20.0"
                jk-compat = ">=0.13"
                """;
        assertThat(JumpKickPins.movePluginManifest(text, "0.51.1"))
                .contains("sdk       = \"0.51.1\"")
                .contains("jk-compat = \">=0.51.1\"");
        String ahead = """
                [plugin]
                sdk       = "0.50.0"
                jk-compat = ">=0.52.0"
                """;
        assertThat(JumpKickPins.movePluginManifest(ahead, "0.51.1")).contains("jk-compat = \">=0.52.0\"");
    }
}
