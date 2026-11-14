// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.DeclaredSuite;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.SuiteSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A workspace root's {@code [test.suites.<name>]} tables are every member's (JK-CWJGR). */
class WorkspaceSuitesTest {

    private static final String ROOT = """
            group    = "com.example"
            name     = "root"
            version  = "0.1.0"

            [workspace]
            modules = ["libs/a"]

            [test.suites.integration]
            tags = ["integration"]
            env = { DB = "root", SHARED = "root" }
            jvm-args = ["-Xmx1g"]
            system-properties = { mode = "root" }
            """;

    @Test
    void a_suite_declared_only_at_the_root_is_every_members(@TempDir Path ws) throws IOException {
        write(ws, ROOT, "name = \"a\"\n");

        JkBuild parsed = member(ws);
        JkBuild loaded = loaded(ws);

        DeclaredSuite integration =
                Objects.requireNonNull(parsed.build().testSuites().get("integration"));
        assertThat(integration.tags()).containsExactly("integration");
        assertThat(loaded.build().testSuites()).isEqualTo(parsed.build().testSuites());
    }

    @Test
    void a_member_adds_its_own_suites_beside_the_roots(@TempDir Path ws) throws IOException {
        write(ws, ROOT, """
                name = "a"

                [test.suites.smoke]
                classes = ["*Smoke"]
                """);

        assertThat(member(ws).build().testSuites()).containsOnlyKeys("integration", "smoke");
        assertThat(loaded(ws).build().testSuites()).containsOnlyKeys("integration", "smoke");
    }

    @Test
    void a_member_adds_settings_to_an_inherited_suite_and_the_root_wins_a_conflict(@TempDir Path ws)
            throws IOException {
        write(ws, ROOT, """
                name = "a"

                [test.suites.integration]
                env = { SHARED = "member", OWN = "member" }
                jvm-args = ["-Xmx4g", "-Dextra=1"]
                system-properties = { mode = "member", own = "x" }
                timeout = "30s"
                """);

        DeclaredSuite integration =
                Objects.requireNonNull(member(ws).build().testSuites().get("integration"));
        SuiteSettings settings = integration.settings();
        assertThat(integration.tags()).containsExactly("integration");
        assertThat(settings.env())
                .containsExactlyInAnyOrderEntriesOf(Map.of("DB", "root", "SHARED", "root", "OWN", "member"));
        assertThat(settings.jvmArgs()).containsExactly("-Xmx4g", "-Dextra=1", "-Xmx1g");
        assertThat(settings.systemProperties()).containsExactlyInAnyOrderEntriesOf(Map.of("mode", "root", "own", "x"));
        assertThat(settings.timeoutMs()).isEqualTo(30_000L);
        assertThat(loaded(ws).build().testSuites()).isEqualTo(member(ws).build().testSuites());
    }

    @Test
    void a_member_may_not_change_what_an_inherited_suite_claims(@TempDir Path ws) throws IOException {
        for (String claim : List.of(
                "tags = [\"slow\"]",
                "classes = [\"*IT\"]",
                "exclude-tags = [\"flaky\"]",
                "exclude-classes = [\"*IT\"]",
                "explicit = true")) {
            write(ws, ROOT, "name = \"a\"\n\n[test.suites.integration]\n" + claim + "\n");
            String key = claim.substring(0, claim.indexOf(' '));
            assertThatThrownBy(() -> member(ws))
                    .isInstanceOf(JkBuildParseException.class)
                    .hasMessageContaining("[test.suites.integration] is the workspace root's table")
                    .hasMessageContaining("not " + key);
            assertThatThrownBy(() -> loaded(ws))
                    .isInstanceOf(JkBuildParseException.class)
                    .hasMessageContaining("[test.suites.integration] is the workspace root's table");
        }
    }

    @Test
    void a_root_timeout_wins_over_a_members(@TempDir Path ws) throws IOException {
        write(ws, ROOT + "timeout = \"10s\"\n", """
                name = "a"

                [test.suites.integration]
                timeout = "30s"
                """);

        DeclaredSuite integration =
                Objects.requireNonNull(member(ws).build().testSuites().get("integration"));
        assertThat(integration.settings().timeoutMs()).isEqualTo(10_000L);
    }

    private static void write(Path ws, String root, String member) throws IOException {
        Files.writeString(ws.resolve("jk.toml"), root);
        Files.createDirectories(ws.resolve("libs/a"));
        Files.writeString(ws.resolve("libs/a/jk.toml"), member);
    }

    private static JkBuild member(Path ws) throws IOException {
        return JkBuildParser.parse(ws.resolve("libs/a/jk.toml"));
    }

    private static JkBuild loaded(Path ws) throws IOException {
        JkBuild root = JkBuildParser.parseLocal(ws.resolve("jk.toml"));
        return Objects.requireNonNull(WorkspaceLoader.loadModules(ws, root).get(ws.resolve("libs/a")));
    }
}
