// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.RequestShell;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.ChildEnv;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.Processes;
import cc.jumpkick.model.EnvConfig;
import cc.jumpkick.model.EnvDecl;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
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

        assertThat(out).containsOnlyKeys("PATH", "HOME", "TMPDIR", "LANG", "LC_ALL", "JK_HOME", "JK_NONINTERACTIVE");
        assertThat(out)
                .as("the daemon's terminal is no worker's: a test JVM gets a fixed one")
                .doesNotContainKey("TERM");
        assertThat(out).doesNotContainKeys("FAKE_SECRET", "GITHUB_TOKEN", "SSH_AUTH_SOCK");
        // Credentials and the daemon's toolchain pin live in the JK_ namespace too; a prefix rule
        // would have let every one of them through.
        assertThat(out).doesNotContainKeys("JK_REPO_NEXUS_TOKEN", "JK_GIT_CRED_PASS", "JK_JDK");
        // JAVA_HOME is not inherited either, and for the same reason JK_JDK is not: the engine's
        // copy describes the shell that started the daemon. The worker is told which JDK it runs
        // under by the fork site that chose it.
        assertThat(out).doesNotContainKey("JAVA_HOME");
    }

    /**
     * What a request forwards for the engine's own resolvers, and a secret nothing declares that an
     * over-forwarding client sent anyway, stay in the engine: a compiler, plugin or test worker that
     * has not declared them starts without them.
     */
    @Test
    void a_requests_credentials_and_undeclared_secrets_reach_no_worker() throws Exception {
        Map<String, String> shell = Map.of(
                "PATH", "/req/bin",
                "GITHUB_TOKEN", "canary-forge",
                "AWS_SECRET_ACCESS_KEY", "canary-cloud",
                "BUILDKITE_AGENT_ACCESS_TOKEN", "canary-ci",
                "HOMEBREW_GITHUB_API_TOKEN", "canary-tool",
                "GIT_ASKPASS", "/req/askpass");
        AtomicReference<Map<String, String>> env = new AtomicReference<>();
        AtomicReference<Map<String, String>> compiler = new AtomicReference<>();
        RequestShell.where(shell, () -> {
            SessionContext.runWhere(Session.defaults().withVariant(null, shell), () -> {
                try {
                    env.set(WorkerEnv.withEngineEnvironment(ENGINE, WorkerEnv.strict()::environment));
                    compiler.set(WorkerEnv.withEngineEnvironment(ENGINE, WorkerEnv.strict()::compilerEnvironment));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            return null;
        });
        for (Map<String, String> worker : List.of(env.get(), compiler.get())) {
            assertThat(worker).containsEntry("PATH", "/req/bin");
            assertThat(worker.values()).noneMatch(v -> v.startsWith("canary-"));
            assertThat(worker).doesNotContainKey("GIT_ASKPASS");
        }
    }

    /**
     * A module that says nothing of [env] lets its test JVMs read the caller's shell (through the
     * environment broker, a variable at a time), never its compiler or any other worker: their
     * environment is the allow-list, as before.
     */
    @Test
    void a_default_module_hands_its_compiler_no_undeclared_variable(@TempDir Path dir) throws Exception {
        Map<String, String> shell = Map.of("PATH", "/req/bin", "UNDECLARED_FOR_TESTS", "canary-compile");
        EnvConfig config = JkBuildParser.parse("group = \"ex\"\nname = \"m\"\nversion = \"1.0.0\"\n")
                .build()
                .env();
        assertThat(config.strict()).isFalse();
        Map<String, String> compiler = RequestShell.where(
                shell,
                () -> WorkerEnv.withEngineEnvironment(
                        ENGINE, WorkerEnv.forModule(config, dir, null)::compilerEnvironment));
        assertThat(compiler).doesNotContainKey("UNDECLARED_FOR_TESTS");
    }

    @Test
    void a_worker_is_told_the_jdk_it_runs_under_and_it_beats_the_daemons() {
        Path home = Path.of("/jdks/temurin-25.0.4");
        Map<String, String> out = WorkerEnv.compose(
                ENGINE,
                Map.of(),
                /*inherit*/ true,
                WorkerEnv.strict().withJavaHome(home).extras(),
                false);

        assertThat(out)
                .as("even when the module inherited the engine's whole environment")
                .containsEntry("JAVA_HOME", home.toAbsolutePath().toString());
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

    /**
     * The display is where the machine draws, so a worker gets the three display names without a
     * manifest asking — and the request's values over the engine's, because a suite should open the
     * display of the terminal running {@code jk test}, the one {@code xvfb-run} set, not the one of
     * whichever shell spawned the daemon.
     */
    @Test
    void the_display_variables_come_through_with_the_requests_values_over_the_engines() {
        Map<String, String> engine = new LinkedHashMap<>(ENGINE);
        engine.put("DISPLAY", ":0");
        engine.put("WAYLAND_DISPLAY", "wayland-0");
        engine.put("DESKTOP_SESSION", "gnome");
        Map<String, String> request = Map.of("DISPLAY", ":99", "XAUTHORITY", "/tmp/xvfb-run.abc/Xauthority");

        Map<String, String> out = WorkerEnv.compose(engine, request, false, Map.of(), false);

        assertThat(out)
                .containsEntry("DISPLAY", ":99")
                .containsEntry("XAUTHORITY", "/tmp/xvfb-run.abc/Xauthority")
                .as("a name the request did not carry keeps the engine's value")
                .containsEntry("WAYLAND_DISPLAY", "wayland-0");
        assertThat(out).as("only the three names a toolkit reads").doesNotContainKey("DESKTOP_SESSION");
        assertThat(WorkerEnv.compose(engine, Map.of(), false, Map.of(), false))
                .as("with nothing on the request the engine's own value passes")
                .containsEntry("DISPLAY", ":0");
        assertThat(WorkerEnv.allowed("XAUTHORITY", false)).isTrue();
        assertThat(WorkerEnv.allowed("XDG_RUNTIME_DIR", false))
                .as("WAYLAND_DISPLAY names a socket under it")
                .isTrue();
    }

    @Test
    void the_container_runtime_variables_come_through_with_the_requests_values_over_the_engines() {
        Map<String, String> engine = new LinkedHashMap<>(ENGINE);
        engine.put("DOCKER_HOST", "unix:///var/run/docker.sock");
        engine.put("DOCKER_AUTH_TOKEN", "secret");
        Map<String, String> request =
                Map.of("DOCKER_HOST", "unix:///tmp/podman.sock", "TESTCONTAINERS_RYUK_DISABLED", "true");

        Map<String, String> out = WorkerEnv.compose(engine, request, false, Map.of(), false);

        assertThat(out)
                .as("a Testcontainers suite reaches the runtime the terminal running jk names")
                .containsEntry("DOCKER_HOST", "unix:///tmp/podman.sock")
                .containsEntry("TESTCONTAINERS_RYUK_DISABLED", "true")
                .doesNotContainKey("DOCKER_AUTH_TOKEN");
        assertThat(WorkerEnv.compose(engine, Map.of(), false, Map.of(), false))
                .as("with nothing on the request the engine's own value passes")
                .containsEntry("DOCKER_HOST", "unix:///var/run/docker.sock");
        assertThat(WorkerEnv.allowed("TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX", false))
                .as("a corporate registry mirror")
                .isTrue();
        assertThat(WorkerEnv.allowed("CONTAINER_HOST", false))
                .as("Podman's daemon")
                .isTrue();
    }

    @Test
    void inherit_hands_over_the_whole_environment_less_jks_own_credentials() {
        Map<String, String> expected = new LinkedHashMap<>(ENGINE);
        expected.remove("JK_REPO_NEXUS_TOKEN");
        assertThat(WorkerEnv.compose(ENGINE, Map.of(), true, Map.of(), false))
                .containsExactlyInAnyOrderEntriesOf(expected);
        assertThat(WorkerEnv.compose(ENGINE, Map.of("no_proxy", "*"), true, Map.of(), false))
                .as("the request's proxy still wins over an inherited environment")
                .containsEntry("no_proxy", "*");
    }

    @Test
    void a_compiler_never_gets_the_variables_a_jvm_reads_its_options_from() throws Exception {
        Map<String, String> engine = new LinkedHashMap<>(ENGINE);
        engine.put("JAVA_TOOL_OPTIONS", "-javaagent:/x.jar");
        engine.put("_JAVA_OPTIONS", "-Duser.language=ja");
        engine.put("JDK_JAVA_OPTIONS", "-Duser.country=JP");
        WorkerEnv inherited =
                WorkerEnv.policy(new EnvConfig(true, List.of())).with(Map.of("JDK_JAVA_OPTIONS", "-Dset=by-module"));

        Map<String, String> compiler = WorkerEnv.withEngineEnvironment(engine, inherited::compilerEnvironment);
        Map<String, String> test = WorkerEnv.withEngineEnvironment(engine, inherited::environment);

        assertThat(compiler).doesNotContainKeys("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS");
        assertThat(test).as("a test JVM still gets what its module inherited").containsKey("JAVA_TOOL_OPTIONS");
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
    void an_inheriting_worker_gets_the_callers_shell_not_the_daemons() throws Exception {
        WorkerEnv inherit = WorkerEnv.policy(new EnvConfig(true, List.of()));
        Map<String, String> env = RequestShell.where(
                Map.of("JK_CALLER_ONLY", "yes", "PWD", "/somewhere", "PATH", "/caller/bin"), inherit::environment);

        assertThat(env).containsEntry("JK_CALLER_ONLY", "yes");
        assertThat(env).as("per-invocation noise would fork a worker per build").doesNotContainKey("PWD");
        assertThat(WorkerEnv.strict().environment())
                .as("a strict worker never sees the caller's other variables")
                .doesNotContainKey("JK_CALLER_ONLY");
    }

    /**
     * The team's cache key seed reaches no worker: not from the engine's environment, not inherited
     * from the caller's shell, not declared in {@code [env]}, not laid on by a fork, in any case on
     * Windows; a compiler no more than a test JVM.
     */
    @Test
    void the_cache_key_seed_reaches_no_worker(@TempDir Path dir) throws Exception {
        String seed = "made-up-team-seed-0123456789";
        Map<String, String> shell = Map.of("PATH", "/caller/bin", "JK_CACHE_KEY_SEED", seed, "APP_MODE", "a");
        WorkerEnv inherit = WorkerEnv.policy(new EnvConfig(true, List.of()));
        assertThat(RequestShell.where(shell, inherit::environment))
                .containsEntry("APP_MODE", "a")
                .doesNotContainKey("JK_CACHE_KEY_SEED");
        assertThat(RequestShell.where(shell, inherit::compilerEnvironment)).doesNotContainKey("JK_CACHE_KEY_SEED");

        EnvConfig declares = new EnvConfig(false, List.of(), List.of("JK_CACHE_KEY_SEED"), List.of(), Map.of());
        assertThat(RequestShell.where(shell, () -> WorkerEnv.declared(declares, dir, null)))
                .as("a declared forward of it resolves unset")
                .isEmpty();
        assertThat(WorkerEnv.withEngineEnvironment(
                        Map.of("PATH", "/usr/bin", "JK_CACHE_KEY_SEED", seed),
                        WorkerEnv.strict().with(Map.of("JK_CACHE_KEY_SEED", seed))::environment))
                .as("neither the engine's nor one a fork lays on")
                .doesNotContainKey("JK_CACHE_KEY_SEED");
        assertThat(WorkerEnv.compose(Map.of("jk_cache_key_seed", seed), Map.of(), true, Map.of(), true))
                .as("on Windows, whatever its case")
                .isEmpty();
    }

    /**
     * jk's own credentials reach a worker only when the module forwards them by name: a token in
     * the shell running jk or in the engine's own environment reaches no worker ({@code [env]
     * inherit} included) and no program a plugin step starts; {@code [env] require} hands it to the
     * worker and, through {@link ChildEnv#FORWARDED}, to what it starts, and to nothing else.
     */
    @Test
    void jks_own_credentials_reach_a_worker_only_when_forwarded_by_name(@TempDir Path dir) throws Exception {
        String token = "made-up-repo-token-0123456789";
        Map<String, String> shell = Map.of(
                "PATH",
                "/caller/bin",
                "JK_REPO_X_TOKEN",
                token,
                "JK_REPO_Y_TOKEN",
                token,
                "APP_MODE",
                "a",
                ChildEnv.FORWARDED,
                "JK_REPO_Y_TOKEN");
        WorkerEnv inherit = WorkerEnv.policy(new EnvConfig(true, List.of()));
        assertThat(RequestShell.where(shell, inherit::environment))
                .as("[env] inherit hands the shell on, but not jk's credentials, whatever the shell says forwarded")
                .containsEntry("APP_MODE", "a")
                .doesNotContainKeys("JK_REPO_X_TOKEN", "JK_REPO_Y_TOKEN", ChildEnv.FORWARDED);
        assertThat(WorkerEnv.withEngineEnvironment(
                        Map.of("PATH", "/usr/bin", "JK_REPO_X_TOKEN", token), inherit::environment))
                .doesNotContainKey("JK_REPO_X_TOKEN");
        assertThat(seen(shell, WorkerEnv.policy(new EnvConfig(true, List.of()))))
                .as("a plugin step's worker, unforwarded")
                .doesNotContain(token);

        EnvConfig forwards = new EnvConfig(false, List.of("JK_REPO_X_TOKEN"), List.of(), List.of(), Map.of());
        WorkerEnv module = RequestShell.where(shell, () -> WorkerEnv.forModule(forwards, dir, null));
        Map<String, String> env = RequestShell.where(shell, module::environment);
        assertThat(env)
                .containsEntry("JK_REPO_X_TOKEN", token)
                .containsEntry(ChildEnv.FORWARDED, "JK_REPO_X_TOKEN")
                .doesNotContainKey("JK_REPO_Y_TOKEN");
        assertThat(seen(shell, module)).as("the worker, forwarded").contains("JK_REPO_X_TOKEN=" + token);
        ProcessBuilder grandchild =
                Os.isWindows() ? new ProcessBuilder("cmd.exe", "/c", "set") : new ProcessBuilder("/usr/bin/env");
        grandchild.environment().putAll(env);
        assertThat(Processes.capture(grandchild.redirectErrorStream(true), Duration.ofSeconds(30))
                        .output())
                .as("a program the worker starts (TaskExec), which inherits its environment")
                .contains("JK_REPO_X_TOKEN=" + token);
    }

    /** What a worker forked through {@link PluginProcess#builder} for a request from {@code shell} sees. */
    private static String seen(Map<String, String> shell, WorkerEnv env) throws Exception {
        List<String> command = Os.isWindows() ? List.of("cmd.exe", "/c", "set") : List.of("/usr/bin/env");
        ProcessBuilder pb = RequestShell.where(shell, () -> PluginProcess.builder(command, env));
        return Processes.capture(pb, Duration.ofSeconds(30)).output();
    }

    /**
     * Every worker finds its tools on the PATH of the shell running jk, as a test JVM does: the
     * guard suite's JVM is one, and a tool installed into a new PATH entry reaches it without an
     * engine restart.
     */
    @Test
    void a_strict_worker_gets_the_requests_path_over_the_engines() throws Exception {
        Map<String, String> engine = new LinkedHashMap<>(ENGINE);
        AtomicReference<Map<String, String>> seen = new AtomicReference<>();
        SessionContext.runWhere(
                Session.defaults().withVariant(null, Map.of("PATH", "/shell/new-tools:/usr/bin")), () -> {
                    try {
                        seen.set(WorkerEnv.withEngineEnvironment(engine, WorkerEnv.strict()::environment));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
        assertThat(seen.get()).containsEntry("PATH", "/shell/new-tools:/usr/bin");
        assertThat(WorkerEnv.withEngineEnvironment(engine, WorkerEnv.strict()::environment))
                .as("off a request the engine's PATH answers")
                .containsEntry("PATH", "/usr/bin");
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
                List.of(),
                List.of("JK_UNSET_ON_PURPOSE"),
                List.of(new EnvDecl.Set("SCRATCH", "${module}/scratch")),
                Map.of());

        WorkerEnv env = WorkerEnv.forModule(config, tmp, null);

        assertThat(env.inherit()).isFalse();
        assertThat(Path.of(env.extras().get("SCRATCH")))
                .isEqualTo(tmp.resolve("scratch").toAbsolutePath());
        assertThat(env.extras()).as("an unset forward is absent, not empty").doesNotContainKey("JK_UNSET_ON_PURPOSE");
    }

    @Test
    void on_windows_a_later_PATH_replaces_the_inherited_Path_and_either_spelling_finds_it() {
        Map<String, String> out =
                WorkerEnv.compose(Map.of("Path", "C:\\Windows"), Map.of(), false, Map.of("PATH", "C:\\node"), true);
        assertThat(out).hasSize(1);
        assertThat(out.get("PATH")).isEqualTo("C:\\node");
        assertThat(out.get("Path")).isEqualTo("C:\\node");
    }
}
