// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import cc.jumpkick.host.GitCommand;
import cc.jumpkick.host.Os;
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

    /**
     * A launch runs its own test JVMs, so what no window held in one launch's JVMs is that launch's
     * pool, not another's: a run of the guard's suites whose integration JVMs reached Maven Central
     * outside every window ran every class of jk-engine's default suite too, after a body-only
     * engine edit its cones did not reach. A recording whose launch is not told leaves every launch
     * on every JVM's pool.
     */
    @Test
    void what_no_window_held_is_the_pool_of_its_own_launch(@TempDir Path tmp) throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path unit = write(ws, "unit.txt");
        Path integration = write(ws, "integration.txt");
        Path recordings = Files.createDirectories(tmp.resolve("rec"));
        record(recordings.resolve("unit.jfr"), "demo.UnitTest", unit);
        record(recordings.resolve("integration.jfr"), "demo.IntegrationTest", integration);
        ClassReads.Layout layout = new ClassReads.Layout(
                List.of(ws.resolve("m/target/test-classes")), List.of(ws.resolve("m/target")), Set.of(), ws, null);

        ClassReads.Attributed seen = ClassReads.attribute(
                recordings,
                ws,
                layout,
                classes -> classes.contains("demo.IntegrationTest") ? "test:integration" : "test:test");

        assertThat(seen.pooled("test:test").reads())
                .as("the default launch's JVM")
                .containsExactly(unit);
        assertThat(seen.pooled("test:integration").reads())
                .as("the integration launch's JVM")
                .containsExactly(integration);
        assertThat(seen.pooled().reads())
                .as("the suite's pool is every JVM's")
                .containsExactlyInAnyOrder(unit, integration);

        ClassReads.Attributed untold = ClassReads.attribute(
                recordings, ws, layout, classes -> classes.contains("demo.IntegrationTest") ? null : "test:test");
        assertThat(untold.launches()).as("one recording's launch unknown").isEmpty();
        assertThat(untold.pooled("test:test").reads())
                .as("so every launch stands on every JVM's pool")
                .containsExactlyInAnyOrder(unit, integration);
    }

    /**
     * A class with an integration method among its unit tests runs in the default launch's JVMs and
     * the integration launch's. What its integration window read (an engine it started) is that
     * launch's row alone: its default row stands on what its unit tests read there.
     */
    @Test
    void a_class_two_launches_run_keeps_each_launchs_window_apart(@TempDir Path tmp) throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path unitRead = write(ws, "unit.txt");
        Path engineRead = write(ws, "engine.txt");
        Path recordings = Files.createDirectories(tmp.resolve("rec"));
        recordWindows(recordings.resolve("unit.jfr"), "demo.UnitTest", write(ws, "u.txt"), "demo.MixedTest", unitRead);
        recordWindows(
                recordings.resolve("integration.jfr"),
                "demo.MixedTest",
                engineRead,
                "demo.IntegrationTest",
                write(ws, "i.txt"));
        ClassReads.Layout layout = new ClassReads.Layout(
                List.of(ws.resolve("m/target/test-classes")), List.of(ws.resolve("m/target")), Set.of(), ws, null);

        ClassReads.Attributed seen = ClassReads.attribute(
                recordings,
                ws,
                layout,
                classes -> classes.contains("demo.IntegrationTest") ? "test:integration" : "test:test");

        assertThat(Objects.requireNonNull(seen.of("test:test", "demo.MixedTest"))
                        .reads())
                .as("the default launch's window")
                .containsExactly(unitRead);
        assertThat(Objects.requireNonNull(seen.of("test:integration", "demo.MixedTest"))
                        .reads())
                .as("the integration launch's window")
                .containsExactly(engineRead);

        ClassReads.Attributed untold = ClassReads.attribute(
                recordings, ws, layout, classes -> classes.contains("demo.IntegrationTest") ? null : "test:test");
        assertThat(Objects.requireNonNull(untold.of("test:test", "demo.MixedTest"))
                        .reads())
                .as("one recording's launch unknown: every window of the class is its row's")
                .containsExactlyInAnyOrder(unitRead, engineRead);
    }

    /** A recording of one JVM at {@code file}: {@code firstRead} in the window of {@code first}, then the same of the second. */
    private static void recordWindows(Path file, String first, Path firstRead, String second, Path secondRead)
            throws Exception {
        try (Recording r = new Recording()) {
            r.enable("jdk.FileRead").withThreshold(Duration.ZERO);
            r.enable(ClassReads.WINDOW_EVENT).withThreshold(Duration.ZERO);
            r.start();
            Window w = window(first);
            Files.readString(firstRead);
            w.commit();
            w = window(second);
            Files.readString(secondRead);
            w.commit();
            r.stop();
            r.dump(file);
        }
    }

    /** A recording of one JVM at {@code file}: {@code pooled} read before the window of {@code testClass}. */
    private static void record(Path file, String testClass, Path pooled) throws Exception {
        try (Recording r = new Recording()) {
            r.enable("jdk.FileRead").withThreshold(Duration.ZERO);
            r.enable(ClassReads.WINDOW_EVENT).withThreshold(Duration.ZERO);
            r.start();
            Files.readString(pooled);
            window(testClass).commit();
            r.stop();
            r.dump(file);
        }
    }

    /** The probe's use event, as the recording names it. */
    @Name(ClassReads.USE_EVENT)
    @StackTrace(false)
    static final class Use extends Event {
        @Nullable
        String className;
    }

    @Test
    void a_use_is_the_classes_whose_window_held_it_and_one_outside_every_window_is_every_classs(@TempDir Path tmp)
            throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path recordings = Files.createDirectories(tmp.resolve("rec"));

        try (Recording r = new Recording()) {
            r.enable(ClassReads.WINDOW_EVENT).withThreshold(Duration.ZERO);
            r.enable(ClassReads.USE_EVENT);
            r.start();
            use("demo.Registry");
            Window first = window("demo.ATest");
            use("demo.ATest");
            use("demo.Engine");
            first.commit();
            Window second = window("demo.BTest");
            use("demo.BTest");
            second.commit();
            r.stop();
            r.dump(recordings.resolve("one.jfr"));
        }

        ClassReads.Attributed seen =
                ClassReads.attribute(recordings, ws, new ClassReads.Layout(List.of(), List.of(), Set.of(), ws, null));
        ClassReads.Observation first = Objects.requireNonNull(seen.classes().get("demo.ATest"));
        ClassReads.Observation second = Objects.requireNonNull(seen.classes().get("demo.BTest"));
        assertThat(first.used("demo.Engine")).as("its code ran in the window").isTrue();
        assertThat(second.used("demo.Engine"))
                .as("loaded already, but not run in this window")
                .isFalse();
        assertThat(second.used("demo.BTest")).isTrue();
        assertThat(second.used("demo.Registry"))
                .as("what ran before any window left state every class may read")
                .isTrue();
        assertThat(first.usedMore()).isFalse();
    }

    private static void use(String className) {
        Use u = new Use();
        u.className = className;
        u.commit();
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
    void the_suite_names_every_code_file_its_jvms_read_past_a_classs_own_bound(@TempDir Path tmp) throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path repo = Files.createDirectories(tmp.resolve("repo"));
        List<Path> jars = new ArrayList<>();
        for (int i = 0; i < ClassReads.CODE_KEPT + 3; i++) {
            jars.add(Files.write(repo.resolve("lib-" + i + ".jar"), new byte[] {(byte) i}));
        }
        Path recordings = Files.createDirectories(tmp.resolve("rec"));

        try (Recording r = new Recording()) {
            r.enable("jdk.FileRead").withThreshold(Duration.ZERO);
            r.enable(ClassReads.WINDOW_EVENT).withThreshold(Duration.ZERO);
            r.start();
            Window compiler = window("demo.CompileTest");
            for (Path jar : jars) Files.readAllBytes(jar);
            compiler.commit();
            window("demo.LaterTest").commit();
            r.stop();
            r.dump(recordings.resolve("one.jfr"));
        }

        ClassReads.Attributed seen =
                ClassReads.attribute(recordings, ws, new ClassReads.Layout(List.of(), List.of(), Set.of(), ws, null));

        ClassReads.Observation later = Objects.requireNonNull(seen.classes().get("demo.LaterTest"));
        assertThat(later.codeMore())
                .as("a later class of the JVM is charged every jar its loaders may serve it from")
                .isTrue();
        assertThat(seen.code().files())
                .as("the suite's list holds each of them, once")
                .containsExactlyInAnyOrderElementsOf(jars);
        assertThat(seen.code().more()).isFalse();
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

    /**
     * git started in a temp root reads a repository the test made: its class stands on git's
     * identity, not its module. The same command in the workspace reads the workspace's repository.
     */
    @Test
    void git_in_a_temp_root_is_keyed_by_its_identity_not_as_a_program_started(@TempDir Path tmp) throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path temp = Files.createDirectories(tmp.resolve("temp"));
        Path repo = Files.createDirectories(temp.resolve("repo"));
        Path recordings = Files.createDirectories(tmp.resolve("rec"));

        try (Recording r = new Recording()) {
            r.enable("jdk.ProcessStart");
            r.enable(ClassReads.WINDOW_EVENT).withThreshold(Duration.ZERO);
            r.start();
            Window own = window("demo.OwnRepoTest");
            runIn(repo, GitCommand.of("rev-parse", "--show-toplevel"));
            own.commit();
            Window checkout = window("demo.CheckoutTest");
            runIn(ws, GitCommand.of("rev-parse", "--show-toplevel"));
            checkout.commit();
            r.stop();
            r.dump(recordings.resolve("one.jfr"));
        }

        ClassReads.Attributed seen = ClassReads.attribute(
                recordings,
                ws,
                new ClassReads.Layout(List.of(), List.of(), Set.of(), ws, null, List.of(), List.of(temp)));

        ClassReads.Observation ownRepo = Objects.requireNonNull(seen.classes().get("demo.OwnRepoTest"));
        ClassReads.Observation inCheckout =
                Objects.requireNonNull(seen.classes().get("demo.CheckoutTest"));
        assertThat(inCheckout.processes()).as("git in the workspace").containsExactly("git");
        assertThat(inCheckout.tools()).containsExactly(ClassReads.STARTED_PROGRAM);
        // A host whose home is a repository encloses the temp root: git there is a program started.
        if (GitRuns.unenclosed(List.of(temp)).isEmpty()) return;
        assertThat(ownRepo.processes()).as("git in a repository the test made").isEmpty();
        assertThat(ownRepo.tools()).containsExactly(GitRuns.GIT);
    }

    private static void run(String... command) throws Exception {
        new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor();
    }

    /**
     * A utility whose command names no file of the workspace stands on its identity; one that reads a
     * workspace file is a program started.
     */
    @Test
    void a_utility_naming_no_workspace_file_is_keyed_by_its_identity(@TempDir Path tmp) throws Exception {
        assumeFalse(Os.isWindows());
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("README"), "x");
        Path temp = Files.createDirectories(tmp.resolve("temp"));
        Path recordings = Files.createDirectories(tmp.resolve("rec"));

        try (Recording r = new Recording()) {
            r.enable("jdk.ProcessStart");
            r.enable(ClassReads.WINDOW_EVENT).withThreshold(Duration.ZERO);
            r.start();
            Window sleeps = window("demo.SleepTest");
            runIn(ws, List.of("sleep", "0"));
            sleeps.commit();
            Window reads = window("demo.CatTest");
            runIn(ws, List.of("cat", "README"));
            reads.commit();
            r.stop();
            r.dump(recordings.resolve("one.jfr"));
        }

        ClassReads.Attributed seen = ClassReads.attribute(
                recordings,
                ws,
                new ClassReads.Layout(List.of(), List.of(), Set.of(), ws, null, List.of(), List.of(temp)));
        ClassReads.Observation sleep = Objects.requireNonNull(seen.classes().get("demo.SleepTest"));
        ClassReads.Observation cat = Objects.requireNonNull(seen.classes().get("demo.CatTest"));
        assertThat(sleep.processes()).isEmpty();
        assertThat(sleep.tools()).containsExactlyInAnyOrder("sleep", ClassReads.STARTED_PROGRAM);
        assertThat(cat.processes()).as("a file of the workspace").containsExactly("cat");
        assertThat(cat.tools()).containsExactly(ClassReads.STARTED_PROGRAM);
    }

    /**
     * The test JDK's own launcher is no program the class depends on, but what it was told to run is
     * read in a process no recording holds: the class that started it, and every class after it in the
     * JVM, which may talk to what it left running, say a program was started. A class before it does
     * not, and git in a repository a test made says nothing.
     */
    @Test
    void a_program_started_marks_its_class_and_every_later_class_of_the_jvm(@TempDir Path tmp) throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path temp = Files.createDirectories(tmp.resolve("temp"));
        Path repo = Files.createDirectories(temp.resolve("repo"));
        Path recordings = Files.createDirectories(tmp.resolve("rec"));
        Path javaHome = Path.of(System.getProperty("java.home"));
        String java = javaHome.resolve("bin")
                .resolve(Os.isWindows() ? "java.exe" : "java")
                .toString();

        try (Recording r = new Recording()) {
            r.enable("jdk.ProcessStart");
            r.enable(ClassReads.WINDOW_EVENT).withThreshold(Duration.ZERO);
            r.start();
            Window before = window("demo.BeforeTest");
            runIn(repo, GitCommand.of("rev-parse", "--show-toplevel"));
            before.commit();
            Window starts = window("demo.StartsTest");
            runIn(ws, List.of(java, "-version"));
            starts.commit();
            Window after = window("demo.AfterTest");
            after.commit();
            r.stop();
            r.dump(recordings.resolve("one.jfr"));
        }

        ClassReads.Attributed seen = ClassReads.attribute(
                recordings,
                ws,
                new ClassReads.Layout(List.of(), List.of(), Set.of(), ws, javaHome, List.of(), List.of(temp)));
        ClassReads.Observation first = Objects.requireNonNull(seen.classes().get("demo.BeforeTest"));
        ClassReads.Observation starter = Objects.requireNonNull(seen.classes().get("demo.StartsTest"));
        ClassReads.Observation later = Objects.requireNonNull(seen.classes().get("demo.AfterTest"));
        assertThat(starter.processes()).as("the JDK the key holds").isEmpty();
        assertThat(starter.tools()).containsExactly(ClassReads.STARTED_PROGRAM);
        assertThat(later.tools()).as("a class after it in the JVM").containsExactly(ClassReads.STARTED_PROGRAM);
        assertThat(first.tools()).doesNotContain(ClassReads.STARTED_PROGRAM);
    }

    private static void runIn(Path dir, List<String> command) throws Exception {
        new ProcessBuilder(command)
                .directory(dir.toFile())
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

    /**
     * A class that reads past its bound keeps directories that cover every file it read: the
     * workspace's own manifest and thirty plugin manifests are the root's files and everything under
     * plugins, which holds no engine source.
     */
    @Test
    void read_roots_cover_every_file_a_class_read_and_no_more(@TempDir Path ws) {
        ClassReads.Observation o = ClassReads.Observation.empty();
        o.addRead(ws.resolve("jk.toml"));
        for (int i = 0; i < 30; i++) o.addRead(ws.resolve("plugins/p" + i + "/jk-plugin.toml"));
        assertThat(o.readsMore()).isTrue();
        assertThat(o.readRoots())
                .containsExactlyInAnyOrder(
                        new ClassReads.ReadRoot(ws, false), new ClassReads.ReadRoot(ws.resolve("plugins"), true));
        assertThat(o.readRoots().stream().anyMatch(r -> r.covers(ws.resolve("server/engine/src/Self.java"))))
                .as("an engine source lies under no root")
                .isFalse();
        assertThat(o.readRoots().stream().anyMatch(r -> r.covers(ws.resolve("plugins/p3/README.md"))))
                .isTrue();
    }

    /** Reads with no directory in common past the bound leave no roots: the class keys on its whole suite. */
    @Test
    void reads_that_share_no_directory_leave_no_roots() {
        ClassReads.Observation o = ClassReads.Observation.empty();
        for (int i = 0; i < 12; i++) o.addRead(Path.of("d" + i, "f.txt"));
        assertThat(o.readsMore()).isTrue();
        assertThat(o.rootsMore()).isTrue();
        assertThat(o.readRoots()).isEmpty();
    }

    /** A class past its read bound keeps the names of the files it read, up to their own bound. */
    @Test
    void a_class_keeps_the_file_names_it_read_up_to_their_bound(@TempDir Path ws) {
        ClassReads.Observation o = ClassReads.Observation.empty();
        o.addRead(ws.resolve("jk.toml"));
        for (int i = 0; i < 30; i++) o.addRead(ws.resolve("m" + i + "/jk.toml"));
        assertThat(o.readsMore()).isTrue();
        assertThat(o.readNames()).containsExactly("jk.toml");
        assertThat(o.wantsRead(ws.resolve("m3/jk.toml")))
                .as("a name and a root it holds")
                .isFalse();
        assertThat(o.wantsRead(ws.resolve("m3/jk-lock.toml")))
                .as("a name it has not seen")
                .isTrue();
        for (int i = 0; i < ClassReads.READ_NAMES_KEPT; i++) o.addRead(ws.resolve("m1/f" + i + ".txt"));
        assertThat(o.readNames()).as("past the bound the names say nothing").isEmpty();
    }
}
