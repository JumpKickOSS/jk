// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The engine inherits the allow-list of the spawning shell, not the shell. What the list keeps is
 * everything the engine reads; what it drops is everything that would otherwise become one
 * terminal's stale truth for every later one.
 */
class EngineEnvironmentTest {

    @Test
    void the_jvm_switches_credentials_proxies_and_build_tool_options_stay_behind() {
        for (String poison : new String[] {
            "JAVA_TOOL_OPTIONS",
            "_JAVA_OPTIONS",
            "JDK_JAVA_OPTIONS",
            "CLASSPATH",
            "MAVEN_OPTS",
            "GRADLE_OPTS",
            "HTTPS_PROXY",
            "http_proxy",
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
            "JK_HOME",
            "JK_JVM_ARGS",
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
    void windows_matches_names_case_insensitively_and_keeps_its_system_roots() {
        for (String needed : new String[] {
            "Path", "systemroot", "ComSpec", "USERPROFILE", "ProgramFiles", "PATHEXT", "TEMP", "jk_home"
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
                        Map.entry("LC_ALL", "C.UTF-8"));
    }
}
