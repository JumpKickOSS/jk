// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static java.lang.constant.ConstantDescs.CD_int;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.orchestrator.ClassCones;
import cc.jumpkick.task.SourceApiIndex;
import cc.jumpkick.test.ClassReads;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Before the compile an edit waits on, the forecast counts the tests whose classes replay by their
 * cones, as the run will decide it: a body-only edit replays a class that reaches the edited class
 * but never ran it, and runs one that ran it; an edit that moves a declaration runs every class
 * whose cone holds the edited class; an edit the forecast cannot read counts every test. A worker
 * jar the suite is handed by path that is rebuilding runs only the classes that read it, and a
 * class that read more code files than its record names runs only when one its suite lists moves.
 * The affected-test ranking reads the same records: how each class's cone and reads meet a change.
 */
class ConeForecastTest {

    private static final ClassDesc DEP = ClassDesc.of("demo.Dep");

    /** The launches the records of these tests stand for: the default suite's and a gate's. */
    private static final List<String> LAUNCHES = List.of("", "test:test");

    @Test
    void a_body_only_edit_replays_the_classes_that_never_ran_the_edited_class(@TempDir Path ws) throws Exception {
        BuildLayout layout = module(ws);
        Path moduleDir = layout.moduleRoot();
        write(layout.classesDir(), "demo/Dep", dep());
        write(layout.testClassesDir(), "demo/T", caller("demo.T"));
        write(layout.testClassesDir(), "demo/U", plain("demo.U"));
        ClassCones.Cone cone = ClassCones.index(List.of(layout.testClassesDir(), layout.classesDir()))
                .cone("demo.T");
        String notRun = cone.used(c -> c.equals("demo.T"));
        String ran = cone.used(c -> true);
        SourceApiIndex.Hint body = edit(SourceApiIndex.Kind.BODY_ONLY);

        records(moduleDir, notRun);
        assertThat(replayed(layout, body)).as("T reaches Dep but never ran it").isEqualTo(5);
        records(moduleDir, ran);
        assertThat(replayed(layout, body)).as("T ran Dep").isEqualTo(2);
        records(moduleDir, notRun);
        assertThat(replayed(layout, edit(SourceApiIndex.Kind.API_CHANGED)))
                .as("a declaration every caller sees")
                .isEqualTo(2);
        assertThat(replayed(layout, new SourceApiIndex.Hint(SourceApiIndex.Kind.BODY_ONLY, List.of())))
                .as("an edit whose sources are not known")
                .isNull();
    }

    @Test
    void a_rebuilding_worker_jar_runs_only_the_classes_that_read_it(@TempDir Path ws) throws Exception {
        BuildLayout layout = module(ws);
        Path moduleDir = layout.moduleRoot();
        write(layout.testClassesDir(), "demo/T", plain("demo.T"));
        write(layout.testClassesDir(), "demo/U", plain("demo.U"));
        Path engineJar = ws.resolve("engine/target/engine-all.jar");
        String spelled = "engine/target/engine-all.jar";

        writeRows(moduleDir, null, row("T", 3, null), row("U", 2, null));
        assertThat(replayedWith(layout, engineJar)).as("neither read the jar").isEqualTo(5);
        assertThat(replayedWith(layout)).as("no worker jar rebuilding").isEqualTo(5);

        writeRows(moduleDir, null, withCode(row("T", 3, null), spelled, false), row("U", 2, null));
        assertThat(replayedWith(layout, engineJar)).as("T read the jar").isEqualTo(2);
        assertThat(replayedWith(layout, ws.resolve("other/target/other.jar")))
                .as("T read a jar that is not rebuilding")
                .isEqualTo(5);

        writeRows(moduleDir, null, withCode(row("T", 3, null), "lib/a.jar", true), row("U", 2, null));
        assertThat(replayedWith(layout, engineJar))
                .as("T read more code files than its record names, and the header lists none")
                .isEqualTo(2);

        writeRows(
                moduleDir,
                null,
                Jsonl.append(
                        row("T", 3, null),
                        JsonFields.fields().string("fallback", "process java").body()),
                row("U", 2, null));
        assertThat(replayedWith(layout, engineJar))
                .as("T is keyed by its module")
                .isEqualTo(2);
        assertThat(replayedWith(layout))
                .as("a module-keyed class with nothing moving")
                .isEqualTo(5);

        writeRows(
                moduleDir,
                null,
                Jsonl.append(
                        row("T", 3, null),
                        JsonFields.fields()
                                .array("tools", List.of(ClassReads.STARTED_PROGRAM))
                                .body()),
                row("U", 2, null));
        assertThat(replayedWith(layout, engineJar))
                .as("a program was started in T's JVM")
                .isEqualTo(2);

        writeRows(moduleDir, spelled, row("T", 3, null), row("U", 2, null));
        assertThat(replayedWith(layout, engineJar)).as("the pool read the jar").isZero();

        Path file = TestClassRecords.file(moduleDir);
        String pooledProcess = JsonFields.object()
                .string("kind", "suite")
                .array(TestClassRecords.LAUNCHES, List.of(""))
                .token(
                        "pooled",
                        JsonFields.object()
                                .array("processes", List.of("/usr/bin/node"))
                                .finish())
                .finish();
        Files.writeString(file, pooledProcess + "\n" + row("T", 3, null) + "\n" + row("U", 2, null) + "\n");
        assertThat(replayedWith(layout, engineJar))
                .as("a program started outside every window keys every class by its module")
                .isZero();
        assertThat(replayedWith(layout)).as("with nothing moving").isEqualTo(5);
    }

