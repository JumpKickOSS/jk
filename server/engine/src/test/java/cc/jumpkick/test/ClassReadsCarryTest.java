// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.GitCommand;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.Processes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.StackTrace;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a class before it in its JVM did reaches a later class only through what the later class
 * touched: a program started earlier, when its window talked to a local peer; a worker jar, when its
 * window or a thread it started read it.
 *
 * <p>A recording holds every thread of the JVM, the test runner's channel and other classes' tests
 * included, so each test keeps only its own threads' events before it attributes them.
 */
class ClassReadsCarryTest {

    /** The test runner's window event, as the recording names it. */
    @Name(ClassReads.WINDOW_EVENT)
    @StackTrace(false)
    static final class Window extends Event {
        @Nullable
        String testClass;

        @Nullable
        String uniqueId;
    }

    /** A socket write, as the JDK's event names it. */
    @Name("jdk.SocketWrite")
    @StackTrace(false)
    static final class SocketWrite extends Event {
        @Nullable
        String host;

        @Nullable
        String address;

        int port;
    }

    /** A read of a pipe, which the JDK records with no path. */
    @Name("jdk.FileRead")
    @StackTrace(false)
    static final class PipeRead extends Event {
        @Nullable
        String path;
    }

    /**
     * A program outlives the window that started it, so a later class of the JVM that talked to a
     * local peer may have been served by it: a loopback or Unix domain socket other than the run's
     * own, or a pipe. A later class that talked to none is not marked.
     */
    @Test
    void a_program_started_marks_its_class_and_each_later_class_that_reached_a_local_peer(@TempDir Path tmp)
            throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path temp = Files.createDirectories(tmp.resolve("temp"));
        Path repo = Files.createDirectories(temp.resolve("repo"));
        Path recordings = Files.createDirectories(tmp.resolve("rec"));
        Path javaHome = Path.of(System.getProperty("java.home"));
        String java = javaHome.resolve("bin")
                .resolve(Os.isWindows() ? "java.exe" : "java")
                .toString();

        ExecutorService other = Executors.newSingleThreadExecutor();
        try (Recording r = new Recording()) {
            r.enable("jdk.ProcessStart");
            r.enable("jdk.FileRead").withThreshold(Duration.ZERO);
            r.enable("jdk.SocketRead").withThreshold(Duration.ZERO);
            r.enable("jdk.SocketWrite").withThreshold(Duration.ZERO);
            r.enable(ClassReads.WINDOW_EVENT).withThreshold(Duration.ZERO);
            r.start();
            Window before = window("demo.BeforeTest");
            runIn(repo, GitCommand.of("rev-parse", "--show-toplevel"));
            before.commit();
            Window starts = window("demo.StartsTest");
            ProcessBuilder version = new ProcessBuilder(java, "-version").redirectErrorStream(true);
            Process served = version.start();
            starts.commit();
            Window quiet = window("demo.QuietTest");
            // Another thread of the JVM talks to a local peer while the quiet class runs.
            other.submit(() -> loopback("127.0.0.1", OWN_PORT + 2)).get();
            quiet.commit();
            Window own = window("demo.OwnPortTest");
            loopback("127.0.0.1", OWN_PORT);
            own.commit();
            Window talks = window("demo.TalksTest");
            loopback("127.0.0.1", OWN_PORT + 1);
            talks.commit();
            Window domain = window("demo.DomainSocketTest");
            loopback("[" + tmp.resolve("engine.sock") + "]", 0);
            domain.commit();
            Window pipe = window("demo.PipeTest");
            new PipeRead().commit();
            pipe.commit();
            r.stop();
            Processes.capture(version, served, Duration.ofMinutes(1));
            dumpOwn(r, tmp, recordings, Set.of(Thread.currentThread().threadId()));
        } finally {
            other.shutdownNow();
        }

