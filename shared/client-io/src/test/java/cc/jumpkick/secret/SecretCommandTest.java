// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.host.Os;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class SecretCommandTest {

    @Test
    void the_real_runner_feeds_stdin_and_keeps_both_streams_apart() throws Exception {
        assumeTrue(!Os.isWindows(), "POSIX shell");
        SecretCommand.Result r = SecretCommand.REAL.run(
                List.of("sh", "-c", "cat; echo oops >&2; exit 3"),
                "s3cret".getBytes(StandardCharsets.UTF_8),
                Duration.ofSeconds(10));

        assertThat(r.exit()).isEqualTo(3);
        assertThat(r.stdout()).isEqualTo("s3cret");
        assertThat(r.stderr()).isEqualTo("oops\n");
        assertThat(r.complaint()).isEqualTo("oops");
    }

    @Test
    void the_real_runner_gives_up_on_a_tool_that_outlives_its_cap() {
        assumeTrue(!Os.isWindows(), "POSIX shell");
        assertThatThrownBy(() -> SecretCommand.REAL.run(List.of("sleep", "30"), null, Duration.ofMillis(200)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("did not answer");
    }

    @Test
    void a_tool_that_is_not_installed_is_an_io_failure() {
        assertThatThrownBy(
                        () -> SecretCommand.REAL.run(List.of("jk-no-such-keychain-tool"), null, Duration.ofSeconds(5)))
                .isInstanceOf(IOException.class);
    }
}
