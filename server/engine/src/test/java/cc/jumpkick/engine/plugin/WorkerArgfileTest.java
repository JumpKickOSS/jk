// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jdk.JdkFingerprint;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A worker command line that would overflow Windows' cap launches through a Java {@code @argfile}
 * instead, and what the launcher reads back from that file is the argument list it was given.
 */
class WorkerArgfileTest {

    @Test
    void a_short_command_is_left_alone_on_every_host() throws IOException {
        List<String> cmd = List.of("java", "-cp", "a.jar", "Main", "arg");
        assertThat(WorkerArgfile.shorten(cmd, true, WorkerArgfile.LIMIT).command())
                .isEqualTo(cmd);
        assertThat(WorkerArgfile.shorten(cmd, false, 1).command())
                .as("only Windows caps the command line")
                .isEqualTo(cmd);
    }

    @Test
    void a_long_command_becomes_the_launcher_plus_one_argfile_and_the_file_goes_when_deleted() throws IOException {
        List<String> cmd = List.of("java", "-Dx=1", "-cp", "a".repeat(200), "Main", "arg");
        WorkerArgfile shortened = WorkerArgfile.shorten(cmd, true, 100);
        assertThat(shortened.command()).hasSize(2).startsWith("java");
        assertThat(shortened.command().get(1)).startsWith("@");
        Path file = shortened.file();
        assertThat(file).isNotNull().isRegularFile();
        assertThat(Files.readAllLines(file))
                .containsExactly("\"-Dx=1\"", "\"-cp\"", "\"" + "a".repeat(200) + "\"", "\"Main\"", "\"arg\"");
        shortened.delete();
        assertThat(file).doesNotExist();
    }

    @Test
    void quoting_escapes_the_two_characters_the_launcher_reads_specially() {
        assertThat(WorkerArgfile.quote("plain")).isEqualTo("\"plain\"");
        assertThat(WorkerArgfile.quote("C:\\a b\\c")).isEqualTo("\"C:\\\\a b\\\\c\"");
        assertThat(WorkerArgfile.quote("say \"hi\"")).isEqualTo("\"say \\\"hi\\\"\"");
    }

    /**
     * The launcher itself is the parser that matters: a JVM started through the argfile must see
     * a path with spaces, backslashes and a quote exactly as it was written, or the file is wrong
     * in a way no string assertion catches.
     */
    @Test
    void the_java_launcher_reads_back_the_arguments_the_argfile_carries(@TempDir Path tmp) throws Exception {
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path marker = tmp.resolve("has space").resolve("out.txt");
        Files.createDirectories(marker.getParent());
        String value = "a \"quoted\" back\\slash";
        List<String> cmd = new ArrayList<>(List.of(
                JdkFingerprint.java(javaHome).toString(),
                "-Dprobe.path=" + marker,
                "-Dprobe.value=" + value,
                "-cp",
                System.getProperty("java.class.path"),
                Probe.class.getName()));
        WorkerArgfile shortened = WorkerArgfile.shorten(cmd, true, 10);
        assertThat(shortened.command()).hasSize(2);
        try {
            Process p = new ProcessBuilder(shortened.command())
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes());
            assertThat(p.waitFor(60, TimeUnit.SECONDS)).as("launcher exited").isTrue();
            assertThat(p.exitValue()).as(out).isZero();
            assertThat(Files.readString(marker)).isEqualTo(value);
        } finally {
            shortened.delete();
        }
    }

    /** Writes {@code probe.value} into the file {@code probe.path} names. */
    public static final class Probe {
        public static void main(String[] args) throws IOException {
            Files.writeString(Path.of(System.getProperty("probe.path")), System.getProperty("probe.value"));
        }
    }
}
