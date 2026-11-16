// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reading a suite's recordings holds what it keeps, never the recording: two million file reads in
 * a hundred chunks, the last carrying 32,768 distinct stack traces from an in-process recording,
 * are read inside a 48 MiB heap. The JDK's own parser resolves every constant pool of a chunk
 * first, and one such chunk took 160 MB of the engine's 256 MiB heap and ended it; on that parser
 * the same recording does not fit 48 MiB. A run that names more files than {@link
 * SuiteReads#MAX_PATHS} is incomplete rather than held whole.
 *
 * <p>The recordings are made in a child JVM: an event this JVM committed would also land in its
 * own suite recording, which the engine running this suite reads when it ends.
 */
class SuiteReadsMemoryTest {

    private static final int READS = 2_000_000;
    private static final int PER_CHUNK = 20_000;
    private static final int DEEP = 100_000;

    /** A file read as the JDK's own event names it, committed here so millions take seconds. */
    @Name("jdk.FileRead")
    static final class Read extends Event {
        @Nullable
        String path;
    }

    static long sink;

    /** Commit along the bits of {@code n}, so each {@code n} below 2^depth has its own stack. */
    static void deep(int n, int depth, String path) {
        if (depth == 0) {
            read(path);
            sink++;
            return;
        }
        if ((n & 1) == 0) left(n >>> 1, depth - 1, path);
        else right(n >>> 1, depth - 1, path);
    }

    static void left(int n, int depth, String path) {
        deep(n, depth, path);
    }

    static void right(int n, int depth, String path) {
        deep(n, depth, path);
    }

    private static void read(String path) {
        Read read = new Read();
        read.path = path;
        read.commit();
    }

    @Test
    @Timeout(300)
    void millions_of_reads_in_many_chunks_with_stack_traces_read_in_a_small_heap(@TempDir Path tmp) throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        int files = 32;
        for (int i = 0; i < files; i++) Files.writeString(ws.resolve("d" + i), "x");
        Path recordings = Files.createDirectories(tmp.resolve("rec"));
        assertThat(child(tmp, "-Xmx256m", "deep", recordings, ws)).contains("exit=0", "made");

        String out = child(tmp, "-Xmx48m", "collect", recordings, ws);
        assertThat(out).contains("exit=0", "complete=true files=" + files);
    }

    @Test
    @Timeout(120)
    void a_run_naming_more_files_than_the_cap_is_incomplete(@TempDir Path tmp) throws Exception {
        Path recordings = Files.createDirectories(tmp.resolve("rec"));
        assertThat(child(tmp, "-Xmx256m", "wide", recordings, tmp)).contains("exit=0", "made");
        SuiteReads.Observed seen = SuiteReads.collect(recordings, tmp);
        assertThat(seen.complete()).isFalse();
        assertThat(seen.files()).isEmpty();
    }

    /**
     * Run {@link Child} in a fresh JVM; its exit and output, without the name of a heap error: a
     * failure row naming one is taken for this suite's own heap running out, and would grow the
     * heap this suite is given next time.
     */
    private static String child(Path tmp, String heap, String mode, Path recordings, Path ws) throws Exception {
        Path log = Files.createTempFile(tmp, mode, ".log");
        Process p = new ProcessBuilder(List.of(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        heap,
                        "-XX:+ExitOnOutOfMemoryError",
                        "-cp",
                        System.getProperty("java.class.path"),
                        Child.class.getName(),
                        mode,
                        recordings.toString(),
                        ws.toString()))
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        try {
            assertThat(p.waitFor(240, TimeUnit.SECONDS)).as(mode + " in time").isTrue();
        } finally {
            p.destroyForcibly();
        }
        return ("exit=" + p.exitValue() + " " + Files.readString(log))
                .replace("OutOfMemoryError", "out-of-memory error");
    }

    /** {@code deep} or {@code wide}: make a recording under {@code args[1]}; {@code collect}: read it. */
    static final class Child {
        public static void main(String[] args) throws IOException {
            Path recordings = Path.of(args[1]);
            Path ws = Path.of(args[2]);
            switch (args[0]) {
                case "deep" -> deep(recordings, ws);
                case "wide" -> wide(recordings, ws);
                default -> {
                    SuiteReads.Observed seen = SuiteReads.collect(recordings, ws);
                    long data =
                            seen.files().stream().filter(f -> f.startsWith(ws)).count();
                    System.out.println("complete=" + seen.complete() + " files=" + data);
                    return;
                }
            }
            System.out.println("made");
        }

        private static void deep(Path recordings, Path ws) throws IOException {
            String[] data = new String[32];
            for (int i = 0; i < data.length; i++) data[i] = ws.resolve("d" + i).toString();
            try (Recording r = new Recording()) {
                r.enable("jdk.FileRead").withThreshold(Duration.ZERO).withStackTrace();
                r.start();
                for (int i = 0; i < READS; i++) {
                    if (i % PER_CHUNK == 0) {
                        // another recording starting and stopping rotates the chunk
                        try (Recording rotate = new Recording()) {
                            rotate.start();
                        }
                    }
                    String path = data[i % data.length];
                    // the last chunk carries tens of thousands of distinct stacks in its pools
                    if (i < READS - DEEP) read(path);
                    else SuiteReadsMemoryTest.deep(i & 0x7fff, 15, path);
                }
                r.stop();
                r.dump(recordings.resolve("big.jfr"));
            }
        }

        private static void wide(Path recordings, Path tmp) throws IOException {
            try (Recording r = new Recording()) {
                r.enable("jdk.FileRead").withThreshold(Duration.ZERO);
                r.start();
                for (int i = 0; i <= SuiteReads.MAX_PATHS; i++)
                    read(tmp.resolve("f" + i).toString());
                r.stop();
                r.dump(recordings.resolve("wide.jfr"));
            }
        }
    }
}