    @Test
    void a_class_past_its_code_bound_runs_only_when_a_code_file_its_suite_lists_moves(@TempDir Path ws)
            throws Exception {
        BuildLayout layout = module(ws);
        Path moduleDir = layout.moduleRoot();
        write(layout.testClassesDir(), "demo/T", plain("demo.T"));
        write(layout.testClassesDir(), "demo/U", plain("demo.U"));
        Path engineJar = ws.resolve("engine/target/engine-all.jar");
        Path pluginJar = Files.createDirectories(ws.resolve("lib")).resolve("plugin.jar");
        Files.writeString(pluginJar, "plugin");
        String t = withCode(row("T", 3, null), "lib/plugin.jar", true);
        String held = 500 + " " + ClassKeys.content(pluginJar) + " lib/plugin.jar";

        writeHeader(moduleDir, List.of(held), t, row("U", 2, null));
        assertThat(replayedWith(layout, engineJar))
                .as("the engine jar is not among the code files the suite read")
                .isEqualTo(5);
        writeHeader(moduleDir, List.of(held, 500 + " old engine/target/engine-all.jar"), t, row("U", 2, null));
        assertThat(replayedWith(layout, engineJar))
                .as("the engine jar is among them and rebuilding")
                .isEqualTo(2);
        writeHeader(
                moduleDir,
                List.of(2_000 + " " + ClassKeys.content(pluginJar) + " lib/plugin.jar"),
                t,
                row("U", 2, null));
        assertThat(replayedWith(layout)).as("a listed file changed after T ran").isEqualTo(2);
        Files.writeString(pluginJar, "rebuilt");
        writeHeader(moduleDir, List.of(held), t, row("U", 2, null));
        assertThat(replayedWith(layout))
                .as("a listed file changed since the header")
                .isEqualTo(2);
    }

    @Test
    void a_class_whose_source_counts_more_tests_than_ran_replays_whole(@TempDir Path ws) throws Exception {
        TestClassRecording.Replayed replayed = new TestClassRecording.Replayed(Map.of("demo.T", 12L), Map.of());
        assertThat(replayed.rerun(Map.of("demo.T", 19)))
                .as("a source counts @Test in a text block it holds")
                .isZero();
        assertThat(replayed.rerun(Map.of("demo.T", 19, "demo.V", 4)))
                .as("V has no record")
                .isEqualTo(4);
    }

