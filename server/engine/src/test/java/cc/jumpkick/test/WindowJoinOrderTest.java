// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A recording lists one thread's events out of time order, so the join goes by time, not by listing. */
class WindowJoinOrderTest {

    @Test
    void a_threads_first_read_is_its_earliest_even_when_listed_late(@TempDir Path tmp) throws Exception {
        Path jar = Files.write(tmp.resolve("worker.jar"), new byte[] {1});
        Path other = Files.write(tmp.resolve("other.jar"), new byte[] {2});
        Path data = Files.writeString(tmp.resolve("data.txt"), "x");
        ClassReads.Layout layout = new ClassReads.Layout(List.of(), List.of(), Set.of(), tmp, null);
        Map<String, ClassReads.Observation> classes = new HashMap<>();
        ClassReads.Observation pooled = ClassReads.Observation.pool();
        WindowJoin join = new WindowJoin(
                List.of(new ClassReads.Window("demo.ReaderTest", 10, 20)),
                Set.of(),
                tmp,
                layout,
                new PathVerdicts(tmp, layout),
                classes,
                List.of(pooled),
                ClassReads.SuiteCode.empty(),
                new int[] {100},
                new ClassReads.UseNames(),
                new ClassReads.Handed(Set.of(jar, other), Set.of()));

        // The thread read data before any window, yet its in-window reads are listed first.
        join.read(15, jar.toString(), "t");
        join.read(25, other.toString(), "t");
        join.read(5, data.toString(), "t");
        join.finish();

        ClassReads.Observation reader = Objects.requireNonNull(classes.get("demo.ReaderTest"));
        assertThat(reader.code())
                .as("the jar read in its window, not the one read after it")
                .containsExactly(jar);
        assertThat(pooled.code()).as("read after every window").containsExactly(other);
    }

    @Test
    void a_socket_read_after_the_window_goes_to_the_class_that_held_the_threads_first_event(@TempDir Path tmp) {
        ClassReads.Layout layout = new ClassReads.Layout(List.of(), List.of(), Set.of(), tmp, null);
        Map<String, ClassReads.Observation> classes = new HashMap<>();
        ClassReads.Observation pooled = ClassReads.Observation.pool();
        WindowJoin join = new WindowJoin(
                List.of(new ClassReads.Window("demo.ReaderTest", 10, 20)),
                Set.of(),
                tmp,
                layout,
                new PathVerdicts(tmp, layout),
                classes,
                List.of(pooled),
                ClassReads.SuiteCode.empty(),
                new int[] {100},
                new ClassReads.UseNames(),
                ClassReads.Handed.NONE);

        // The server closing a connection is read after the window, yet listed before the write.
        join.host(25, "idle.example.com", "t", true);
        join.host(15, "idle.example.com", "t", false);
        join.finish();

        assertThat(Objects.requireNonNull(classes.get("demo.ReaderTest")).hosts())
                .containsExactly("idle.example.com");
        assertThat(pooled.hosts())
                .as("the late read is the class's, not the suite's")
                .isEmpty();
    }
}
