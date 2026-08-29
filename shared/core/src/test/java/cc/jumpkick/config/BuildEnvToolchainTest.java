// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The request's environment beats the process's, for the variables that pick a toolchain.
 *
 * <p>Why this is a test and not a comment: the engine is a daemon, so a {@code System.getenv} on a
 * build path answers from whichever shell started it — days earlier, with a different {@code JK_JDK}.
 * The consequence was not a missing override but a build whose JDK depended on how the daemon had
 * been launched, so {@code jk engine stop} changed what got compiled (JK-1021).
 *
 * <p>The other half of that fix — that swapping the resolved JDK actually moves the action key, so
 * forwarding these names opens no cache hole — is {@code ActionKeyTest}'s, which already asserts two
 * JDK homes produce two {@code forJavac} keys and that the token is content rather than path. Not
 * repeated here.
 */
class BuildEnvToolchainTest {

    @Test
    void toolchain_names_are_owned_in_one_place() {
        // The ban list lives here so G38 and every build-path reader agree on what must not be a
        // raw System.getenv. ClientEnvForward deliberately does not forward these.
        assertThat(BuildEnv.TOOLCHAIN).containsExactly("JK_JDK", "JAVA_HOME", "GRAALVM_HOME");
    }

    @Test
    void machine_names_are_owned_in_one_place() {
        // ClientEnvForward and TestEnv both read this list — the client ships them on the request,
        // the engine seeds them into every forked test JVM. They must not drift.
        assertThat(BuildEnv.MACHINE).contains("PATH");
        assertThat(BuildEnv.MACHINE).doesNotContainAnyElementsOf(BuildEnv.TOOLCHAIN);
        assertThat(BuildEnv.MACHINE).hasSizeLessThanOrEqualTo(10);
    }

    @Test
    void machine_resolves_from_the_request() {
        String path = "/from-the-request/bin:/usr/bin";
        SessionContext.runWhere(sessionWith(Map.of("PATH", path)), () -> {
            assertThat(BuildEnv.machine()).containsEntry("PATH", path);
        });
    }

    @Test
    void request_env_supplies_every_toolchain_name() {
        Map<String, String> requestEnv =
                Map.of("JK_JDK", "temurin-21", "JAVA_HOME", "/opt/jdk-21", "GRAALVM_HOME", "/opt/graal-21");

        SessionContext.runWhere(sessionWith(requestEnv), () -> {
            UnaryOperator<String> env = BuildEnv.ambient();
            for (String name : BuildEnv.TOOLCHAIN) {
                assertThat(env.apply(name))
                        .as("%s must come from the request, not the daemon", name)
                        .isEqualTo(requestEnv.get(name));
            }
        });
    }

    @Test
    void request_env_wins_over_the_process_env() {
        // Precedence needs a name this process actually has, or "the request value came back" would
        // prove nothing about which layer answered. Any inherited variable will do.
        Optional<String> inherited = System.getenv().keySet().stream()
                .filter(k -> !k.isBlank())
                .filter(k -> System.getenv(k) != null && !System.getenv(k).isBlank())
                .findFirst();
        assertThat(inherited).as("test needs at least one inherited env var").isPresent();

        String name = inherited.get();
        String processValue = System.getenv(name);
        String requestValue = processValue + "-from-the-request";

        SessionContext.runWhere(sessionWith(Map.of(name, requestValue)), () -> {
            assertThat(BuildEnv.ambient().apply(name)).isEqualTo(requestValue);
        });
    }

    @Test
    void process_env_still_answers_when_the_request_is_silent() {
        // Falling back matters: a plain `jk build` forwards only what the caller had set, and the
        // engine's own environment is a better answer than nothing.
        SessionContext.runWhere(sessionWith(Map.of()), () -> {
            assertThat(BuildEnv.ambient().apply("PATH")).isEqualTo(System.getenv("PATH"));
            assertThat(BuildEnv.ambient().apply("JK_NO_SUCH_VARIABLE_EXISTS")).isNull();
        });
    }

    @Test
    void forModule_layers_the_request_under_dot_env(@TempDir Path moduleDir) {
        // `.env` is the highest layer, so a module can pin a value the shell did not set. It has no
        // say over the toolchain three in practice, but the ordering is the same one BuildEnv
        // documents and is worth pinning where it can be observed.
        SessionContext.runWhere(sessionWith(Map.of("JK_JDK", "temurin-21")), () -> {
            assertThat(BuildEnv.forModule(moduleDir).apply("JK_JDK")).isEqualTo("temurin-21");
        });
    }

    private static Session sessionWith(Map<String, String> clientEnv) {
        return Session.defaults().withVariant(null, clientEnv);
    }

    @Test
    void the_request_carries_graal_home_as_a_typed_field() {
        // GRAALVM_HOME is a home path, not a spec, so it cannot ride the graal selection — it needs
        // its own field, and it needs one for the same reason: a getenv inside a resident engine
        // answers from the shell that started the daemon (JK-1039).
        Path home = Path.of("/opt/graal-25");
        Session session = Session.defaults().withToolchainSpecs("temurin-21", "graal-25", home);

        assertThat(session.jdkSpec()).isEqualTo("temurin-21");
        assertThat(session.graalSpec()).isEqualTo("graal-25");
        assertThat(session.graalHome()).isEqualTo(home);
    }

    @Test
    void a_request_with_no_graal_home_carries_null_rather_than_the_daemons() {
        // The engine must be able to tell "the caller named none" from "the caller named this", or it
        // would fall back to its own environment and be right by accident.
        assertThat(Session.defaults().graalHome()).isNull();
        assertThat(Session.defaults()
                        .withToolchainSpecs("temurin-21", null, null)
                        .graalHome())
                .isNull();
    }
}
