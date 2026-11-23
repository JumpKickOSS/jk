// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.task.TestStamp;
import cc.jumpkick.test.ClassCones;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A class's key moves with what it read; past its record's bound it moves with everything its suite
 * read; a workspace class it loaded outside its cone joins the cone; it falls back to its module when
 * it started a program, loaded past its bound, read more code files than its suite's list holds or
 * has no record.
 */
class ClassKeysTest {

    /** The name a class's record keeps for git run in a repository a test made. */
    private static final String GIT = "git";

    @Test
    void a_class_is_keyed_by_its_cone_and_reads_until_it_cannot_be_trusted(@TempDir Path ws) throws Exception {
        Path classes = Files.createDirectories(ws.resolve("classes"));
        ClassCones.Cone cone = ClassCones.index(List.of(classes)).cone("demo.T");
        Path data = Files.writeString(ws.resolve("data.txt"), "one");
        ClassKeys.Observed seen = observed(List.of("data.txt"), false, List.of());
        ClassKeys.Key key = key(cone, seen, "suite-reads", ws);
        Files.writeString(data, "two");
        assertThat(key(cone, seen, "suite-reads", ws).key())
                .as("a file it read changed")
                .isNotEqualTo(key.key());

        ClassKeys.Observed many = observed(List.of("data.txt"), true, List.of());
        String before = key(cone, many, "reads-1", ws).key();
        assertThat(key(cone, many, "reads-2", ws).key())
                .as("past its bound, any file its suite read")
                .isNotEqualTo(before);
        assertThat(key(cone, seen, "reads-2", ws).key())
                .as("within its bound, only its own")
                .isEqualTo(key(cone, seen, "reads-1", ws).key());

        assertThat(ClassKeys.fallback(cone, observed(List.of(), false, List.of("/usr/bin/git")), false, false, false))
                .startsWith("process");
        ClassKeys.Observed pastOwnCode = new ClassKeys.Observed(true, List.of(), false, List.of(), true, List.of());
        assertThat(ClassKeys.fallback(cone, pastOwnCode, false, false, false))
                .as("past its own code bound it stands on its suite's code files, not its module")
                .isNull();
        assertThat(ClassKeys.fallback(cone, pastOwnCode, false, false, true))
                .as("past its suite's too")
                .isEqualTo("code past the suite's bound");
        assertThat(ClassKeys.fallback(cone, seen, true, false, false)).startsWith("loads past");
        assertThat(ClassKeys.fallback(cone, seen, false, true, false)).startsWith("reflective loads past");
        assertThat(ClassKeys.fallback(cone, ClassKeys.Observed.NONE, false, false, false))
                .isEqualTo("unattributed");
    }

    /**
     * A workspace class loaded by name that the test class's static cone does not reach joins its
     * cone, so an edit to it moves the key and an edit elsewhere does not; past the bound the class
     * is keyed by its module.
     */
    @Test
    void a_class_loaded_outside_the_cone_keys_the_class_by_its_own_cone(@TempDir Path ws) throws Exception {
        Path classes = Files.createDirectories(ws.resolve("classes"));
        copy(classes, "demo/T", FIXTURE + "ConeFar");
        copy(classes, "demo/Ext", FIXTURE + "ConeNamed");
        copy(classes, "demo/Other", FIXTURE + "ConeUnrelated");
        copy(classes, "demo/T$Nested", FIXTURE + "ConeUnrelated");
        ClassCones.Index index = ClassCones.index(List.of(classes));
        assertThat(ClassKeys.reflective(index, "demo.T", List.of("demo.T", "demo.T$Nested", "demo.Ext", "lib.Outside")))
                .as("only the workspace class outside its cone")
                .containsExactly("demo.Ext");

        ClassKeys.Observed seen = observed(List.of(), false, List.of());
        String before = key(index.cone("demo.T", List.of("demo.Ext")), seen, "reads", ws)
                .key();
        copy(classes, "demo/Other", FIXTURE + "ConeNamed");
        ClassCones.Index otherEdited = ClassCones.index(List.of(classes));
        assertThat(key(otherEdited.cone("demo.T", List.of("demo.Ext")), seen, "reads", ws)
                        .key())
                .as("a class it never loaded changed")
                .isEqualTo(before);
        copy(classes, "demo/Ext", FIXTURE + "ConeUnrelated");
        ClassCones.Index extEdited = ClassCones.index(List.of(classes));
        assertThat(key(extEdited.cone("demo.T", List.of("demo.Ext")), seen, "reads", ws)
                        .key())
                .as("the class it loaded by name changed")
                .isNotEqualTo(before);

        List<String> many = new ArrayList<>();
        for (int i = 0; i <= ClassKeys.REFLECTIVE_KEPT; i++) {
            copy(classes, "demo/L" + i, FIXTURE + "ConeUnrelated");
            many.add("demo.L" + i);
        }
        assertThat(ClassKeys.reflective(ClassCones.index(List.of(classes)), "demo.T", many))
                .as("past the bound")
                .isNull();
    }

