// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs({OS.LINUX, OS.MAC})
class ProcessesTest {

    private static List<String> sh(String script) {
        return List.of("sh", "-c", script);
    }

    @Test
    void a_child_that_never_exits_or_closes_its_output_times_out_and_is_killed() {
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            ProcessBuilder pb = new ProcessBuilder(sh("echo started; sleep 60")).redirectErrorStream(true);
            Process child = pb.start();
            Processes.Captured c = Processes.capture(pb, child, Duration.ofMillis(500));
            assertThat(c.timedOut()).isTrue();
            assertThat(c.ok()).isFalse();
            assertThat(c.exit()).isEqualTo(-1);
            assertThat(child.waitFor(5, TimeUnit.SECONDS))
                    .as("killed at the deadline")
                    .isTrue();
        });
    }

    /** The child exits, but the grandchild it left behind keeps the pipe open past the deadline. */
    @Test
    void a_descendant_holding_the_pipe_does_not_hold_the_caller() {
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            Processes.Captured c = Processes.capture(sh("sleep 5 & echo done"), Duration.ofSeconds(1));
            assertThat(c.timedOut()).isFalse();
            assertThat(c.exit()).isZero();
            assertThat(c.output().strip()).isEqualTo("done");
        });
    }

    /** Only a stderr of its own needs a thread to drain it; a merged or redirected one has no pipe. */
    @Test
    void a_merged_or_redirected_stderr_is_not_drained() throws Exception {
        assertThat(Processes.ownStderr(new ProcessBuilder(sh("true")).redirectErrorStream(true)))
                .isFalse();
        assertThat(Processes.ownStderr(new ProcessBuilder(sh("true")).redirectError(ProcessBuilder.Redirect.DISCARD)))
                .isFalse();
        assertThat(Processes.ownStderr(new ProcessBuilder(sh("true")))).isTrue();

        ProcessBuilder discard = new ProcessBuilder(sh("echo gone >&2")).redirectError(ProcessBuilder.Redirect.DISCARD);
        ProcessBuilder keep = new ProcessBuilder(sh("echo oops >&2"));
        assertThat(Processes.capture(discard, discard.start(), Duration.ofSeconds(10))
                        .error())
                .isEmpty();
        assertThat(Processes.capture(keep, keep.start(), Duration.ofSeconds(10)).error())
                .isEqualTo("oops\n");
        Processes.Captured merged = Processes.capture(sh("echo both >&2"), Duration.ofSeconds(10));
        assertThat(merged.output()).isEqualTo("both\n");
        assertThat(merged.error()).isEmpty();
    }

    /**
     * A child the caller started itself from a builder that merged stderr: its stderr stream is not
     * read on a thread of its own (on Linux it is a real pipe, so the stream alone cannot tell).
     */
    @Test
    void a_started_child_whose_builder_merged_stderr_gets_no_stderr_drain() throws IOException {
        List<String> readers = new CopyOnWriteArrayList<>();
        boolean[] closed = {false};
        InputStream stderr = new InputStream() {
            @Override
            public int read() {
                readers.add(Thread.currentThread().getName());
                return -1;
            }

            @Override
            public void close() {
                closed[0] = true;
            }
        };
        Process child = new Exited(new ByteArrayInputStream("both\n".getBytes(StandardCharsets.UTF_8)), stderr);

        Processes.Captured c = Processes.capture(
                new ProcessBuilder(sh("true")).redirectErrorStream(true), child, Duration.ofSeconds(10));

        assertThat(c.ok()).isTrue();
        assertThat(c.output()).isEqualTo("both\n");
        assertThat(readers).as("threads that read the merged stderr").isEmpty();
        assertThat(closed[0]).as("the unread stderr is closed").isTrue();
    }

    /** A child that has already exited 0, with the given output and stderr streams. */
    private static final class Exited extends Process {
        private final InputStream out;
        private final InputStream err;

        Exited(InputStream out, InputStream err) {
            this.out = out;
            this.err = err;
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return out;
        }

        @Override
        public InputStream getErrorStream() {
            return err;
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {}
    }

    @Test
    void output_if_ok_is_no_answer_on_a_failure_or_a_timeout() {
        assertThat(Processes.outputIfOk(sh("echo yes"), Duration.ofSeconds(10))).isEqualTo("yes\n");
        assertThat(Processes.outputIfOk(sh("echo missing; exit 1"), Duration.ofSeconds(10)))
                .isNull();
        assertThat(Processes.outputIfOk(sh("echo started; sleep 60"), Duration.ofMillis(300)))
                .isNull();
        assertThat(Processes.outputIfOk(List.of("/nonexistent/jk-no-such-tool"), Duration.ofSeconds(10)))
                .isNull();
    }

    @Test
    void bytes_if_ok_keeps_output_that_is_not_utf8() {
        // "U" in UTF-16LE behind its byte-order mark: FF FE 55 00, which a UTF-8 decode would mangle.
        assertThat(Processes.bytesIfOk(sh("printf '\\377\\376U\\000'"), Duration.ofSeconds(10)))
                .containsExactly(0xFF, 0xFE, 0x55, 0x00);
        assertThat(Processes.bytesIfOk(sh("printf x; exit 1"), Duration.ofSeconds(10)))
                .isNull();
    }

    @Test
    void output_larger_than_the_pipe_buffer_is_read_whole() throws IOException {
        Processes.Captured c = Processes.capture(sh("head -c 200000 /dev/zero | tr '\\0' x"), Duration.ofSeconds(10));
        assertThat(c.ok()).isTrue();
        assertThat(c.output()).hasSize(200_000);
    }

    @Test
    void the_limit_keeps_the_head_and_still_drains_the_rest() throws IOException {
        ProcessBuilder pb = new ProcessBuilder(sh("head -c 200000 /dev/zero | tr '\\0' x"));
        Processes.Captured c = Processes.capture(pb, pb.start(), null, Duration.ofSeconds(10), 4096);
        assertThat(c.ok()).isTrue();
        assertThat(c.output()).hasSize(4096);
    }

    @Test
    void stdin_is_written_and_stderr_kept_apart() throws IOException {
        ProcessBuilder pb = new ProcessBuilder(sh("cat; echo oops >&2; exit 3"));
        Processes.Captured c = Processes.capture(
                pb, pb.start(), "secret".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(10), 1 << 20);
        assertThat(c.exit()).isEqualTo(3);
        assertThat(c.output()).isEqualTo("secret");
        assertThat(c.error().strip()).isEqualTo("oops");
    }

    /** A child that never reads its input must not hold the caller past the deadline on a full pipe. */
    @Test
    void stdin_larger_than_the_pipe_buffer_cannot_outlast_the_deadline() {
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            ProcessBuilder pb = new ProcessBuilder(sh("sleep 60"));
            Process child = pb.start();
            Processes.Captured c = Processes.capture(pb, child, new byte[4 << 20], Duration.ofMillis(500), 1 << 20);
            assertThat(c.timedOut()).isTrue();
            assertThat(child.waitFor(5, TimeUnit.SECONDS))
                    .as("killed at the deadline")
                    .isTrue();
        });
    }

    @Test
    void the_last_lines_of_an_output_are_kept() {
        assertThat(Processes.lastLines("a\nb\nc\nd", 2)).isEqualTo("c\nd");
        assertThat(Processes.lastLines("only", 5)).isEqualTo("only");
    }
}
