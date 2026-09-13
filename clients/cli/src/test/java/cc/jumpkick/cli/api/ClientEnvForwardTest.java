// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.api;

import static org.assertj.core.api.Assertions.assertThat;

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
            assertThat(ClientEnvForward.names()).contains(e.getKey());
            assertThat(e.getValue()).as(e.getKey()).isEqualTo(System.getenv(e.getKey()));
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
}