    /**
     * A class whose only program is git in a repository a test made keeps its cone and stands on
     * git's identity as the suite's stamp names it: an upgrade moves its key, nothing else does.
     */
    @Test
    void a_tool_keys_a_class_by_its_identity_in_the_suites_stamp(@TempDir Path ws) throws Exception {
        ClassCones.Cone cone = ClassCones.index(List.of(Files.createDirectories(ws.resolve("classes"))))
                .cone("demo.T");
        ClassKeys.Observed seen =
                new ClassKeys.Observed(true, List.of(), false, List.of(), false, List.of(), List.of(GIT));
        assertThat(ClassKeys.fallback(cone, seen, false, false, false))
                .as("no program started that reads what its key does not hold")
                .isNull();

        Map<String, String> v1 = ClassKeys.programs(List.of("proc:git=git version 2.51.0", "proc:sh=posix", "sel:x"));
        assertThat(v1).containsOnlyKeys("git", "sh");
        Map<String, String> v2 = ClassKeys.programs(List.of("proc:git=git version 2.52.0", "proc:sh=posix"));
        Map<String, String> otherTools = ClassKeys.programs(List.of("proc:git=git version 2.51.0", "proc:node=v24"));
        String before = key(cone, ClassKeys.identified(seen, v1), "reads", ws).key();
        assertThat(key(cone, ClassKeys.identified(seen, v2), "reads", ws).key())
                .as("git upgraded")
                .isNotEqualTo(before);
        assertThat(key(cone, ClassKeys.identified(seen, otherTools), "reads", ws)
                        .key())
                .as("another program's identity is not this class's")
                .isEqualTo(before);
        assertThat(key(cone, ClassKeys.identified(seen, Map.of()), "reads", ws).key())
                .as("a stamp that names no git yet")
                .isNotEqualTo(before);
        assertThat(key(cone, ClassKeys.identified(observed(List.of(), false, List.of()), v1), "reads", ws)
                        .key())
                .as("a class that started nothing")
                .isNotEqualTo(before);
    }

    /**
     * A data file's directory is keyed by its listing, taken once per pass: the classes of one pass
     * share it, and only a pass after the directory changed sees the new name.
     */
    @Test
    void a_directorys_listing_is_taken_once_for_every_class_of_a_pass(@TempDir Path ws) throws Exception {
        ClassCones.Cone cone = ClassCones.index(List.of(Files.createDirectories(ws.resolve("classes"))))
                .cone("demo.T");
        Path data = Files.createDirectories(ws.resolve("data"));
        Files.writeString(data.resolve("a.txt"), "a");
        ClassKeys.Observed seen = observed(List.of("data/a.txt"), false, List.of());
        Map<Path, String> pass = new HashMap<>();
        String first = ClassKeys.key(
                        "",
                        cone,
                        null,
                        "suite",
                        seen,
                        Map.of(),
                        ClassKeys.Observed.NONE,
                        "module",
                        "reads",
                        ws,
                        null,
                        pass)
                .key();
        assertThat(pass)
                .as("the listing the pass took")
                .containsOnlyKeys(data.toAbsolutePath().normalize());

        Files.writeString(data.resolve("b.txt"), "b");
        assertThat(ClassKeys.key(
                                "",
                                cone,
                                null,
                                "suite",
                                seen,
                                Map.of(),
                                ClassKeys.Observed.NONE,
                                "module",
                                "reads",
                                ws,
                                null,
                                pass)
                        .key())
                .as("another class of the same pass reuses the listing")
                .isEqualTo(first);
        assertThat(key(cone, seen, "reads", ws).key())
                .as("a new pass lists the directory again and sees the file added beside")
                .isNotEqualTo(first);
    }

    @Test
    void the_selection_stays_in_the_suite_part_and_the_test_compile_does_not() throws Exception {
        String fast = ClassKeys.suitePart(List.of("sel:fast", "suites-run:test", "x"), List.of(), List.of());
        assertThat(ClassKeys.suitePart(List.of("sel:all", "suites-run:test,integration", "x"), List.of(), List.of()))
                .as("a wider run selects methods a fast run never ran")
                .isNotEqualTo(fast);
        assertThat(ClassKeys.suitePart(
                        List.of("sel:fast", "suites-run:test", "x", TestStamp.COMPILE_TEST + "abc"),
                        List.of(),
                        List.of()))
                .as("the class bytes the compile wrote are the cone's")
                .isEqualTo(fast);
    }

