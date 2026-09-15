// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.api;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.host.Os;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The short list jk ships from the caller's shell without being asked. These pin its shortness:
 * every name here is one no manifest could have declared, and the test that would fail first if
 * that stopped being true is the one asserting what is absent.
 */
class ClientEnvForwardTest {

    @Test
    void it_forwards_where_the_machine_is_and_how_it_talks() {
        assertThat(ClientEnvForward.names()).contains("PATH");
        // PATH above all: the engine is a daemon, so without it a worker searches the PATH of
        // whichever shell started that daemon — install node, re-run, still not found.
        assertThat(ClientEnvForward.names()).isNotEmpty();
        if (Os.isWindows()) {
            // A JVM that cannot find SystemRoot fails to initialise its socket stack.
            assertThat(ClientEnvForward.names()).contains("SystemRoot", "USERPROFILE");
        } else {
            assertThat(ClientEnvForward.names()).contains("HOME", "LANG");
        }
    }

    @Test
    void it_does_not_forward_anything_a_manifest_could_declare() {
        // The line this list must not cross. TZ changes what code computes, so it belongs in
        // [test] env where it is hashed into the action key; forwarding it here would change test
        // outcomes through a channel no key can see. Repository credentials travel by prefix and
        // stay out of this list: nothing here seeds them into a test JVM.
        assertThat(ClientEnvForward.names())
                .doesNotContain("TZ", "CI", "JAVA_HOME", "JAVA_TOOL_OPTIONS", "GRADLE_OPTS", "MAVEN_OPTS")
                .noneMatch(n -> n.startsWith(ClientEnvForward.REPO_PREFIX));
    }

    @Test
    void it_stays_short() {
        // Not a style rule. Every entry is a value that reaches a test JVM without appearing in any
        // action key, so the list's length is the size of the hole in jk's reproducibility story.
        assertThat(ClientEnvForward.names()).hasSizeLessThanOrEqualTo(10);
    }

    @Test
    void it_reports_only_what_the_caller_actually_has() {
        var resolved = ClientEnvForward.resolve();
        for (var e : resolved.entrySet()) {
            if (e.getKey().startsWith(ClientEnvForward.REPO_PREFIX)) continue; // by prefix, tested below
            if (BuildEnv.PROXY.contains(e.getKey())) continue; // by name, tested below
            if (BuildEnv.JDK_ROOT.contains(e.getKey())) continue; // by name, tested below
            assertThat(ClientEnvForward.names()).contains(e.getKey());
            assertThat(e.getValue()).as(e.getKey()).isEqualTo(System.getenv(e.getKey()));
        }
    }

    /**
     * The proxy is the network the shell running {@code jk} is on. Left to the daemon's inherited
     * value, a developer who moved networks had to {@code jk engine stop}; on the request, the
     * engine and every worker it forks for this build go through the proxy this terminal names.
     */
    @Test
    void it_forwards_the_proxy_variables_so_the_running_shell_decides_the_proxy() {
        System.setProperty("jk.env.https_proxy", "http://this-network.proxy:3128");
        System.setProperty("jk.env.NO_PROXY", ".corp");
        System.setProperty("jk.env.ftp_proxy", "http://not-a-name-jk-reads:1");
        try {
            assertThat(ClientEnvForward.resolve())
                    .containsEntry("https_proxy", "http://this-network.proxy:3128")
                    .containsEntry("NO_PROXY", ".corp")
                    .doesNotContainKey("ftp_proxy");
            // Off the machine list on purpose: that list's length is the reproducibility budget,
            // and these ride by name.
            assertThat(ClientEnvForward.names()).doesNotContainAnyElementsOf(BuildEnv.PROXY);
        } finally {
            System.clearProperty("jk.env.https_proxy");
            System.clearProperty("jk.env.NO_PROXY");
            System.clearProperty("jk.env.ftp_proxy");
        }
    }

    @Test
    void it_forwards_repository_credentials_and_host_bindings_by_prefix() {
        // A JK_REPO_<ID>_TOKEN exported in this terminal has to reach the resident engine's
        // resolver for this request — the daemon's own environment is whichever shell started it.
        // The name is the user's standing with a repository, not the build: no manifest can declare
        // it, it enters no action key, and no test JVM is seeded with it.
        System.setProperty("jk.env.JK_REPO_X_TOKEN", "t0k3n");
        System.setProperty("jk.env.JK_REPO_X_HOST", "repo.example:8443");
        try {
            assertThat(ClientEnvForward.resolve())
                    .containsEntry("JK_REPO_X_TOKEN", "t0k3n")
                    .containsEntry("JK_REPO_X_HOST", "repo.example:8443");
            // A command's own resolution of a declared reference wins over the forward set.
            assertThat(ClientEnvForward.layerUnder(Map.of("JK_REPO_X_TOKEN", "declared")))
                    .containsEntry("JK_REPO_X_TOKEN", "declared")
                    .containsEntry("JK_REPO_X_HOST", "repo.example:8443");
        } finally {
            System.clearProperty("jk.env.JK_REPO_X_TOKEN");
            System.clearProperty("jk.env.JK_REPO_X_HOST");
        }
    }

    /**
     * The client pre-flights a missing JDK into the root of the shell running {@code jk}; the
     * engine's own ensure-jdk has to look in that same root, not in the one of the shell that
     * started the daemon.
     */
    @Test
    void it_forwards_the_managed_jdk_root_so_the_engine_looks_where_this_shell_installs() {
        System.setProperty("jk.env.JK_JDKS_DIR", "/srv/runtimes/jdks");
        try {
            var resolved = ClientEnvForward.resolve();
            assertThat(resolved).containsEntry("JK_JDKS_DIR", "/srv/runtimes/jdks");
            assertThat(ClientEnvForward.names()).doesNotContain("JK_JDKS_DIR");
        } finally {
            System.clearProperty("jk.env.JK_JDKS_DIR");
        }
    }
}
