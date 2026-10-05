// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.PluginTuning;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * One {@link Jk#execute} is one shell. A host that runs several invocations in one JVM must not
 * hand the second caller the first caller's environment: the forward set ({@code JK_REPO_*}), the
 * variant and the worker-JVM tuning are read from the shell that runs {@code jk}, so each
 * invocation starts from a fresh session and what it installs stays its own.
 */
class JkInvocationSessionTest {

    @Test
    void an_invocation_starts_from_a_fresh_session_not_the_previous_callers() throws Exception {
        Session previousCaller = Session.defaults()
                .withVariant("ci", Map.of("JK_REPO_PRIVATE_TOKEN", "alpha"))
                .withJvm(new PluginTuning(null, null, null, List.of("-Djk.probe=first")));
        Session before = SessionContext.installed();
        SessionContext.install(previousCaller);
        try {
            Session seen = SessionContext.invocation(SessionContext::current);
            assertThat(seen.clientEnv()).isEmpty();
            assertThat(seen.variant()).isEmpty();
            assertThat(seen.jvm().extraArgs()).isEmpty();

            PrintStream prevOut = System.out;
            System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            try {
                assertThat(Jk.execute("--version")).isZero();
            } finally {
                System.setOut(prevOut);
            }
            assertThat(SessionContext.installed())
                    .as("an invocation's installs never reach the process fallback")
                    .isSameAs(previousCaller);
        } finally {
            SessionContext.install(before);
        }
    }
}