    /**
     * A class keyed by its module because it started a program stands on that program's identity as
     * the suite's stamp names it: an upgrade of node reruns it. A class keyed by its cone that started
     * nothing does not move.
     */
    @Test
    void a_module_keyed_class_reruns_when_a_program_its_suite_started_changes(@TempDir Path ws) throws Exception {
        ClassCones.Cone cone = ClassCones.index(List.of(Files.createDirectories(ws.resolve("classes"))))
                .cone("demo.T");
        List<String> v24 = List.of("sel:fast", "proc:node=v24.1.0", "proc:sh=posix");
        List<String> v25 = List.of("sel:fast", "proc:node=v25.0.0", "proc:sh=posix");
        ClassKeys.Observed node = observed(List.of(), false, List.of("/usr/bin/node"));
        String fallback = ClassKeys.fallback(cone, node, false, false, false);
        assertThat(fallback).isEqualTo("process /usr/bin/node");

        assertThat(stampKey(cone, node, v25, fallback, ws))
                .as("node upgraded under a class that started it")
                .isNotEqualTo(stampKey(cone, node, v24, fallback, ws));
        ClassKeys.Observed nothing = observed(List.of(), false, List.of());
        assertThat(stampKey(cone, nothing, v25, null, ws))
                .as("a class keyed by its cone started no program")
                .isEqualTo(stampKey(cone, nothing, v24, null, ws));
    }

    /**
     * No class's record says which variables of the caller's it read, so what the suite read of the
     * caller's environment keys every class: a value that changed reruns them all, and so does the
     * whole environment a suite that started a program (git among them) took.
     */
    @Test
    void what_the_suite_read_of_the_callers_environment_keys_every_class() throws Exception {
        String foo1 = ClassKeys.suitePart(List.of("sel:fast", "env-read:FOO=aaa"), List.of(), List.of());
        assertThat(ClassKeys.suitePart(List.of("sel:fast", "env-read:FOO=bbb"), List.of(), List.of()))
                .as("FOO changed in the caller's shell")
                .isNotEqualTo(foo1);
        assertThat(ClassKeys.suitePart(List.of("sel:fast", "env-read:FOO=unset"), List.of(), List.of()))
                .as("FOO unset since")
                .isNotEqualTo(foo1);
        String all1 = ClassKeys.suitePart(List.of("sel:fast", "env-all=aaa"), List.of(), List.of());
        assertThat(ClassKeys.suitePart(List.of("sel:fast", "env-all=bbb"), List.of(), List.of()))
                .as("a GIT_ variable a started program reads changed")
                .isNotEqualTo(all1);
        assertThat(ClassKeys.suitePart(List.of("sel:fast", "env-names=bbb"), List.of(), List.of()))
                .as("the names a listing saw changed")
                .isNotEqualTo(ClassKeys.suitePart(List.of("sel:fast", "env-names=aaa"), List.of(), List.of()));
    }

    /** A class's key as the shadow takes it from a stamp of {@code extras}. */
    private static String stampKey(
            ClassCones.Cone cone, ClassKeys.Observed seen, List<String> extras, @Nullable String fallback, Path ws)
            throws Exception {
        Map<String, String> programs = ClassKeys.programs(extras);
        return ClassKeys.key(
                        "",
                        cone,
                        null,
                        ClassKeys.suitePart(extras, List.of(), List.of()),
                        ClassKeys.identified(seen, programs),
                        programs,
                        ClassKeys.Observed.NONE,
                        "module",
                        "reads",
                        ws,
                        fallback,
                        new HashMap<>())
                .key();
    }

    private static final String FIXTURE = "cc/jumpkick/test/";

    /** The bytes of the test fixture class {@code fixture} as the workspace class {@code internalName}. */
    private static void copy(Path classes, String internalName, String fixture) throws Exception {
        Path f = classes.resolve(internalName + ".class");
        Files.createDirectories(f.getParent());
        try (InputStream in =
                Objects.requireNonNull(ClassKeysTest.class.getClassLoader().getResourceAsStream(fixture + ".class"))) {
            Files.write(f, in.readAllBytes());
        }
    }

    private static ClassKeys.Observed observed(List<String> reads, boolean more, List<String> processes) {
        return new ClassKeys.Observed(true, reads, more, List.of(), false, processes);
    }

    private static ClassKeys.Key key(ClassCones.Cone cone, ClassKeys.Observed seen, String suiteReads, Path ws) {
        return ClassKeys.key(
                "",
                cone,
                null,
                "suite",
                seen,
                Map.of(),
                ClassKeys.Observed.NONE,
                "module",
                suiteReads,
                ws,
                null,
                new HashMap<>());
    }
}
