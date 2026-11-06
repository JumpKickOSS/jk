// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;

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
 * <p>Ten sites spelled that pair themselves and three of them — {@code jk gradle}, {@code jk mvn},
 * {@code jk self update}'s engine takeover — did only the second half. G27
 * (`checkOneTerminalHandoff`) is what keeps an eleventh site from doing the same; this asserts what
 * the owner actually does, which a text scan cannot.
 *
 * <p>The restore half has no observable effect in a test JVM — {@code Terminals.restoreForChild()}
 * is a documented no-op when no native session was ever opened, and there is no seam to inject one
 * (owns that gap). So this pins the half that *is* observable, and says plainly that it is
 * a half.
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
     * of waiting on the test JVM's stdin. The child reads one byte and exits 0 only on EOF; it is
     * stopped at the deadline when its read blocks.
     */
    @Test
    void a_child_that_reads_the_handed_off_stdin_finishes_under_test(@TempDir Path dir) throws Exception {
        Path java = JdkFingerprint.tool(Path.of(System.getProperty("java.home")), "java");
        Path source = dir.resolve("ReadsStdin.java");
        Files.writeString(source, """
                class ReadsStdin {
                    public static void main(String[] args) throws Exception {
                        System.exit(System.in.read() == -1 ? 0 : 3);
                    }
                }
                """);

        Process child = CliOutput.handOffTerminal(new ProcessBuilder(java.toString(), source.toString()));
        try {
            assertThat(child.waitFor(60, TimeUnit.SECONDS))
                    .describedAs("the child is still waiting for input on the test JVM's stdin")
                    .isTrue();
            assertThat(child.exitValue())
                    .describedAs("the child read input, not end-of-input")
                    .isZero();
        } finally {
            child.destroyForcibly();
        }
    }
}
