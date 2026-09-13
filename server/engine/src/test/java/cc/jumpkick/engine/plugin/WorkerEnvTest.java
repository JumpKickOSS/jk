// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.EnvConfig;
import cc.jumpkick.model.EnvDecl;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The allow-list rule, on maps: what of the engine's environment a worker may start with. */
class WorkerEnvTest {

    private static final Map<String, String> ENGINE = Map.ofEntries(
            Map.entry("PATH", "/usr/bin"),
            Map.entry("HOME", "/home/dev"),
            Map.entry("JAVA_HOME", "/jdk"),
            Map.entry("TMPDIR", "/tmp"),
            Map.entry("LANG", "C.UTF-8"),
            Map.entry("LC_ALL", "C"),
            Map.entry("TERM", "xterm"),
            Map.entry("JK_HOME", "/home/dev/.jk"),
            Map.entry("JK_NONINTERACTIVE", "1"),
            Map.entry("FAKE_SECRET", "x"),
            Map.entry("GITHUB_TOKEN", "ghp_x"),
            Map.entry("SSH_AUTH_SOCK", "/run/ssh"),
            Map.entry("JK_REPO_NEXUS_TOKEN", "r"),
            Map.entry("JK_GIT_CRED_PASS", "p"),
            Map.entry("JK_JDK", "temurin-25"));

    @Test
    void only_the_machine_and_the_workers_own_jk_settings_come_through() {
        Map<String, String> out = WorkerEnv.compose(ENGINE, Map.of(), false, Map.of(), false);

        assertThat(out)
                .containsOnlyKeys(
                        "PATH",
                        "HOME",
                        "JAVA_HOME",
                        "TMPDIR",
                        "LANG",
                        "LC_ALL",
                        "TERM",
                        "JK_HOME",
                        "JK_NONINTERACTIVE");
        assertThat(out).doesNotContainKeys("FAKE_SECRET", "GITHUB_TOKEN", "SSH_AUTH_SOCK");
        // Credentials and the daemon's toolchain pin live in the JK_ namespace too; a prefix rule
        // would have let every one of them through.
        assertThat(out).doesNotContainKeys("JK_REPO_NEXUS_TOKEN", "JK_GIT_CRED_PASS", "JK_JDK");
    }

    /**
     * The proxy is how the machine talks, so a worker gets the six proxy names without a manifest
     * asking — and the request's values over the engine's, because the engine's are whichever
     * network the shell that spawned it was on, and the worker's downloads should go where the
     * request's do.
     */
    @Test
    void the_proxy_variables_come_through_with_the_requests_values_over_the_engines() {
        Map<String, String> engine = new LinkedHashMap<>(ENGINE);
        engine.put("https_proxy", "http://old-network.proxy:3128");
        engine.put("NO_PROXY", ".corp");
        Map<String, String> request =
                Map.of("https_proxy", "http://new-network.proxy:3128", "http_proxy", "http://p:1");

        Map<String, String> out = WorkerEnv.compose(engine, request, false, Map.of(), false);

        assertThat(out)
                .containsEntry("https_proxy", "http://new-network.proxy:3128")
                .containsEntry("http_proxy", "http://p:1")
                .as("a name the request did not carry keeps the engine's value")
                .containsEntry("NO_PROXY", ".corp");
        assertThat(WorkerEnv.compose(engine, Map.of(), false, Map.of(), false))
                .as("with nothing on the request the engine's own values pass")
                .containsEntry("https_proxy", "http://old-network.proxy:3128");
        assertThat(WorkerEnv.allowed("HTTPS_PROXY", true)).isTrue();
        assertThat(WorkerEnv.allowed("https_proxy", true)).isTrue();
        assertThat(WorkerEnv.allowed("ftp_proxy", false))
                .as("only the six names jk itself reads")
                .isFalse();
    }

