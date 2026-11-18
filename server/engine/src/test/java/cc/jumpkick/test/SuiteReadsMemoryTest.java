// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
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
 * the same recording does not fit 48 MiB. No number of files costs a run its replay: a run that
 * reads and writes 200,000 files in its own sandbox is read whole in a 48 MiB heap, holding only
 * its few real inputs, and what it keeps is exactly what the whole set would have given.
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

    /** A program start as the JDK's own event names it. */
    @Name("jdk.ProcessStart")
    static final class Start extends Event {
        @Nullable
        String command;
    }

    /** A file write as the JDK's own event names it. */
    @Name("jdk.FileWrite")
    static final class Write extends Event {
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

    private static void write(String path) {
        Write write = new Write();
        write.path = path;
        write.commit();
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
    @Timeout(300)
    void a_run_naming_200000_files_in_its_sandbox_is_read_whole_in_a_small_heap(@TempDir Path tmp) throws Exception {
        Path recordings = Files.createDirectories(tmp.resolve("rec"));
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        for (int i = 0; i < 3; i++)
            Files.writeString(Files.createDirectories(ws.resolve("inputs")).resolve("in" + i), "x");
        assertThat(child(tmp, "-Xmx256m", "wide", recordings, ws)).contains("exit=0", "made");
        String out = child(tmp, "-Xmx48m", "sandboxed", recordings, ws);
        assertThat(out).contains("exit=0", "complete=true files=3");
    }

    /**
     * Holding only the candidates while reading changes nothing: the inputs kept are the ones the
     * whole set gives, a file the run wrote included, a file that is gone excluded.
     */
    @Test
    @Timeout(120)
    void the_candidates_give_the_inputs_the_whole_set_gives(@TempDir Path tmp) throws Exception {
        Path recordings = Files.createDirectories(tmp.resolve("rec"));
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path inputs = Files.createDirectories(ws.resolve("inputs"));
        for (int i = 0; i < 50; i++) Files.writeString(inputs.resolve("in" + i), "x");
        Files.writeString(inputs.resolve("own"), "written by the run");
        assertThat(child(tmp, "-Xmx256m", "mixed", recordings, ws)).contains("exit=0", "made");
        List<Path> roots = List.of(ws.resolve("sandbox"), ws.resolve("target"));
        SuiteReads.Observed whole = SuiteReads.collect(recordings, ws);
        SuiteReads.Observed held = SuiteReads.collect(recordings, ws, ws, SuiteReads.candidates(roots));
        assertThat(held.files().size()).isLessThan(whole.files().size());
        Set<Path> fromWhole = SuiteReads.undeclared(whole, roots, ws, 0);
        assertThat(SuiteReads.undeclared(held, roots, ws, 0))
                .isEqualTo(fromWhole)
                .hasSize(50);
    }

    /**
     * A recording the streaming reader declines (a chunk whose string pool passes its cap) is read by
     * the JDK's parser in a child JVM instead, never by neither: what it says still comes back.
     */
    @Test
    @Timeout(300)
    void a_recording_the_fast_reader_declines_is_read_by_the_jdk_parser_in_a_child(@TempDir Path tmp) throws Exception {
        Path recordings = Files.createDirectories(tmp.resolve("rec"));
        assertThat(child(tmp, "-Xmx256m", "pool", recordings, tmp)).contains("exit=0", "made");
        SuiteReads.Observed seen = SuiteReads.collect(recordings, tmp);
        assertThat(seen.unread()).isNull();
        assertThat(seen.complete()).isTrue();
        assertThat(seen.commands()).containsExactly("/opt/tool/bin/fake");
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
                case "mixed" -> mixed(recordings, ws);
                case "sandboxed" -> {
                    SuiteReads.Observed seen = SuiteReads.collect(
                            recordings, ws, ws, SuiteReads.candidates(List.of(ws.resolve("sandbox"))));
                    // The recording JVM's own reads (its classpath) are outside the workspace.
                    long inWs =
                            seen.files().stream().filter(f -> f.startsWith(ws)).count();
                    System.out.println("complete=" + seen.complete() + " files=" + inWs);
                    return;
                }
                case "pool" -> pool(recordings);
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

        /** Distinct long command lines: one chunk's string pool far past the reader's cap. */
        private static void pool(Path recordings) throws IOException {
            String pad = "x".repeat(200);
            try (Recording r = new Recording()) {
                r.enable("jdk.ProcessStart").withThreshold(Duration.ZERO);
                r.start();
                for (int i = 0; i < 100_000; i++) {
                    Start start = new Start();
                    start.command = "/opt/tool/bin/fake --run " + i + " " + pad;
                    start.commit();
                }
                r.stop();
                r.dump(recordings.resolve("pool.jfr"));
            }
        }

        /**
         * 200,000 files read and written in the sandbox, in chunks of 20,000 as a long suite's
         * recording comes out, and three real inputs read.
         */
        private static void wide(Path recordings, Path ws) throws IOException {
            try (Recording r = new Recording()) {
                r.enable("jdk.FileRead").withThreshold(Duration.ZERO);
                r.enable("jdk.FileWrite").withThreshold(Duration.ZERO);
                r.start();
                for (int i = 0; i < 200_000; i++) {
                    if (i % 20_000 == 0) {
                        try (Recording rotate = new Recording()) {
                            rotate.start();
                        }
                    }
                    String path = ws.resolve("sandbox/t" + (i % 1000) + "/f" + i + ".txt")
                            .toString();
                    write(path);
                    read(path);
                }
                for (int i = 0; i < 3; i++) read(ws.resolve("inputs/in" + i).toString());
                r.stop();
                r.dump(recordings.resolve("wide.jfr"));
            }
        }

        /** Inputs, a file the run wrote, a file now gone, and files under the two roots. */
        private static void mixed(Path recordings, Path ws) throws IOException {
            try (Recording r = new Recording()) {
                r.enable("jdk.FileRead").withThreshold(Duration.ZERO);
                r.enable("jdk.FileWrite").withThreshold(Duration.ZERO);
                r.start();
                for (int i = 0; i < 50; i++) read(ws.resolve("inputs/in" + i).toString());
                write(ws.resolve("inputs/own").toString());
                read(ws.resolve("inputs/own").toString());
                read(ws.resolve("inputs/gone").toString());
                for (int i = 0; i < 500; i++) {
                    read(ws.resolve("sandbox/s" + i).toString());
                    write(ws.resolve("target/t" + i).toString());
                    read(ws.resolve("target/t" + i).toString());
                }
                r.stop();
                r.dump(recordings.resolve("mixed.jfr"));
            }
        }
    }
}