        ClassReads.Attributed seen = ClassReads.attribute(
                recordings,
                ws,
                new ClassReads.Layout(List.of(), List.of(), Set.of(), ws, javaHome, List.of(), List.of(temp)),
                classes -> null,
                new ClassReads.Handed(Set.of(), Set.of(OWN_PORT)));
        assertThat(tools(seen, "demo.StartsTest")).containsExactly(ClassReads.STARTED_PROGRAM);
        assertThat(tools(seen, "demo.BeforeTest")).doesNotContain(ClassReads.STARTED_PROGRAM);
        assertThat(tools(seen, "demo.QuietTest"))
                .as("a later class that talked to nothing")
                .doesNotContain(ClassReads.STARTED_PROGRAM);
        assertThat(tools(seen, "demo.OwnPortTest"))
                .as("a later class that talked only on the run's own channel")
                .doesNotContain(ClassReads.STARTED_PROGRAM);
        for (String later : List.of("demo.TalksTest", "demo.DomainSocketTest", "demo.PipeTest")) {
            assertThat(tools(seen, later)).as(later).contains(ClassReads.STARTED_PROGRAM);
        }
        assertThat(Objects.requireNonNull(seen.classes().get("demo.StartsTest")).processes())
                .as("the JDK the key holds")
                .isEmpty();
    }

    private static final int OWN_PORT = 47_011;

    private static Set<String> tools(ClassReads.Attributed seen, String testClass) {
        return Objects.requireNonNull(seen.classes().get(testClass)).tools();
    }

    /** One socket event to a peer on this host at {@code address}, as the JDK records it. */
    private static void loopback(String address, int port) {
        SocketWrite w = new SocketWrite();
        w.host = address.startsWith("[") ? "Unix domain socket" : "localhost";
        w.address = address;
        w.port = port;
        w.commit();
    }

    /**
     * A worker jar the suite was handed is read by the tests to hash, copy or hand to a program, and
     * no loader of the JVM keeps it for a later class: it keys the class whose window read it, a read
     * outside every window on a thread an earlier window owns keys that class, and only a read on a
     * thread no window owns is carried to the classes after it. Any other jar is still carried.
     */
    @Test
    void a_worker_jar_keys_the_classes_that_read_it_and_no_later_one(@TempDir Path tmp) throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path target = Files.createDirectories(ws.resolve("engine/target"));
        List<Path> workers = new ArrayList<>();
        for (int i = 0; i <= ClassReads.CODE_KEPT; i++) {
            workers.add(Files.write(target.resolve("worker-" + i + ".jar"), new byte[] {(byte) i}));
        }
        Path lingering = Files.write(target.resolve("lingering.jar"), new byte[] {7});
        Path between = Files.write(target.resolve("between.jar"), new byte[] {8});
        Path last = Files.write(target.resolve("last.jar"), new byte[] {9});
        Path library = Files.write(Files.createDirectories(ws.resolve("libs")).resolve("lib.jar"), new byte[] {6});
        Path data = Files.writeString(ws.resolve("data.txt"), "x");
        Path recordings = Files.createDirectories(tmp.resolve("rec"));
        ExecutorService background = Executors.newSingleThreadExecutor();

        try (Recording r = new Recording()) {
            r.enable("jdk.FileRead").withThreshold(Duration.ZERO);
            r.enable(ClassReads.WINDOW_EVENT).withThreshold(Duration.ZERO);
            r.start();
            // This thread's first read is outside every window: no class owns it.
            Files.readAllBytes(data);
            Window reader = window("demo.ReaderTest");
            for (Path w : workers) Files.readAllBytes(w);
            Files.readAllBytes(library);
            background.submit(() -> Files.readAllBytes(data)).get();
            reader.commit();
            background.submit(() -> Files.readAllBytes(lingering)).get();
            Files.readAllBytes(between);
            Window later = window("demo.LaterTest");
            later.commit();
            Files.readAllBytes(last);
            r.stop();
            long worker =
                    background.submit(() -> Thread.currentThread().threadId()).get();
            dumpOwn(r, tmp, recordings, Set.of(Thread.currentThread().threadId(), worker));
        } finally {
            background.shutdownNow();
        }

        Set<Path> handed = new HashSet<>(workers);
        handed.addAll(List.of(lingering, between, last));
        ClassReads.Attributed seen = ClassReads.attribute(
                recordings,
                ws,
                new ClassReads.Layout(List.of(), List.of(), Set.of(), ws, null),
                classes -> null,
                new ClassReads.Handed(handed, Set.of()));
        ClassReads.Observation read = Objects.requireNonNull(seen.classes().get("demo.ReaderTest"));
        ClassReads.Observation after = Objects.requireNonNull(seen.classes().get("demo.LaterTest"));
        List<Path> own = new ArrayList<>(workers);
        own.addAll(List.of(library, lingering));
        assertThat(read.code())
                .as("every worker jar it read, past its own code bound, and one its thread read after it")
                .containsExactlyInAnyOrderElementsOf(own);
        assertThat(read.codeMore()).isFalse();
        assertThat(after.code())
                .as("a later class: the library a loader may serve it from, and what no window's thread read")
                .containsExactlyInAnyOrder(library, between);
        assertThat(seen.pooled().code()).as("read after every window").containsExactly(last);
        assertThat(seen.code().files())
                .as("worker jars are no file a class past its bound stands on")
                .containsExactly(library);
    }

    /** Dump {@code r} into {@code recordings}, keeping only the events of {@code threads}. */
    private static void dumpOwn(Recording r, Path tmp, Path recordings, Set<Long> threads) throws Exception {
        Path whole = tmp.resolve("whole.jfr");
        r.dump(whole);
        Predicate<RecordedEvent> own = e -> {
            RecordedThread t = e.getThread();
            return t != null && threads.contains(t.getJavaThreadId());
        };
        try (RecordingFile file = new RecordingFile(whole)) {
            file.write(recordings.resolve("one.jfr"), own);
        }
    }

    private static Window window(String testClass) {
        Window w = new Window();
        w.testClass = testClass;
        w.uniqueId = "[engine:junit-jupiter]/[class:" + testClass + "]";
        w.begin();
        return w;
    }

    private static void runIn(Path dir, List<String> command) throws Exception {
        new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor();
    }
}