    @Test
    void only_the_launches_the_selection_starts_count_and_a_class_that_runs_is_priced_by_its_record(@TempDir Path ws)
            throws Exception {
        BuildLayout layout = module(ws);
        write(layout.classesDir(), "demo/Dep", dep());
        write(layout.testClassesDir(), "demo/T", caller("demo.T"));
        write(layout.testClassesDir(), "demo/U", plain("demo.U"));
        String gate = "test:test";
        writeRows(
                layout.moduleRoot(),
                null,
                row("", "T", 3, null),
                row("", "U", 2, null),
                row(gate, "T", 7, null),
                row(gate, "U", 40, null));

        TestClassRecording.Replayed replayed = Objects.requireNonNull(TestClassRecording.replayedAfter(
                layout.moduleRoot(),
                layout,
                layout.testClassesDir(),
                layout.classesDir(),
                List.of(),
                List.of(edit(SourceApiIndex.Kind.BODY_ONLY)),
                List.of(),
                Set.of(gate)));
        assertThat(replayed.classes()).as("U, in the gate's launch alone").isEqualTo(1);
        assertThat(replayed.tests()).isEqualTo(40);
        assertThat(replayed.ran())
                .as("T, which ran Dep, by its record in the gate's launch")
                .isEqualTo(7);
        assertThat(replayed.recorded()).containsExactlyInAnyOrder("demo.T", "demo.U");
        assertThat(replayed.rerun(Map.of("demo.T", 1, "demo.U", 1)))
                .as("the sources count fewer tests than the records: what runs is still priced")
                .isEqualTo(7);
        assertThat(replayed.rerun(Map.of("demo.T", 3, "demo.U", 2, "demo.V", 13)))
                .as("what no record covers is priced by the sources")
                .isEqualTo(20);
    }

    /**
     * A launch's rows outlive the selection that wrote them: S, recorded in the default suite's launch
     * before it was tagged out of the suite, keyed by its module, would run after any edit, but the
     * run never starts it, so its tests are not priced and it is not among the classes replayed.
     */
    @Test
    void a_class_the_suite_no_longer_selects_is_not_priced_by_its_old_record(@TempDir Path ws) throws Exception {
        BuildLayout layout = module(ws);
        write(layout.classesDir(), "demo/Dep", dep());
        write(layout.testClassesDir(), "demo/T", caller("demo.T"));
        write(layout.testClassesDir(), "demo/U", plain("demo.U"));
        write(layout.testClassesDir(), "demo/S", plain("demo.S"));
        write(layout.testClassesDir(), "demo/R", plain("demo.R"));
        writeRows(
                layout.moduleRoot(),
                null,
                row("T", 3, null),
                row("U", 2, null),
                Jsonl.append(
                        row("S", 40, null),
                        JsonFields.fields().string("fallback", "process git").body()),
                Jsonl.append(
                        row("R", 9, null),
                        JsonFields.fields().string("fallback", "process git").body()));

        TestClassRecording.Replayed replayed = Objects.requireNonNull(TestClassRecording.replayedAfter(
                layout.moduleRoot(),
                layout,
                layout.testClassesDir(),
                layout.classesDir(),
                List.of(),
                List.of(edit(SourceApiIndex.Kind.BODY_ONLY)),
                List.of(),
                Set.of("")));
        Map<String, Integer> selected = Map.of("demo.T", 3, "demo.U", 2, "demo.R", 9);
        assertThat(replayed.ran())
                .as("T ran Dep; S and R are keyed by their module")
                .isEqualTo(52);
        assertThat(replayed.rerun(selected))
                .as("T and R by their records; S, tagged out, never starts")
                .isEqualTo(12);
        assertThat(replayed.within(selected.keySet()).classes()).isEqualTo(1);
        assertThat(replayed.within(Set.of("demo.T")).recorded()).containsExactly("demo.T");
    }

    /**
     * Each launch's classes stand on the pool of the header the run that last started that launch
     * wrote: a later run of the integration suite whose test JVMs reached the network outside every
     * class leaves the default suite's forecast as it was, and the integration suite's own classes
     * still all run.
     */
    @Test
    void another_launchs_pool_leaves_this_launchs_forecast(@TempDir Path ws) throws Exception {
        BuildLayout layout = module(ws);
        write(layout.classesDir(), "demo/Dep", dep());
        write(layout.testClassesDir(), "demo/T", caller("demo.T"));
        write(layout.testClassesDir(), "demo/U", plain("demo.U"));
        String integration = "test:integration";
        Path file = TestClassRecords.file(layout.moduleRoot());
        Files.createDirectories(file.getParent());
        String reachedHost = JsonFields.object()
                .string("kind", "suite")
                .array(TestClassRecords.LAUNCHES, List.of(integration))
                .token(
                        "pooled",
                        JsonFields.object()
                                .array("network", List.of("repo.maven.apache.org"))
                                .finish())
                .finish();
        String quiet = JsonFields.object()
                .string("kind", "suite")
                .array(TestClassRecords.LAUNCHES, List.of(""))
                .finish();
        Files.writeString(
                file,
                String.join(
                        "\n",
                        reachedHost,
                        quiet,
                        row("", "T", 3, null),
                        row("", "U", 2, null),
                        row(integration, "U", 40, null),
                        ""));

        assertThat(replayed(layout, edit(SourceApiIndex.Kind.BODY_ONLY)))
                .as("U replays in the default suite: the integration suite's pool is not its own")
                .isEqualTo(2);
        TestClassRecording.Replayed gate = Objects.requireNonNull(TestClassRecording.replayedAfter(
                layout.moduleRoot(),
                layout,
                layout.testClassesDir(),
                layout.classesDir(),
                List.of(),
                List.of(edit(SourceApiIndex.Kind.BODY_ONLY)),
                List.of(),
                Set.of(integration)));
        assertThat(gate.classes()).as("its own pool reached another host").isZero();
        assertThat(gate.ran()).isEqualTo(40);
    }

