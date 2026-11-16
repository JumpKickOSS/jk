// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.StackTrace;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Attributing a suite's recordings holds what it keeps, never the events it reads: a recording of
 * two million file reads in a hundred chunks, the last chunk carrying 32,768 distinct stack traces,
 * is attributed inside a 48 MiB heap. Holding every event of a recording before joining it ran the
 * engine out of heap on jk-cli's suite (6.2 million reads, 240 MB of the engine's heap) in a release
 * gate; the JDK's own parser, which builds every constant pool of a chunk, does not fit 48 MiB here
 * either.
 *
 * <p>The recording is made in a child JVM, never in this one: an event this JVM committed would also
 * land in its own suite recording, which the engine running this suite reads when it ends. What the
 * children print reaches a failure message without the name of the heap error, which the engine
 * would take for this suite running out of heap.
 */
class ClassReadsMemoryTest {

    private static final int READS = 2_000_000;
    private static final int PER_CHUNK = 20_000;
    private static final int DEEP = 100_000;

    /** The test runner's window event, as the recording names it. */
    @Name(ClassReads.WINDOW_EVENT)
    @StackTrace(false)
    static final class Window extends Event {
        @Nullable
        String testClass;
    }

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
    void a_recording_of_millions_of_reads_is_attributed_in_a_small_heap(@TempDir Path tmp) throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        int files = 16;
        for (int i = 0; i < files; i++) Files.writeString(ws.resolve("data-" + i + ".txt"), "x");
        Path recordings = Files.createDirectories(tmp.resolve("rec"));
        assertThat(child(tmp, "-Xmx256m", "make", recordings, ws)).contains("exit=0", "made");

        String out = child(tmp, "-Xmx48m", "attribute", recordings, ws);
        assertThat(out).contains("exit=0", "complete=true", "classes=1", "reads=" + ClassReads.READS_KEPT, "more=true");
        long events = Long.parseLong(out.replaceAll("(?s).*events=(\\d+).*", "$1"));
        assertThat(events).as("every read joined").isGreaterThanOrEqualTo(READS);
    }

    /**
     * Run {@link Child} in a fresh JVM; its exit and output, without the name of a heap error: a
     * failure row naming one is taken for this suite's own heap running out.
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

    /** {@code make}: write the recording under {@code args[1]}; {@code attribute}: attribute it. */
    static final class Child {
        public static void main(String[] args) throws IOException {
            Path recordings = Path.of(args[1]);
            Path ws = Path.of(args[2]);
            if (args[0].equals("make")) {
                make(recordings, ws);
                System.out.println("made");
                return;
            }
            ClassReads.Attributed seen = ClassReads.attribute(
                    recordings, ws, new ClassReads.Layout(List.of(), List.of(), Set.of(), ws, null));
            ClassReads.Observation big = Objects.requireNonNull(seen.classes().get("demo.BigTest"));
            System.out.println("complete=" + seen.complete() + " classes="
                    + seen.classes().size() + " reads=" + big.reads().size() + " more=" + big.readsMore() + " events="
                    + seen.events());
        }

        private static void make(Path recordings, Path ws) throws IOException {
            String[] data = new String[16];
            for (int i = 0; i < data.length; i++)
                data[i] = ws.resolve("data-" + i + ".txt").toString();
            try (Recording r = new Recording()) {
                r.enable("jdk.FileRead").withThreshold(Duration.ZERO).withStackTrace();
                r.enable(ClassReads.WINDOW_EVENT).withThreshold(Duration.ZERO);
                r.start();
                Window w = new Window();
                w.testClass = "demo.BigTest";
                w.begin();
                for (int i = 0; i < READS; i++) {
                    if (i % PER_CHUNK == 0) {
                        // another recording starting and stopping rotates the chunk
                        try (Recording rotate = new Recording()) {
                            rotate.start();
                        }
                    }
                    String path = data[i % data.length];
                    if (i < READS - DEEP) read(path);
                    else deep(i & 0x7fff, 15, path);
                }
                w.commit();
                r.stop();
                r.dump(recordings.resolve("big.jfr"));
            }
        }
    }
}
