// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.JkBuild;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@code [dev.sidecars]}: the table jk dev reads and nothing else does. */
class JkBuildParserDevTest {

    @Test
    void sidecars_parse_in_manifest_order_with_their_defaults() {
        JkBuild b = JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                [dev.sidecars]
                web = { command = "npm run dev", cwd = "../web", ready = "http://localhost:5173", front-door = true }
                docs = { command = ["mkdocs", "serve", "-a", "127.0.0.1:8001"], env = { PORT = "8001" }, restart = "on-exit", ready-pattern = "Serving on", ready-timeout = "2m" }
                """);
        List<JkBuild.Sidecar> sidecars = b.build().devSidecars();
        assertThat(sidecars).extracting(JkBuild.Sidecar::name).containsExactly("web", "docs");

        JkBuild.Sidecar web = sidecars.get(0);
        assertThat(web.command()).containsExactly("npm", "run", "dev");
        assertThat(web.cwd()).isEqualTo("../web");
        assertThat(web.env()).isEmpty();
        assertThat(web.ready()).isEqualTo("http://localhost:5173");
        assertThat(web.readyPattern()).isNull();
        assertThat(web.readyTimeoutMillis()).isEqualTo(JkBuild.Sidecar.DEFAULT_READY_TIMEOUT_MILLIS);
        assertThat(web.frontDoor()).isTrue();
        assertThat(web.restart()).isEqualTo(JkBuild.SidecarRestart.NEVER);

        JkBuild.Sidecar docs = sidecars.get(1);
        assertThat(docs.command()).containsExactly("mkdocs", "serve", "-a", "127.0.0.1:8001");
        assertThat(docs.cwd()).isEqualTo(".");
        assertThat(docs.env()).isEqualTo(Map.of("PORT", "8001"));
        assertThat(docs.readyPattern()).isEqualTo("Serving on");
        assertThat(docs.readyTimeoutMillis()).isEqualTo(120_000);
        assertThat(docs.restart()).isEqualTo(JkBuild.SidecarRestart.ON_EXIT);
    }

    @Test
    void a_command_string_splits_like_a_shell_without_running_one() {
        assertThat(ShellWords.split("npm run dev -- --port \"5 173\" 'a b' c\\ d"))
                .containsExactly("npm", "run", "dev", "--", "--port", "5 173", "a b", "c d");
        assertThat(ShellWords.split("run \"\" x")).containsExactly("run", "", "x");
        assertThat(ShellWords.split("x 'a \"b\"' \"c 'd'\"")).containsExactly("x", "a \"b\"", "c 'd'");
        assertThat(ShellWords.split("echo \"say \\\"hi\\\"\" \\\"bare\\\" don\\'t"))
                .containsExactly("echo", "say \"hi\"", "\"bare\"", "don't");
        assertThatThrownBy(() -> ShellWords.split("echo \"unterminated")).hasMessageContaining("unbalanced quote");
    }

    @Test
    void a_backslash_is_literal_unless_it_escapes_a_quote_a_space_or_itself() {
        assertThat(ShellWords.split("C:\\tools\\node.exe run dev"))
                .containsExactly("C:\\tools\\node.exe", "run", "dev");
        assertThat(ShellWords.split("\"a\\nb\" a\\nb")).containsExactly("a\\nb", "a\\nb");
        assertThat(ShellWords.split("a\\")).containsExactly("a\\");
        assertThat(ShellWords.split("a\\\\b \"c\\\\d\"")).containsExactly("a\\b", "c\\d");
        assertThat(ShellWords.split("\"C:\\dir\\\\\" x")).containsExactly("C:\\dir\\", "x");
    }

    @Test
    void durations_are_seconds_by_default() {
        assertThat(ManifestBuild.durationMillis("90s", "x")).isEqualTo(90_000);
        assertThat(ManifestBuild.durationMillis("2m", "x")).isEqualTo(120_000);
        assertThat(ManifestBuild.durationMillis("500ms", "x")).isEqualTo(500);
        assertThat(ManifestBuild.durationMillis(30L, "x")).isEqualTo(30_000);
        assertThatThrownBy(() -> ManifestBuild.durationMillis("soon", "x")).hasMessageContaining("duration");
    }

    @Test
    void an_unknown_key_fails_the_parse_where_it_sits() {
        assertThatThrownBy(() -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                        [dev.sidecars]
                        web = { command = "npm run dev", redy = "http://localhost:5173" }
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[dev.sidecars.web] unknown key `redy`");
        assertThatThrownBy(() -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                        [dev]
                        sidecar = { web = { command = "npm run dev" } }
                        """))
                .hasMessageContaining("[dev] unknown key `sidecar`");
    }

    @Test
    void a_sidecar_has_one_probe_and_a_command() {
        assertThatThrownBy(() -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                        [dev.sidecars]
                        web = { command = "npm run dev", ready = "http://localhost:5173", ready-pattern = "ready" }
                        """))
                .hasMessageContaining("one probe");
        assertThatThrownBy(() -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                        [dev.sidecars]
                        web = { cwd = "../web" }
                        """))
                .hasMessageContaining("needs command");
        assertThatThrownBy(() -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                        [dev.sidecars]
                        web = { command = "npm run dev", restart = "always" }
                        """))
                .hasMessageContaining("never or on-exit");
    }

    @Test
    void no_dev_table_means_no_sidecars() {
        assertThat(JkBuildParser.parse(JkBuildParserFixtures.PROJECT).build().devSidecars())
                .isEmpty();
    }
}