    @Test
    void a_source_names_its_class_and_the_classes_nested_in_it(@TempDir Path ws) throws Exception {
        Path classes = ws.resolve("classes");
        write(classes, "demo/Dep", dep());
        write(classes, "demo/Dep$In", plain("demo.Dep$In"));
        ClassCones.Index index = ClassCones.index(List.of(classes));
        assertThat(index.compiledFrom("src/main/java/demo/Dep.java")).isEqualTo(Set.of("demo.Dep", "demo.Dep$In"));
        assertThat(index.compiledFrom("src/main/java/demo/New.java")).isEmpty();
    }

    @Test
    void a_class_meets_a_change_by_the_code_it_ran_the_cone_it_reaches_and_the_files_it_read(@TempDir Path ws)
            throws Exception {
        BuildLayout layout = module(ws);
        Path moduleDir = layout.moduleRoot();
        write(layout.classesDir(), "demo/Dep", dep());
        write(layout.testClassesDir(), "demo/T", caller("demo.T"));
        write(layout.testClassesDir(), "demo/U", plain("demo.U"));
        write(layout.testClassesDir(), "demo/V", plain("demo.V"));
        write(layout.testClassesDir(), "demo/W", caller("demo.W"));
        ClassCones.Cone cone = ClassCones.index(List.of(layout.testClassesDir(), layout.classesDir()))
                .cone("demo.T");
        String reads = JsonFields.fields()
                .array("reads", List.of("m/src/test/resources/orders.json"))
                .body();
        List<String> change = List.of("m/src/main/java/demo/Dep.java", "m/src/test/resources/orders.json");

        writeRows(
                moduleDir,
                null,
                row("T", 3, cone.used(c -> true)),
                row("U", 2, null),
                Jsonl.append(row("V", 1, null), reads),
                row("test:integration", "W", 1, null));
        assertThat(overlaps(layout, change))
                .as("W was recorded only in a launch the selection does not start")
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "demo.T", "cone-ran:demo.Dep",
                        "demo.U", "",
                        "demo.V", "reads:m/src/test/resources/orders.json"));

        writeRows(moduleDir, null, row("T", 3, cone.used(c -> c.equals("demo.T"))));
        assertThat(overlaps(layout, change))
                .as("T reaches Dep but never ran it")
                .containsEntry("demo.T", "cone:demo.Dep");
    }

    private static Map<String, String> overlaps(BuildLayout layout, List<String> changed) throws Exception {
        return TestClassRecording.overlaps(
                layout.moduleRoot(),
                layout,
                layout.testClassesDir(),
                layout.classesDir(),
                List.of(),
                changed,
                Set.of(""));
    }

    private static @Nullable Long replayed(BuildLayout layout, SourceApiIndex.Hint edit) throws Exception {
        TestClassRecording.Replayed replayed = TestClassRecording.replayedAfter(
                layout.moduleRoot(),
                layout,
                layout.testClassesDir(),
                layout.classesDir(),
                List.of(),
                List.of(edit),
                List.of(),
                Set.of(""));
        return replayed == null ? null : replayed.tests();
    }

    /** What replays once {@code rebuilding} worker jars are built, with no source of the classpath edited. */
    private static @Nullable Long replayedWith(BuildLayout layout, Path... rebuilding) throws Exception {
        TestClassRecording.Replayed replayed = TestClassRecording.replayedAfter(
                layout.moduleRoot(),
                layout,
                layout.testClassesDir(),
                layout.classesDir(),
                List.of(),
                List.of(),
                List.of(rebuilding),
                Set.of(""));
        return replayed == null ? null : replayed.tests();
    }

    /** {@code row} naming {@code code} as a code file it read, and past its bound when {@code more}. */
    private static String withCode(String row, String code, boolean more) {
        String fields = JsonFields.fields()
                .array("code", List.of(code))
                .optionalTrue("codeMore", more)
                .body();
        return Jsonl.append(row, fields);
    }

    /** A record file of {@code rows} whose pool read {@code pooledCode}, when not null. */
    private static void writeRows(Path moduleDir, @Nullable String pooledCode, String... rows) throws Exception {
        Path file = TestClassRecords.file(moduleDir);
        Files.createDirectories(file.getParent());
        JsonFields header = JsonFields.object().string("kind", "suite").array(TestClassRecords.LAUNCHES, LAUNCHES);
        if (pooledCode != null) {
            header.token(
                    "pooled",
                    JsonFields.object().array("code", List.of(pooledCode)).finish());
        }
        Files.writeString(file, header.finish() + "\n" + String.join("\n", rows) + "\n");
    }

    /** A record file of {@code rows} whose header lists the suite's code files as {@code suiteCode}. */
    private static void writeHeader(Path moduleDir, List<String> suiteCode, String... rows) throws Exception {
        Path file = TestClassRecords.file(moduleDir);
        Files.createDirectories(file.getParent());
        String header = JsonFields.object()
                .string("kind", "suite")
                .array(TestClassRecords.LAUNCHES, LAUNCHES)
                .array(SuiteCodeFiles.FIELD, suiteCode)
                .finish();
        Files.writeString(file, header + "\n" + String.join("\n", rows) + "\n");
    }

    private static SourceApiIndex.Hint edit(SourceApiIndex.Kind kind) {
        return new SourceApiIndex.Hint(kind, List.of(), false, List.of("src/main/java/demo/Dep.java"));
    }

    private static BuildLayout module(Path ws) throws Exception {
        Path dir = Files.createDirectories(ws.resolve("m"));
        Files.writeString(dir.resolve("jk.toml"), "name = \"m\"\ngroup = \"demo\"\nversion = \"1.0\"\n");
        return BuildLayout.of(ws, dir, JkBuildParser.parse(dir.resolve("jk.toml")));
    }

    /** The records of a green run: T (3 tests) with {@code tUsed}, U (2 tests). */
    private static void records(Path moduleDir, String tUsed) throws Exception {
        Path file = TestClassRecords.file(moduleDir);
        Files.createDirectories(file.getParent());
        Files.writeString(
                file,
                String.join(
                        "\n",
                        JsonFields.object()
                                .string("kind", "suite")
                                .array(TestClassRecords.LAUNCHES, LAUNCHES)
                                .finish(),
                        row("T", 3, tUsed),
                        row("U", 2, null),
                        ""));
    }

    private static String row(String cls, int tests, @Nullable String used) {
        return row("", cls, tests, used);
    }

    private static String row(String launch, String cls, int tests, @Nullable String used) {
        return JsonFields.object()
                .string("launch", launch)
                .array("container", List.of("demo", cls))
                .string("outcome", "passed")
                .number("tests", tests)
                .number("ranAt", 1_000)
                .string("classKey", "k")
                .optionalString("coneUsed", used)
                .finish();
    }

    private static byte[] dep() {
        return ClassFile.of()
                .build(
                        DEP,
                        cb -> cb.withMethod(
                                "f",
                                MethodTypeDesc.of(CD_int),
                                ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                                mb -> mb.withCode(c -> c.ldc(1).ireturn())));
    }

    private static byte[] caller(String name) {
        return ClassFile.of()
                .build(
                        ClassDesc.of(name),
                        cb -> cb.withMethod(
                                "run",
                                MethodTypeDesc.of(CD_int),
                                ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                                mb -> mb.withCode(c -> c.invokestatic(DEP, "f", MethodTypeDesc.of(CD_int))
                                        .ireturn())));
    }

    private static byte[] plain(String name) {
        return ClassFile.of().build(ClassDesc.of(name), cb -> {});
    }

    private static void write(Path root, String internalName, byte[] bytes) throws Exception {
        Path f = root.resolve(internalName + ".class");
        Files.createDirectories(f.getParent());
        Files.write(f, bytes);
    }
}
