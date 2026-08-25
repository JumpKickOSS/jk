// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Os;
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
        // outcomes through a channel no key can see. Same for anything credential-shaped.
        assertThat(ClientEnvForward.names())
                .doesNotContain("TZ", "CI", "JAVA_HOME", "JAVA_TOOL_OPTIONS", "GRADLE_OPTS", "MAVEN_OPTS");
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
        assertThat(resolved.keySet()).isSubsetOf(ClientEnvForward.names());
        for (var e : resolved.entrySet()) {
            assertThat(e.getValue()).as(e.getKey()).isEqualTo(System.getenv(e.getKey()));
        }
    }
}
