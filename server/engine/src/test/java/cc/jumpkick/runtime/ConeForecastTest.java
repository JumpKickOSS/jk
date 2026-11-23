// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static java.lang.constant.ConstantDescs.CD_int;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.task.SourceApiIndex;
import cc.jumpkick.test.ClassCones;
import cc.jumpkick.test.ClassReads;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Before the compile an edit waits on, the forecast counts the tests whose classes replay by their
 * cones, as the run will decide it: a body-only edit replays a class that reaches the edited class
 * but never ran it, and runs one that ran it; an edit that moves a declaration runs every class
 * whose cone holds the edited class; an edit the forecast cannot read counts every test. A worker
 * jar the suite is handed by path that is rebuilding runs only the classes that read it.
 */
class ConeForecastTest {

    private static final ClassDesc DEP = ClassDesc.of("demo.Dep");

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
                .as("T read more code files than its record names")
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

    private static @Nullable Long replayed(BuildLayout layout, SourceApiIndex.Hint edit) throws Exception {
        TestClassRecording.Replayed replayed = TestClassRecording.replayedAfter(
                layout.moduleRoot(),
                layout,
                layout.testClassesDir(),
                layout.classesDir(),
                List.of(),
                List.of(edit),
                List.of());
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
                List.of(rebuilding));
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
        JsonFields header = JsonFields.object().string("kind", "suite");
        if (pooledCode != null) {
            header.token(
                    "pooled",
                    JsonFields.object().array("code", List.of(pooledCode)).finish());
        }
        Files.writeString(file, header.finish() + "\n" + String.join("\n", rows) + "\n");
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
                        JsonFields.object().string("kind", "suite").finish(),
                        row("T", 3, tUsed),
                        row("U", 2, null),
                        ""));
    }

    private static String row(String cls, int tests, @Nullable String used) {
        return JsonFields.object()
                .string("launch", "")
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
