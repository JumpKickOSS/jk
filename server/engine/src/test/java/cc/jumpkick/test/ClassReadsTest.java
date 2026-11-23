// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.GitCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.StackTrace;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A recording's reads are given to the test class whose window held them: any thread's read in a
 * window, never a read after the window closed; what no window held is pooled for every class; a
 * class file under a class tree is a class loaded; a code file read before a class finished is that
 * class's; what the JVM wrote it does not read as an input; the system's own files are evidence.
 */
class ClassReadsTest {

    /** The test runner's window event, as the recording names it. */
    @Name(ClassReads.WINDOW_EVENT)
    @StackTrace(false)
    static final class Window extends Event {
        @Nullable
        String testClass;

        @Nullable
        String uniqueId;
    }

    @Test
    void each_read_goes_to_the_class_whose_window_held_it(@TempDir Path tmp) throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path tree = Files.createDirectories(ws.resolve("m/target/test-classes/demo"));
        Path loaded = Files.write(tree.resolve("Helper.class"), new byte[] {1, 2, 3});
        Path tool = Files.write(Files.createDirectories(ws.resolve("tools")).resolve("tool.jar"), new byte[] {4});
        Path pooledFile = write(ws, "pooled.txt");
        Path a = write(ws, "a.txt");
        Path background = write(ws, "bg.txt");
        Path b = write(ws, "b.txt");
        Path late = write(ws, "late.txt");
        Path own = ws.resolve("own.txt");
        Path recordings = Files.createDirectories(tmp.resolve("rec"));

        try (Recording r = new Recording()) {
            r.enable("jdk.FileRead").withThreshold(Duration.ZERO);
            r.enable("jdk.FileWrite").withThreshold(Duration.ZERO);
            r.enable(ClassReads.WINDOW_EVENT).withThreshold(Duration.ZERO);
            r.start();
            Files.readString(pooledFile);

            Window first = window("demo.ATest");
            Files.readString(a);
            Files.readAllBytes(loaded);
            Files.readAllBytes(tool);
            Thread t = new Thread(() -> {
                try {
                    Files.readString(background);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            t.start();
            t.join();
            first.commit();

            Window second = window("demo.BTest");
            Files.readString(b);
            Files.writeString(own, "made here");
            Files.readString(own);
            second.commit();

            Files.readString(late);
            r.stop();
            r.dump(recordings.resolve("one.jfr"));
        }

        ClassReads.Attributed seen = ClassReads.attribute(
                recordings,
                ws,
                new ClassReads.Layout(
                        List.of(ws.resolve("m/target/test-classes")),
                        List.of(ws.resolve("m/target")),
                        Set.of(),
                        ws,
                        null));

        assertThat(seen.complete()).isTrue();
        ClassReads.Observation first = Objects.requireNonNull(seen.classes().get("demo.ATest"));
        ClassReads.Observation second = Objects.requireNonNull(seen.classes().get("demo.BTest"));
        assertThat(first.reads())
                .as("its own read and its background thread's")
                .containsExactlyInAnyOrder(a, background);
        assertThat(first.loaded())
                .as("a class file under a class tree loads a class")
                .containsExactly("demo.Helper");
        assertThat(first.code()).as("a code file read in its window").containsExactly(tool);
        assertThat(second.reads()).as("what it wrote is its own output").containsExactly(b);
        assertThat(second.code())
                .as("a code file read before it finished: its JVM's loaders may serve it from that")
                .containsExactly(tool);
        assertThat(seen.pooled().reads())
                .as("before the first window and after the last: every class's")
                .containsExactlyInAnyOrder(pooledFile, late);
        assertThat(first.reads()).as("never a read after its window closed").doesNotContain(late, b);
    }

    @Test
    void the_pool_names_more_files_than_a_class_keeps(@TempDir Path tmp) throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path recordings = Files.createDirectories(tmp.resolve("rec"));
        List<Path> templates = new ArrayList<>();
        for (int i = 0; i < ClassReads.READS_KEPT + 2; i++) templates.add(write(ws, "template-" + i + ".txt"));

        try (Recording r = new Recording()) {
            r.enable("jdk.FileRead").withThreshold(Duration.ZERO);
            r.enable(ClassReads.WINDOW_EVENT).withThreshold(Duration.ZERO);
            r.start();
            for (Path t : templates) Files.readString(t);
            window("demo.ATest").commit();
            r.stop();
            r.dump(recordings.resolve("one.jfr"));
        }

        ClassReads.Attributed seen =
                ClassReads.attribute(recordings, ws, new ClassReads.Layout(List.of(), List.of(), Set.of(), ws, null));

        assertThat(seen.pooled().readsMore())
                .as("what discovery read keys every class by content, not as all the suite read")
                .isFalse();
        assertThat(seen.pooled().reads()).containsExactlyInAnyOrderElementsOf(templates);
    }

    @Test
    void a_code_file_gone_once_the_run_ended_is_no_class_input(@TempDir Path tmp) throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path kept = Files.write(Files.createDirectories(ws.resolve("tools")).resolve("tool.jar"), new byte[] {4});
        Path made = Files.write(Files.createDirectories(tmp.resolve("made")).resolve("made.jar"), new byte[] {5});
        Path recordings = Files.createDirectories(tmp.resolve("rec"));

        try (Recording r = new Recording()) {
            r.enable("jdk.FileRead").withThreshold(Duration.ZERO);
            r.enable(ClassReads.WINDOW_EVENT).withThreshold(Duration.ZERO);
            r.start();
            Window first = window("demo.MakerTest");
            Files.readAllBytes(made);
            Files.readAllBytes(kept);
            first.commit();
            Window second = window("demo.LaterTest");
            second.commit();
            r.stop();
            r.dump(recordings.resolve("one.jfr"));
        }
        Files.delete(made);

        ClassReads.Attributed seen =
                ClassReads.attribute(recordings, ws, new ClassReads.Layout(List.of(), List.of(), Set.of(), ws, null));

        assertThat(Objects.requireNonNull(seen.classes().get("demo.MakerTest")).code())
                .as("the jar the run made and removed is none of its inputs")
                .containsExactly(kept);
        assertThat(Objects.requireNonNull(seen.classes().get("demo.LaterTest")).code())
                .as("nor one a later class's loader could be served from")
                .containsExactly(kept);
    }

