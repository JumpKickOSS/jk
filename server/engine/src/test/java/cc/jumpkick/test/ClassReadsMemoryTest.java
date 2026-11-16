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
import org.junit.jupiter.api.io.TempDir;

/**
 * Attributing a suite's recordings holds what it keeps, never the events it reads: a recording of
 * two million file reads is attributed inside a 48 MiB heap. Holding every event of a recording
 * before joining it is what ran the engine out of heap on jk-cli's suite (6.2 million reads, 240 MB
 * of the engine's heap) in a release gate.
 */
class ClassReadsMemoryTest {

    private static final int READS = 2_000_000;

    /** The test runner's window event, as the recording names it. */
    @Name(ClassReads.WINDOW_EVENT)
    @StackTrace(false)
    static final class Window extends Event {
        @Nullable
        String testClass;
    }

    /**
     * A file read as the JDK's own event names it, committed here so a recording of millions of
     * reads takes a second to make instead of millions of real reads.
     */
    @Name("jdk.FileRead")
    @StackTrace(false)
    static final class Read extends Event {
        @Nullable
        String path;
    }

    @Test
    void a_recording_of_millions_of_reads_is_attributed_in_a_small_heap(@TempDir Path tmp) throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path[] data = new Path[16];
        for (int i = 0; i < data.length; i++) data[i] = Files.writeString(ws.resolve("data-" + i + ".txt"), "x");
        Path recordings = Files.createDirectories(tmp.resolve("rec"));
        try (Recording r = new Recording()) {
            r.enable("jdk.FileRead").withThreshold(Duration.ZERO);
            r.enable(ClassReads.WINDOW_EVENT).withThreshold(Duration.ZERO);
            r.start();
            Window w = new Window();
            w.testClass = "demo.BigTest";
            w.begin();
            for (int i = 0; i < READS; i++) {
                Read read = new Read();
                read.path = data[i % data.length].toString();
                read.commit();
            }
            w.commit();
            r.stop();
            r.dump(recordings.resolve("big.jfr"));
        }

        Path log = tmp.resolve("child.log");
        Process child = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-Xmx48m",
                        "-XX:+ExitOnOutOfMemoryError",
                        "-cp",
                        System.getProperty("java.class.path"),
                        Child.class.getName(),
                        recordings.toString(),
                        ws.toString())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        try {
            assertThat(child.waitFor(120, TimeUnit.SECONDS))
                    .as("attributed in time")
                    .isTrue();
        } finally {
            child.destroyForcibly();
        }
        String out = Files.readString(log);
        assertThat(child.exitValue()).as(out).isZero();
        assertThat(out).contains("complete=true", "classes=1", "reads=" + data.length);
        long events = Long.parseLong(out.replaceAll("(?s).*events=(\\d+).*", "$1"));
        assertThat(events).as("every read joined").isGreaterThanOrEqualTo(READS);
    }

    /** Attributes the recordings under {@code args[0]} for a workspace at {@code args[1]}. */
    static final class Child {
        public static void main(String[] args) throws IOException {
            Path ws = Path.of(args[1]);
            ClassReads.Attributed seen = ClassReads.attribute(
                    Path.of(args[0]), ws, new ClassReads.Layout(List.of(), List.of(), Set.of(), ws, null));
            ClassReads.Observation big = Objects.requireNonNull(seen.classes().get("demo.BigTest"));
            System.out.println("complete=" + seen.complete() + " classes="
                    + seen.classes().size() + " reads=" + big.reads().size() + " events=" + seen.events());
        }
    }
}
