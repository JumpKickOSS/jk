// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.jdk.JdkFingerprint;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link CliOutput#handOffTerminal} is the one place a CLI command gives this terminal to a child
 *: restore the terminal out of jk's own mode, then start the child on inherited stdio.
 *
 * <p>The guard {@code checkOneTerminalHandoff} keeps other sites from spelling the pair
 * themselves; this asserts what the owner does, which a text scan cannot. The restore half has no
 * observable effect in a test JVM ({@code Terminals.restoreForChild()} is a no-op when no native
 * session was opened), so only the inherited-stdio half is pinned here.
 */
class TerminalHandoffTest {

    /**
     * Inherited stdio means the child writes to <em>our</em> file descriptors, so the parent's pipe
     * streams are the JDK's null streams and read EOF immediately. Without {@code inheritIO()},
     * {@code java -version} — which writes to stderr — would be readable here, so this
     * distinguishes the handoff from a plain {@code start()}.
     */
    @Test
    void the_handoff_gives_the_child_our_file_descriptors() throws Exception {
        Path java = JdkFingerprint.tool(Path.of(System.getProperty("java.home")), "java");

        Process child = CliOutput.handOffTerminal(new ProcessBuilder(java.toString(), "-version"));

        assertThat(child.getErrorStream().read())
                .describedAs("stderr is a pipe, so the child did not inherit our descriptors")
                .isEqualTo(-1);
        assertThat(child.getInputStream().read()).isEqualTo(-1);
        assertThat(child.waitFor()).isZero();
    }

    /** The builder the caller configured is the one that runs — cwd and argv survive the handoff. */
    @Test
    void the_handoff_starts_the_builder_it_was_given() throws Exception {
        Path java = JdkFingerprint.tool(Path.of(System.getProperty("java.home")), "java");
        Path cwd = Path.of(System.getProperty("java.io.tmpdir"));
        ProcessBuilder pb = new ProcessBuilder(java.toString(), "-version").directory(cwd.toFile());

        Process child = CliOutput.handOffTerminal(pb);

        assertThat(child.waitFor()).isZero();
        assertThat(pb.directory()).isEqualTo(cwd.toFile());
        assertThat(pb.redirectInput().file()).isNull(); // INHERIT, not a file redirect
    }

    /**
     * A child given the terminal under test reads end-of-input, so a REPL or a prompt ends instead
     * of waiting on the test JVM's stdin. The child is a class already compiled onto the test
     * classpath, so its start is a JVM start and nothing more; it marks that it started, reads one
     * byte and exits 0 only on EOF. A child that started and is still reading is the defect; one
     * that never started within the wait says nothing about stdin, and the test is aborted.
     */
    @Test
    void a_child_that_reads_the_handed_off_stdin_finishes_under_test(@TempDir Path dir) throws Exception {
        Path java = JdkFingerprint.tool(Path.of(System.getProperty("java.home")), "java");
        Path started = dir.resolve("started");

        Process child = CliOutput.handOffTerminal(new ProcessBuilder(
                java.toString(),
                "-Xshare:auto",
                "-XX:TieredStopAtLevel=1",
                "-XX:+UseSerialGC",
                "-cp",
                System.getProperty("java.class.path"),
                ReadsStdin.class.getName(),
                started.toString()));
        try {
            boolean exited = child.waitFor(15, TimeUnit.SECONDS);
            if (!exited) {
                assumeTrue(Files.exists(started), "the child JVM did not start within 15 s on this host");
            }
            assertThat(exited)
                    .describedAs("the child started and is still waiting for input on the test JVM's stdin")
                    .isTrue();
            assertThat(child.exitValue())
                    .describedAs("the child read input, not end-of-input")
                    .isZero();
        } finally {
            child.destroyForcibly();
        }
    }

    /** Marks that it started in the file {@code args[0]}, then exits 0 only when stdin is at end-of-input. */
    public static final class ReadsStdin {
        public static void main(String[] args) throws Exception {
            Files.writeString(Path.of(args[0]), "started");
            System.exit(System.in.read() == -1 ? 0 : 3);
        }
    }
}
