// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The engine inherits the allow-list of the spawning shell, not the shell. What the list keeps is
 * everything the engine reads; what it drops is everything that would otherwise become one
 * terminal's stale truth for every later one.
 */
class EngineEnvironmentTest {

    @Test
    void the_jvm_switches_credentials_and_build_tool_options_stay_behind() {
        for (String poison : new String[] {
            "JAVA_TOOL_OPTIONS",
            "_JAVA_OPTIONS",
            "JDK_JAVA_OPTIONS",
            "CLASSPATH",
            "MAVEN_OPTS",
            "GRADLE_OPTS",
            "AWS_SECRET_ACCESS_KEY",
            "GITHUB_TOKEN",
            "CI"
        }) {
            assertThat(EngineEnvironment.inherited(poison, false)).as(poison).isFalse();
        }
    }

    @Test
    void the_machine_the_toolchain_and_every_jk_variable_reach_the_engine() {
        for (String needed : new String[] {
            "PATH",
            "HOME",
            "USER",
            "SHELL",
            "TERM",
            "TMPDIR",
            "TZ",
            "LANG",
            "LC_ALL",
            "LC_CTYPE",
            "JAVA_HOME",
            "GRAALVM_HOME",
            "SSH_AUTH_SOCK",
            "MISE_DATA_DIR",
            "http_proxy",
            "HTTPS_PROXY",
            "no_proxy",
            "NO_PROXY",
            "DISPLAY",
            "WAYLAND_DISPLAY",
            "XAUTHORITY",
            "JK_HOME",
            "JK_PERF",
            "JK_GIT",
            "JK_OFFLINE",
            "JK_REPO_NEXUS_TOKEN",
            "JK_ENGINE_TRANSPORT",
            "JK_RESOLVE_TIMEOUT_MS",
            "JK_STORE_DIR"
        }) {
            assertThat(EngineEnvironment.inherited(needed, false)).as(needed).isTrue();
        }
    }

    @Test
    void worker_jvm_tuning_rides_each_request_and_never_seeds_the_daemon() {
        // JK_JVM_ARGS is the shell's spelling of --jvm-arg: the client folds it into the request, the
        // engine reads it from nowhere else. Seeded here, a second terminal exporting a different
        // value silently got the first one's until `jk engine stop`.
        for (String perRequest :
                new String[] {"JK_JVM_ARGS", "JK_JVM_GC", "JK_JVM_STRING_DEDUP", "JK_MAX_RAM_PERCENT"}) {
            assertThat(EngineEnvironment.inherited(perRequest, false))
                    .as(perRequest)
                    .isFalse();
            assertThat(EngineEnvironment.inherited(perRequest.toLowerCase(Locale.ROOT), true))
                    .as(perRequest)
                    .isFalse();
        }
        assertThat(EngineEnvironment.PER_REQUEST)
                .containsExactlyInAnyOrder("JK_JVM_ARGS", "JK_JVM_GC", "JK_JVM_STRING_DEDUP", "JK_MAX_RAM_PERCENT");
    }

    @Test
    void windows_matches_names_case_insensitively_and_keeps_its_system_roots() {
        for (String needed : new String[] {
            "Path",
            "systemroot",
            "ComSpec",
            "USERPROFILE",
            "ProgramFiles",
            "PATHEXT",
            "TEMP",
            "jk_home",
            "INCLUDE",
            "LIB",
            "LIBPATH"
        }) {
            assertThat(EngineEnvironment.inherited(needed, true)).as(needed).isTrue();
        }
        assertThat(EngineEnvironment.inherited("java_tool_options", true)).isFalse();
        // Unix names are exact: a lower-case "path" is some other variable.
        assertThat(EngineEnvironment.inherited("path", false)).isFalse();
    }

    @Test
    void seeding_replaces_the_inherited_environment_rather_than_adding_to_it() {
        Map<String, String> env = new HashMap<>(Map.of("JAVA_TOOL_OPTIONS", "-Xmx1g", "PATH", "/stale"));
        Map<String, String> shell = Map.of(
                "PATH", "/usr/bin",
                "JK_HOME", "/home/u/.jk",
                "LC_ALL", "C.UTF-8",
                "AWS_SECRET_ACCESS_KEY", "hunter2",
                "_JAVA_OPTIONS", "-Dx=y");

        EngineEnvironment.seed(env, shell);

        assertThat(env)
                .containsOnly(
                        Map.entry("PATH", "/usr/bin"),
                        Map.entry("JK_HOME", "/home/u/.jk"),
                        Map.entry("LC_ALL", "C.UTF-8"),
                        Map.entry("MALLOC_ARENA_MAX", EngineEnvironment.DEFAULT_MALLOC_ARENA_MAX));
    }

    @Test
    void the_engine_gets_a_malloc_arena_cap_unless_the_shell_set_one() {
        Map<String, String> env = new HashMap<>();
        EngineEnvironment.seed(env, Map.of("PATH", "/usr/bin"));
        assertThat(env).containsEntry("MALLOC_ARENA_MAX", "4");
        EngineEnvironment.seed(env, Map.of("PATH", "/usr/bin", "MALLOC_ARENA_MAX", "1"));
        assertThat(env).as("the shell's own cap is inherited, not overruled").containsEntry("MALLOC_ARENA_MAX", "1");
    }
}