    @Test
    void inherit_hands_over_the_whole_environment() {
        assertThat(WorkerEnv.compose(ENGINE, Map.of(), true, Map.of(), false))
                .containsExactlyInAnyOrderEntriesOf(ENGINE);
        assertThat(WorkerEnv.compose(ENGINE, Map.of("no_proxy", "*"), true, Map.of(), false))
                .as("the request's proxy still wins over an inherited environment")
                .containsEntry("no_proxy", "*");
    }

    @Test
    void extras_land_after_the_inherited_names_and_win_over_them() {
        Map<String, String> extras = new LinkedHashMap<>();
        extras.put("JK_HOME", "/sandbox");
        extras.put("SPEC", "/tmp/spec");

        Map<String, String> out = WorkerEnv.compose(ENGINE, Map.of(), false, extras, false);

        assertThat(out).containsEntry("JK_HOME", "/sandbox").containsEntry("SPEC", "/tmp/spec");
        assertThat(List.copyOf(out.keySet())).endsWith("SPEC");
    }

    @Test
    void windows_names_match_without_regard_to_case() {
        Map<String, String> engine = Map.of("Path", "C:/x", "SystemRoot", "C:/Windows", "Secret", "s");

        assertThat(WorkerEnv.compose(engine, Map.of(), false, Map.of(), true)).containsOnlyKeys("Path", "SystemRoot");
        assertThat(WorkerEnv.allowed("path", true)).isTrue();
        assertThat(WorkerEnv.allowed("path", false)).as("POSIX names are exact").isFalse();
        assertThat(WorkerEnv.allowed("SYSTEMROOT", true)).isTrue();
        assertThat(WorkerEnv.allowed("LC_MESSAGES", false))
                .as("the locale family is a prefix")
                .isTrue();
    }

    @Test
    void with_layers_later_over_earlier_and_defaults_sit_beneath() {
        WorkerEnv env = WorkerEnv.strict().with(Map.of("A", "1")).with(Map.of("A", "2", "B", "2"));
        assertThat(env.extras()).containsExactlyInAnyOrderEntriesOf(Map.of("A", "2", "B", "2"));

        WorkerEnv defaulted = env.withDefaults(Map.of("A", "0", "C", "0"));
        assertThat(defaulted.extras()).containsExactlyInAnyOrderEntriesOf(Map.of("A", "2", "B", "2", "C", "0"));
        assertThat(defaulted.inherit()).isFalse();
    }

    @Test
    void the_fingerprint_changes_with_the_policy_and_with_the_extras() {
        WorkerEnv strict = WorkerEnv.strict();
        WorkerEnv inherit = WorkerEnv.policy(new EnvConfig(true, List.of()));

        assertThat(strict.fingerprint()).isEqualTo(WorkerEnv.strict().fingerprint());
        assertThat(strict.fingerprint()).isNotEqualTo(inherit.fingerprint());
        assertThat(strict.fingerprint())
                .isNotEqualTo(strict.with(Map.of("TZ", "UTC")).fingerprint());
        assertThat(strict.with(Map.of("A", "1", "B", "2")).fingerprint())
                .as("order of declaration is not identity")
                .isEqualTo(strict.with(Map.of("B", "2", "A", "1")).fingerprint());
    }

    @Test
    void declared_vars_resolve_through_the_build_s_environment(@TempDir Path tmp) {
        EnvConfig config = new EnvConfig(
                false,
                List.of(new EnvDecl.Set("SCRATCH", "${module}/scratch"), new EnvDecl.Forward("JK_UNSET_ON_PURPOSE")));

        WorkerEnv env = WorkerEnv.forModule(config, tmp, null);

        assertThat(env.inherit()).isFalse();
        assertThat(Path.of(env.extras().get("SCRATCH")))
                .isEqualTo(tmp.resolve("scratch").toAbsolutePath());
        assertThat(env.extras()).as("an unset forward is absent, not empty").doesNotContainKey("JK_UNSET_ON_PURPOSE");
    }
}