    @Test
    void a_command_the_test_harness_marks_is_no_program_the_class_started(@TempDir Path tmp) throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path recordings = Files.createDirectories(tmp.resolve("rec"));
        String java = ProcessHandle.current().info().command().orElseThrow();

        try (Recording r = new Recording()) {
            r.enable("jdk.ProcessStart");
            r.enable(ClassReads.WINDOW_EVENT).withThreshold(Duration.ZERO);
            r.start();
            Window harnessed = window("demo.QuietTest");
            run(java, "-D" + GitCommand.HARNESS, "-version");
            harnessed.commit();
            Window starting = window("demo.StartingTest");
            run(java, "-version");
            starting.commit();
            r.stop();
            r.dump(recordings.resolve("one.jfr"));
        }

        ClassReads.Attributed seen =
                ClassReads.attribute(recordings, ws, new ClassReads.Layout(List.of(), List.of(), Set.of(), ws, null));

        assertThat(Objects.requireNonNull(seen.classes().get("demo.QuietTest")).processes())
                .as("the harness's own command around the class")
                .isEmpty();
        assertThat(Objects.requireNonNull(seen.classes().get("demo.StartingTest"))
                        .processes())
                .containsExactly(java);
    }

    private static void run(String... command) throws Exception {
        new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor();
    }

    @Test
    void the_systems_own_files_are_evidence_and_never_reads() {
        ClassReads.Layout layout = new ClassReads.Layout(List.of(), List.of(), Set.of(), Path.of("/ws"), null);
        assertThat(ClassReads.classify(Path.of("/proc/self/status"), layout)).isEqualTo(ClassReads.Where.OS);
        assertThat(ClassReads.classify(Path.of("/etc/hostname"), layout)).isEqualTo(ClassReads.Where.OS);
        assertThat(ClassReads.classify(Path.of("/System/Library/x"), layout)).isEqualTo(ClassReads.Where.OS);
        assertThat(ClassReads.classify(Path.of("/ws/data/x.json"), layout)).isEqualTo(ClassReads.Where.DATA);
        assertThat(ClassReads.classify(Path.of("/ws/lib/x.jar"), layout)).isEqualTo(ClassReads.Where.CODE);
        assertThat(ClassReads.classify(Path.of("/home/u/.jk/cache/env-reads/env-probe-2-ab.jar"), layout))
                .as("jk's own probe agent")
                .isEqualTo(ClassReads.Where.KEYED);
    }

    @Test
    void the_files_every_jvm_reads_of_the_system_tie_no_result_to_it() {
        ClassReads.Observation seen = ClassReads.Observation.empty();
        seen.addOsPath(Path.of("/dev/urandom"));
        seen.addOsPath(Path.of("/proc/self/status"));
        assertThat(seen.platformBound()).isFalse();
        seen.addOsPath(Path.of("/etc/localtime"));
        assertThat(seen.platformBound()).isTrue();
    }

    @Test
    void a_library_on_the_classpath_is_keyed_already() {
        Path lib = Path.of("/store/lib-1.0.jar");
        ClassReads.Layout layout = new ClassReads.Layout(List.of(), List.of(), Set.of(lib), Path.of("/ws"), null);
        assertThat(ClassReads.classify(lib, layout)).isEqualTo(ClassReads.Where.KEYED);
    }

    private static Window window(String testClass) {
        Window w = new Window();
        w.testClass = testClass;
        w.uniqueId = "[engine:junit-jupiter]/[class:" + testClass + "]";
        w.begin();
        return w;
    }

    private static Path write(Path dir, String name) throws Exception {
        return Files.writeString(dir.resolve(name), name);
    }
}
