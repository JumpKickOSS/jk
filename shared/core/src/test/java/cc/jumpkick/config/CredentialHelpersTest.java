// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.host.time.Clock;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.tomlj.Toml;

/** {@code ${cmd:...}}: argv, the run itself, its errors, the cache and where it may be written. */
class CredentialHelpersTest {

    private CredentialHelpers.@Nullable Runner previous;

    @AfterEach
    void restore() {
        if (previous != null) CredentialHelpers.runner(previous);
        CredentialHelpers.clear();
    }

    /** A runner that counts its runs and answers {@code token-<n>}, after {@code delayMillis}. */
    private AtomicInteger countingRunner(long delayMillis) {
        AtomicInteger runs = new AtomicInteger();
        previous = CredentialHelpers.runner((argv, env, timeout, where) -> {
            int n = runs.incrementAndGet();
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return "token-" + n;
        });
        return runs;
    }

    @Test
    void a_body_splits_into_argv_the_way_scripts_do_and_takes_a_ttl_suffix() {
        var cmd = CredentialHelpers.parse("aws codeartifact get-authorization-token --query 'a b' #ttl=5m", "w");

        assertThat(cmd.argv()).containsExactly("aws", "codeartifact", "get-authorization-token", "--query", "a b");
        assertThat(cmd.ttl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(CredentialHelpers.parse("gcloud auth print-access-token", "w")
                        .ttl())
                .isEqualTo(CredentialHelpers.DEFAULT_TTL);
    }

    @Test
    void a_malformed_ttl_or_an_empty_command_is_an_error_naming_the_position() {
        assertThatThrownBy(() -> CredentialHelpers.parse("op read x #ttl=soon", "repositories.gar.token"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageStartingWith("repositories.gar.token")
                .hasMessageContaining("#ttl=5m");
        assertThatThrownBy(() -> CredentialHelpers.parse("   ", "repositories.gar.token"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("no command");
    }

    @Test
    void the_value_is_the_helpers_trimmed_stdout() {
        String value = CredentialHelpers.run(
                List.of("/bin/sh", "-c", "printf '  ya29.token \\n'"), Map.of(), Duration.ofSeconds(10), "w");

        assertThat(value).isEqualTo("ya29.token");
    }

    @Test
    void a_non_zero_exit_names_the_position_the_command_and_its_stderr() {
        assertThatThrownBy(() -> CredentialHelpers.run(
                        List.of("/bin/sh", "-c", "echo 'not logged in' >&2; exit 3"),
                        Map.of(),
                        Duration.ofSeconds(10),
                        "repositories.gar.token"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageStartingWith("repositories.gar.token")
                .hasMessageContaining("exited 3")
                .hasMessageContaining("not logged in");
    }

    @Test
    void empty_output_is_an_error() {
        assertThatThrownBy(() -> CredentialHelpers.run(
                        List.of("/bin/sh", "-c", "true"), Map.of(), Duration.ofSeconds(10), "repositories.gar.token"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("printed nothing");
    }

    @Test
    void a_helper_that_does_not_answer_in_time_is_killed_and_the_resolve_fails() {
        long start = Clock.SYSTEM.nanos();
        assertThatThrownBy(() -> CredentialHelpers.run(
                        List.of("/bin/sh", "-c", "sleep 30"),
                        Map.of(),
                        Duration.ofMillis(300),
                        "repositories.gar.token"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("did not finish within");
        assertThat(Duration.ofNanos(Clock.SYSTEM.nanos() - start)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void the_executable_is_found_on_the_requests_path_not_the_engines() {
        assertThat(CredentialHelpers.executable("sh", Map.of("PATH", "/nowhere:/bin:/usr/bin"), "w"))
                .endsWith("/sh");
        assertThatThrownBy(() -> CredentialHelpers.executable("sh", Map.of("PATH", "/nowhere"), "repositories.x.token"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("not on your PATH");
    }

    @Test
    void a_cached_value_is_reused_until_its_ttl_and_a_changed_environment_runs_again() {
        AtomicInteger runs = countingRunner(0);
        AtomicLong now = new AtomicLong(0);
        CredentialHelpers.clock(now::get);
        var cmd = CredentialHelpers.parse("helper #ttl=1m", "w");

        assertThat(CredentialHelpers.value(cmd, Map.of("A", "1"), "w")).isEqualTo("token-1");
        assertThat(CredentialHelpers.value(cmd, Map.of("A", "1"), "w")).isEqualTo("token-1");
        assertThat(CredentialHelpers.value(cmd, Map.of("A", "2"), "w")).isEqualTo("token-2");
        now.set(Duration.ofMinutes(2).toNanos());
        assertThat(CredentialHelpers.value(cmd, Map.of("A", "1"), "w")).isEqualTo("token-3");
        assertThat(runs).hasValue(3);
    }

    @Test
    void a_forgotten_value_runs_the_helper_again_and_a_newer_one_is_kept() {
        AtomicInteger runs = countingRunner(0);
        var cmd = CredentialHelpers.parse("helper", "w");
        assertThat(CredentialHelpers.value(cmd, Map.of(), "w")).isEqualTo("token-1");
        assertThat(CredentialHelpers.produced("token-1")).isTrue();

        CredentialHelpers.forget("token-1");
        assertThat(CredentialHelpers.value(cmd, Map.of(), "w")).isEqualTo("token-2");
        // A second refusal of the stale value, from a concurrent request, does not run it a third time.
        CredentialHelpers.forget("token-1");
        assertThat(CredentialHelpers.value(cmd, Map.of(), "w")).isEqualTo("token-2");
        assertThat(runs).hasValue(2);
        assertThat(CredentialHelpers.produced("literal")).isFalse();
    }

    @Test
    void fifty_concurrent_resolves_share_one_run() throws Exception {
        AtomicInteger runs = countingRunner(100);
        var cmd = CredentialHelpers.parse("helper", "w");
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        List<String> values = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 50; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                values.add(CredentialHelpers.value(cmd, Map.of(), "w"));
            }));
        }
        start.countDown();
        for (Thread t : threads) t.join(10_000);

        assertThat(runs).hasValue(1);
        assertThat(values).hasSize(50).containsOnly("token-1");
    }

    @Test
    void a_project_value_may_not_run_a_command() {
        var r = new ReferenceResolver(ReferenceResolver.strict(Map.<String, String>of()::get, "w"), () -> Path.of("."));

        assertThatThrownBy(() -> r.expand("${cmd:id}", "repositories.corp.token", ReferenceResolver.Origin.PROJECT))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("may not run a command");
    }

    @Test
    void a_command_reference_anywhere_in_a_manifest_is_a_parse_error() {
        assertThatThrownBy(() -> Interpolation.guard(Toml.parse("""
                        [repositories.gar]
                        url = "https://us-maven.pkg.dev/acme/maven/"
                        token = "${cmd:gcloud auth print-access-token}"
                        """)))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("repositories.gar.token (${cmd:gcloud auth print-access-token})")
                .hasMessageContaining("only in ~/.jk/config.toml");
    }

    @Test
    void a_user_config_value_runs_its_helper() {
        countingRunner(0);
        var r = ReferenceResolver.forUserConfig(Map.<String, String>of()::get, "w");

        assertThat(r.expand("${cmd:helper}", "repositories.gar.token", ReferenceResolver.Origin.USER_CONFIG))
                .isEqualTo("token-1");
    }
}
