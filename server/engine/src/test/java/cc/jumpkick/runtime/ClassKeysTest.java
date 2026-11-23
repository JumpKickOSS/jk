// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.orchestrator.ClassCones;
import cc.jumpkick.task.TestStamp;
import cc.jumpkick.test.ClassReads;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
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
        ClassKeys.Key key = key(cone, seen, ClassKeys.SuiteReadSet.NONE, ws);
        Files.writeString(data, "two");
        assertThat(key(cone, seen, ClassKeys.SuiteReadSet.NONE, ws).key())
                .as("a file it read changed")
                .isNotEqualTo(key.key());

        ClassKeys.Observed many = observed(List.of("data.txt"), true, List.of());
        String before = key(cone, many, suiteRead(ws, "1"), ws).key();
        assertThat(key(cone, many, suiteRead(ws, "2"), ws).key())
                .as("past its bound, any file its suite read")
                .isNotEqualTo(before);
        assertThat(key(cone, seen, suiteRead(ws, "2"), ws).key())
                .as("within its bound, only its own")
                .isEqualTo(key(cone, seen, suiteRead(ws, "1"), ws).key());

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
        copy(classes, "demo/T", FIXTURE + "KeyFixtureFar");
        copy(classes, "demo/Ext", FIXTURE + "KeyFixtureNamed");
        copy(classes, "demo/Other", FIXTURE + "KeyFixtureUnrelated");
        copy(classes, "demo/T$Nested", FIXTURE + "KeyFixtureUnrelated");
        ClassCones.Index index = ClassCones.index(List.of(classes));
        assertThat(ClassKeys.reflective(index, "demo.T", List.of("demo.T", "demo.T$Nested", "demo.Ext", "lib.Outside")))
                .as("only the workspace class outside its cone")
                .containsExactly("demo.Ext");

        ClassKeys.Observed seen = observed(List.of(), false, List.of());
        String before = key(index.cone("demo.T", List.of("demo.Ext")), seen, ClassKeys.SuiteReadSet.NONE, ws)
                .key();
        copy(classes, "demo/Other", FIXTURE + "KeyFixtureNamed");
        ClassCones.Index otherEdited = ClassCones.index(List.of(classes));
        assertThat(key(otherEdited.cone("demo.T", List.of("demo.Ext")), seen, ClassKeys.SuiteReadSet.NONE, ws)
                        .key())
                .as("a class it never loaded changed")
                .isEqualTo(before);
        copy(classes, "demo/Ext", FIXTURE + "KeyFixtureUnrelated");
        ClassCones.Index extEdited = ClassCones.index(List.of(classes));
        assertThat(key(extEdited.cone("demo.T", List.of("demo.Ext")), seen, ClassKeys.SuiteReadSet.NONE, ws)
                        .key())
                .as("the class it loaded by name changed")
                .isNotEqualTo(before);

        List<String> many = new ArrayList<>();
        for (int i = 0; i <= ClassKeys.REFLECTIVE_KEPT; i++) {
            copy(classes, "demo/L" + i, FIXTURE + "KeyFixtureUnrelated");
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
        ClassKeys.Observed seen = new ClassKeys.Observed(
                true, List.of(), false, List.of(), false, List.of(), List.of(GIT), List.of(), List.of());
        assertThat(ClassKeys.fallback(cone, seen, false, false, false))
                .as("no program started that reads what its key does not hold")
                .isNull();

        Map<String, String> v1 = ClassKeys.programs(List.of("proc:git=git version 2.51.0", "proc:sh=posix", "sel:x"));
        assertThat(v1).containsOnlyKeys("git", "sh");
        Map<String, String> v2 = ClassKeys.programs(List.of("proc:git=git version 2.52.0", "proc:sh=posix"));
        Map<String, String> otherTools = ClassKeys.programs(List.of("proc:git=git version 2.51.0", "proc:node=v24"));
        String before = key(cone, ClassKeys.identified(seen, v1), ClassKeys.SuiteReadSet.NONE, ws)
                .key();
        assertThat(key(cone, ClassKeys.identified(seen, v2), ClassKeys.SuiteReadSet.NONE, ws)
                        .key())
                .as("git upgraded")
                .isNotEqualTo(before);
        assertThat(key(cone, ClassKeys.identified(seen, otherTools), ClassKeys.SuiteReadSet.NONE, ws)
                        .key())
                .as("another program's identity is not this class's")
                .isEqualTo(before);
        assertThat(key(cone, ClassKeys.identified(seen, Map.of()), ClassKeys.SuiteReadSet.NONE, ws)
                        .key())
                .as("a stamp that names no git yet")
                .isNotEqualTo(before);
        assertThat(key(
                                cone,
                                ClassKeys.identified(observed(List.of(), false, List.of()), v1),
                                ClassKeys.SuiteReadSet.NONE,
                                ws)
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
                        ClassKeys.SuiteReadSet.NONE,
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
                                ClassKeys.SuiteReadSet.NONE,
                                ws,
                                null,
                                pass)
                        .key())
                .as("another class of the same pass reuses the listing")
                .isEqualTo(first);
        assertThat(key(cone, seen, ClassKeys.SuiteReadSet.NONE, ws).key())
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
     * A worker jar the suite's JVMs are handed by path (a nested engine's) keys a class only where
     * the class read it, as a code file by content, or where the class is keyed by its module: a
     * rebuilt engine jar moves neither the key of a class that never opened it nor its suite part.
     */
    @Test
    void a_worker_jar_keys_only_the_classes_that_read_it(@TempDir Path ws) throws Exception {
        ClassCones.Cone cone = ClassCones.index(List.of(Files.createDirectories(ws.resolve("classes"))))
                .cone("demo.T");
        Path jar = Files.createDirectories(ws.resolve("engine/target")).resolve("engine-all.jar");
        Files.writeString(jar, "v1");
        List<String> v1 = List.of("sel:fast", "worker:jk.engine.jar=v1");
        List<String> v2 = List.of("sel:fast", "worker:jk.engine.jar=v2");
        assertThat(ClassKeys.replacedToken("worker:jk.engine.jar=v1")).isTrue();
        assertThat(ClassKeys.suitePart(v1, List.of(), List.of()))
                .isEqualTo(ClassKeys.suitePart(v2, List.of(), List.of()));

        ClassKeys.Observed nothing = observed(List.of(), false, List.of());
        assertThat(stampKey(cone, nothing, v2, null, ws))
                .as("a class that never read the jar")
                .isEqualTo(stampKey(cone, nothing, v1, null, ws));

        ClassKeys.Observed started = new ClassKeys.Observed(
                true,
                List.of(),
                false,
                List.of(),
                false,
                List.of(),
                List.of(ClassReads.STARTED_PROGRAM),
                List.of(),
                List.of());
        assertThat(stampKey(cone, started, v2, null, ws))
                .as("a class in whose JVM a program was started, which may run the jar")
                .isNotEqualTo(stampKey(cone, started, v1, null, ws));

        ClassKeys.Observed java = observed(List.of(), false, List.of("/usr/bin/java"));
        String fallback = ClassKeys.fallback(cone, java, false, false, false);
        assertThat(stampKey(cone, java, v2, fallback, ws))
                .as("a class keyed by its module")
                .isNotEqualTo(stampKey(cone, java, v1, fallback, ws));

        assertThat(TestClassRecording.classKeyed(
                        List.of(ws.resolve("lib"), ws.resolve("engine/target/../target/engine-all.jar")), List.of(jar)))
                .as("what the recording reads as keyed for every class leaves the jar to the class that read it")
                .containsExactly(ws.resolve("lib"));

        ClassKeys.Observed opened = new ClassKeys.Observed(
                true, List.of(), false, List.of("engine/target/engine-all.jar"), false, List.of());
        String before = stampKey(cone, opened, v1, null, ws);
        Files.writeString(jar, "version 2");
        assertThat(stampKey(cone, opened, v2, null, ws))
                .as("a class that read the jar, rebuilt")
                .isNotEqualTo(before);
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

    /**
     * A class past its read bound stands on the files its suite read under its read roots: an edit
     * elsewhere that its suite also read (a source a repository-scanning neighbour read) leaves its
     * key, an edit under its roots moves it, and with no roots it stands on all of them.
     */
    @Test
    void a_class_past_its_read_bound_keys_on_its_suites_reads_under_its_roots(@TempDir Path ws) throws Exception {
        ClassCones.Cone cone = ClassCones.index(List.of(Files.createDirectories(ws.resolve("classes"))))
                .cone("demo.T");
        ClassKeys.Observed rooted = new ClassKeys.Observed(
                true,
                List.of("jk.toml"),
                true,
                List.of(),
                false,
                List.of(),
                List.of(),
                List.of("*", "plugins/**"),
                List.of());
        ClassKeys.Observed unrooted = observed(List.of("jk.toml"), true, List.of());
        ClassKeys.SuiteReadSet before = reads(ws, "a", "p1", "s1");
        ClassKeys.SuiteReadSet sourceEdited = reads(ws, "a", "p1", "s2");
        ClassKeys.SuiteReadSet pluginEdited = reads(ws, "a", "p2", "s1");
        assertThat(key(cone, rooted, sourceEdited, ws).key())
                .as("a source outside its roots moved")
                .isEqualTo(key(cone, rooted, before, ws).key());
        assertThat(key(cone, rooted, pluginEdited, ws).key())
                .as("a plugin manifest under its roots moved")
                .isNotEqualTo(key(cone, rooted, before, ws).key());
        assertThat(key(cone, unrooted, sourceEdited, ws).key())
                .as("no roots: every file its suite read")
                .isNotEqualTo(key(cone, unrooted, before, ws).key());
    }

    /**
     * A class past its read bound whose roots span the tree stands on the files its suite read with
     * the names it read: a manifest-reading class does not stand on a source a neighbour scanned.
     */
    @Test
    void a_class_past_its_read_bound_keys_only_on_the_file_names_it_read(@TempDir Path ws) throws Exception {
        ClassCones.Cone cone = ClassCones.index(List.of(Files.createDirectories(ws.resolve("classes"))))
                .cone("demo.T");
        ClassKeys.Observed named = new ClassKeys.Observed(
                true,
                List.of("jk.toml"),
                true,
                List.of(),
                false,
                List.of(),
                List.of(),
                List.of("*", "plugins/**", "server/**"),
                List.of("jk-plugin.toml", "jk.toml"));
        ClassKeys.Observed unrooted = new ClassKeys.Observed(
                true, List.of("jk.toml"), true, List.of(), false, List.of(), List.of(), List.of(), List.of("jk.toml"));
        ClassKeys.SuiteReadSet before = reads(ws, "a", "p1", "s1");
        assertThat(key(cone, named, reads(ws, "a", "p1", "s2"), ws).key())
                .as("a source under its roots moved, of a name it never read")
                .isEqualTo(key(cone, named, before, ws).key());
        assertThat(key(cone, named, reads(ws, "a", "p2", "s1"), ws).key())
                .as("a plugin manifest moved")
                .isNotEqualTo(key(cone, named, before, ws).key());
        assertThat(key(cone, unrooted, reads(ws, "a", "p2", "s2"), ws).key())
                .as("no roots: the files of its names anywhere")
                .isEqualTo(key(cone, unrooted, before, ws).key());
        assertThat(key(cone, unrooted, reads(ws, "b", "p1", "s1"), ws).key())
                .as("the root manifest moved")
                .isNotEqualTo(key(cone, unrooted, before, ws).key());
    }

    /** The discovered inputs of a suite that read one file, as it was at {@code version}. */
    private static ClassKeys.SuiteReadSet suiteRead(Path ws, String version) {
        return new ClassKeys.SuiteReadSet(List.of("read:other.txt=" + version), ws);
    }

    /** The discovered inputs of a suite that read the root manifest, a plugin manifest and an engine source. */
    private static ClassKeys.SuiteReadSet reads(Path ws, String root, String plugin, String source) {
        return new ClassKeys.SuiteReadSet(
                List.of(
                        "read:jk.toml=" + root,
                        "read:plugins/a/jk-plugin.toml=" + plugin,
                        "read:server/engine/src/Self.java=" + source,
                        "list:plugins/a=l",
                        "list:server/engine/src=l"),
                ws);
    }

    /**
     * A program started outside every class's window, in discovery or a static initialiser, reads
     * what no recording holds for no class in particular: the record file's header keeps it, and
     * every class is keyed by its module, one recorded on its cone beside such a pool included.
     */
    @Test
    void a_program_started_outside_every_window_keys_every_class_by_its_module(@TempDir Path ws) throws Exception {
        ClassCones.Index index = ClassCones.index(List.of(Files.createDirectories(ws.resolve("classes"))));
        String header = JsonFields.object()
                .string("kind", "suite")
                .token(
                        "pooled",
                        JsonFields.object()
                                .array("reads", List.of())
                                .array("processes", List.of("/usr/bin/node"))
                                .finish())
                .finish();
        ClassKeys.Observed pooled = ClassShadow.pooledOf(header);
        assertThat(pooled.processes()).containsExactly("/usr/bin/node");
        assertThat(ClassKeys.pooledFallback(pooled)).isEqualTo("pooled process /usr/bin/node");
        assertThat(ClassKeys.pooledFallback(ClassShadow.pooledOf("{\"kind\":\"suite\",\"pooled\":{}}")))
                .as("a pool that started nothing")
                .isNull();

        ClassShadow beside = shadow(index, ws, pooled);
        TestClassRecords.Shadowed now = beside.of(
                new TestClassRecords.Outcome("", "demo.T", 1, 0, 0, 1_000, 1), ClassReads.Observation.empty(), null);
        assertThat(now.fallback()).isEqualTo("pooled process /usr/bin/node");
        assertThat(now.key()).startsWith("module:");

        String onCone = ClassKeys.key(
                        "",
                        index.cone("demo.T"),
                        null,
                        "suite",
                        ClassKeys.Observed.NONE,
                        Map.of(),
                        ClassKeys.Observed.NONE,
                        index.digest(),
                        ClassKeys.SuiteReadSet.NONE,
                        ws,
                        null,
                        new HashMap<>())
                .key();
        String row = JsonFields.object()
                .string("launch", "")
                .string("outcome", "passed")
                .number("ranAt", 1_000)
                .bool("attributed", true)
                .string("classKey", onCone)
                .finish();
        assertThat(shadow(index, ws, ClassKeys.Observed.NONE).whyRun(row, "", "demo.T"))
                .as("nothing started outside a window: its cone holds")
                .isNull();
        assertThat(beside.whyRun(row, "", "demo.T"))
                .as("kept on its cone beside a pool that started a program")
                .isEqualTo("module pooled process /usr/bin/node");
    }

    /**
     * A tool started outside every class's window (a static initialiser asking {@code node
     * --version}) served no class in particular: the record file's header keeps it, and every class
     * stands on its identity, so an upgrade of it re-runs them all and a class recorded beside it
     * under the old identity runs once more.
     */
    @Test
    void a_tool_started_outside_every_window_keys_every_class_by_its_identity(@TempDir Path ws) throws Exception {
        ClassCones.Index index = ClassCones.index(List.of(Files.createDirectories(ws.resolve("classes"))));
        ClassReads.Observation pool = ClassReads.Observation.empty();
        pool.addTool("node");
        String header = TestClassRecords.header(
                "g:m",
                new ClassReads.Attributed(Map.of(), pool, true, 1, 1),
                TestClassRecords.Lookups.none(),
                ws,
                null);
        ClassKeys.Observed pooled = ClassShadow.pooledOf(header);
        assertThat(pooled.tools()).containsExactly("node");
        assertThat(ClassKeys.pooledFallback(pooled))
                .as("a tool is no program started")
                .isNull();

        Map<String, String> v24 = ClassKeys.programs(List.of("proc:node=v24.1.0", "proc:sh=posix"));
        Map<String, String> v25 = ClassKeys.programs(List.of("proc:node=v25.0.0", "proc:sh=posix"));
        Map<String, String> shUpgraded = ClassKeys.programs(List.of("proc:node=v24.1.0", "proc:sh=posix2"));
        TestClassRecords.Outcome ran = new TestClassRecords.Outcome("", "demo.T", 1, 0, 0, 1_000, 1);
        TestClassRecords.Shadowed before = shadow(index, ws, pooled, v24).of(ran, ClassReads.Observation.empty(), null);
        assertThat(before.fallback()).as("the class keeps its cone").isNull();
        assertThat(shadow(index, ws, pooled, v25)
                        .of(ran, ClassReads.Observation.empty(), null)
                        .key())
                .as("node upgraded")
                .isNotEqualTo(before.key());
        assertThat(shadow(index, ws, pooled, shUpgraded)
                        .of(ran, ClassReads.Observation.empty(), null)
                        .key())
                .as("a program the pool did not start")
                .isEqualTo(before.key());
        assertThat(shadow(index, ws, ClassKeys.Observed.NONE, v24)
                        .of(ran, ClassReads.Observation.empty(), null)
                        .key())
                .as("a pool that started no tool")
                .isNotEqualTo(before.key());

        String row = JsonFields.object()
                .string("launch", "")
                .string("outcome", "passed")
                .number("ranAt", 1_000)
                .bool("attributed", true)
                .string("classKey", before.key())
                .finish();
        assertThat(shadow(index, ws, pooled, v24).whyRun(row, "", "demo.T"))
                .as("the same node")
                .isNull();
        assertThat(shadow(index, ws, pooled, v25).whyRun(row, "", "demo.T"))
                .as("recorded beside the old node")
                .isEqualTo("key");
    }

    /**
     * The key the selection takes of a class is the key its row holds while nothing it stands on
     * moved, though the record file's header beside the row is rewritten by every run: a class that
     * replayed in a run whose pool read what the run before's did not, beside an edit to a class
     * outside its cone, replays the next time too, and still runs when the pool's file changes.
     */
    @Test
    void a_class_that_replayed_keeps_its_key_when_the_pool_beside_it_moves(@TempDir Path ws) throws Exception {
        Path classes = Files.createDirectories(ws.resolve("classes"));
        copy(classes, "demo/T", FIXTURE + "KeyFixtureUnrelated");
        copy(classes, "demo/U", FIXTURE + "KeyFixtureUnrelated");
        copy(classes, "demo/Other", FIXTURE + "KeyFixtureUnrelated");
        Path module = Files.createDirectories(ws.resolve("m"));
        Path pooledFile = Files.writeString(ws.resolve("pool.txt"), "one");

        recordRun(module, classes, ws, ClassReads.Observation.empty(), List.of("demo.T", "demo.U"));
        assertThat(selected(module, classes, ws, "demo.U")).as("nothing moved").isNull();

        copy(classes, "demo/Other", FIXTURE + "KeyFixtureNamed");
        ClassReads.Observation pool = ClassReads.Observation.empty();
        pool.addRead(pooledFile);
        recordRun(module, classes, ws, pool, List.of("demo.T"));
        assertThat(selected(module, classes, ws, "demo.T"))
                .as("it ran beside this pool")
                .isNull();
        assertThat(selected(module, classes, ws, "demo.U"))
                .as("it replayed beside this pool")
                .isNull();

        Files.writeString(pooledFile, "two");
        assertThat(selected(module, classes, ws, "demo.U"))
                .as("what the pool read changed")
                .isEqualTo("key");
    }

    /**
     * A launch runs its own test JVMs, so what one launch's JVMs did outside every window is no input
     * of another launch's classes. A run of the integration suite whose pool started a program, or
     * reached the network, left the default suite's classes keyed by their module, and a body-only
     * engine edit then ran every class of jk-engine's default suite. Each launch's classes stand on
     * the header of the run that last started that launch, and a later run of another launch keeps it.
     */
    @Test
    void another_launchs_pool_is_no_input_of_this_launchs_classes(@TempDir Path ws) throws Exception {
        Path classes = Files.createDirectories(ws.resolve("classes"));
        copy(classes, "demo/T", FIXTURE + "KeyFixtureUnrelated");
        copy(classes, "demo/U", FIXTURE + "KeyFixtureUnrelated");
        Path module = Files.createDirectories(ws.resolve("m"));
        String integration = "test:integration";
        ClassReads.Observation started = ClassReads.Observation.empty();
        started.addProcess("/usr/bin/setsid");

        recordRun(module, classes, ws, ClassReads.Observation.empty(), "", List.of("demo.T"));
        recordRun(module, classes, ws, started, integration, List.of("demo.U"));
        assertThat(selected(module, classes, ws, "", "demo.T"))
                .as("the integration suite's pool is not the default suite's")
                .isNull();
        assertThat(TestClassRecords.headers(TestClassRecords.file(module))).containsOnlyKeys("", integration);

        recordRun(module, classes, ws, ClassReads.Observation.empty(), "", List.of("demo.U"));
        assertThat(ClassShadow.pooledOf(TestClassRecords.headers(TestClassRecords.file(module))
                                .get(integration))
                        .processes())
                .as("the default suite's next run keeps the integration suite's pool")
                .containsExactly("/usr/bin/setsid");

        recordRun(module, classes, ws, started, "", List.of("demo.U"));
        assertThat(selected(module, classes, ws, "", "demo.T"))
                .as("a launch's own pool still keys its classes")
                .isEqualTo("module pooled process /usr/bin/setsid");
    }

    /**
     * One run of the guard's suites starts the default launch and the integration launch at once,
     * each in its own JVMs. What the integration JVMs did outside every window (started a program,
     * reached Maven Central) is the integration launch's pool alone: the run writes a header for each
     * launch it told apart, and the default suite's classes stand on their own.
     */
    @Test
    void one_run_of_two_launches_keeps_each_launchs_pool_apart(@TempDir Path ws) throws Exception {
        Path classes = Files.createDirectories(ws.resolve("classes"));
        copy(classes, "demo/T", FIXTURE + "KeyFixtureUnrelated");
        copy(classes, "demo/U", FIXTURE + "KeyFixtureUnrelated");
        Path module = Files.createDirectories(ws.resolve("m"));
        String integration = "test:integration";
        ClassReads.Observation started = ClassReads.Observation.empty();
        started.addProcess("/usr/bin/setsid");
        started.addHost("repo.maven.apache.org");
        ClassReads.Observation union = ClassReads.Observation.empty();
        union.addProcess("/usr/bin/setsid");
        union.addHost("repo.maven.apache.org");

        Map<String, TestClassRecords.Outcome> outcomes = new HashMap<>();
        outcomes.put(TestClassRecords.rowKey("", "demo.T"), new TestClassRecords.Outcome("", "demo.T", 1, 0, 0, 1, 1));
        outcomes.put(
                TestClassRecords.rowKey(integration, "demo.U"),
                new TestClassRecords.Outcome(integration, "demo.U", 1, 0, 0, 1, 1));
        assertThat(TestClassRecording.launchOf(outcomes).apply(List.of("demo.U")))
                .as("a JVM that ran the integration class is the integration launch's")
                .isEqualTo(integration);
        assertThat(TestClassRecording.launchOf(outcomes).apply(List.of("demo.T", "demo.U")))
                .as("a JVM that ran both is neither's")
                .isNull();

        Map<String, ClassReads.Observation> seenByClass =
                Map.of("demo.T", ClassReads.Observation.empty(), "demo.U", ClassReads.Observation.empty());
        ClassReads.Attributed seen = new ClassReads.Attributed(
                seenByClass,
                union,
                ClassReads.SuiteCode.empty(),
                true,
                2,
                2,
                Map.of("", ClassReads.Observation.empty(), integration, started));
        Set<String> launches = TestClassRecords.launches(outcomes);
        List<String> leading = TestClassRecords.headers(
                "g:m",
                seen,
                launches,
                TestClassRecords.Lookups.none(),
                ws,
                null,
                SuiteCodeFiles.listed(null, seen.code(), ws, Long.MAX_VALUE));
        assertThat(leading).as("a header for each launch").hasSize(2);
        ClassShadow shadow = new ClassShadow(
                ClassCones.index(List.of(classes)),
                "suite",
                ws,
                l -> ClassKeys.Observed.NONE,
                l -> ClassShadow.observed(seen.pooled(l), ws),
                Map.of(),
                ClassKeys.SuiteReadSet.NONE,
                ClassKeys.SuiteReadSet.NONE,
                Long.MAX_VALUE,
                false,
                "stamp");
        TestClassRecords.merge(
                module,
                leading,
                launches,
                outcomes,
                (o, prev) -> {
                    ClassReads.Observation obs = seen.classes().get(o.testClass());
                    return TestClassRecords.row(o, obs, TestClassRecords.Lookups.none(), ws, shadow.of(o, obs, prev));
                },
                shadow::kept);

        Map<String, String> headers = TestClassRecords.headers(TestClassRecords.file(module));
        assertThat(Jsonl.strArray(Objects.requireNonNull(Jsonl.nested(headers.get(""), "pooled")), "network"))
                .as("the default launch reached no host")
                .isEmpty();
        assertThat(Jsonl.strArray(Objects.requireNonNull(Jsonl.nested(headers.get(integration), "pooled")), "network"))
                .containsExactly("repo.maven.apache.org");
        assertThat(selected(module, classes, ws, "", "demo.T"))
                .as("the integration launch's program is no input of the default suite's class")
                .isNull();
        assertThat(ClassShadow.pooledOf(headers.get(integration)).processes())
                .as("its own launch's classes stand on its program")
                .containsExactly("/usr/bin/setsid");
        assertThat(ClassShadow.pooledOf(headers.get("")).processes())
                .as("the default launch started none")
                .isEmpty();
    }

    /** One run's records, as a suite's recording leaves them: the classes of {@code ran} ran, beside {@code pool}. */
    private static void recordRun(Path module, Path classes, Path ws, ClassReads.Observation pool, List<String> ran)
            throws Exception {
        recordRun(module, classes, ws, pool, "", ran);
    }

    /** As {@link #recordRun(Path, Path, Path, ClassReads.Observation, List)}, the classes run in {@code launch}. */
    private static void recordRun(
            Path module, Path classes, Path ws, ClassReads.Observation pool, String launch, List<String> ran)
            throws Exception {
        Map<String, ClassReads.Observation> seenByClass = new HashMap<>();
        Map<String, TestClassRecords.Outcome> outcomes = new HashMap<>();
        for (String c : ran) {
            seenByClass.put(c, ClassReads.Observation.empty());
            outcomes.put(
                    TestClassRecords.rowKey(launch, c), new TestClassRecords.Outcome(launch, c, 1, 0, 0, 1_000, 1));
        }
        ClassReads.Attributed seen = new ClassReads.Attributed(seenByClass, pool, true, 1, 1);
        Function<String, ClassKeys.Observed> before =
                TestClassRecording.pooledBy(TestClassRecords.headers(TestClassRecords.file(module)));
        ClassShadow shadow = new ClassShadow(
                ClassCones.index(List.of(classes)),
                "suite",
                ws,
                before,
                l -> l.equals(launch) ? ClassShadow.observed(pool, ws) : before.apply(l),
                Map.of(),
                ClassKeys.SuiteReadSet.NONE,
                ClassKeys.SuiteReadSet.NONE,
                Long.MAX_VALUE,
                false,
                "stamp");
        TestClassRecords.merge(
                module,
                TestClassRecords.header("g:m", seen, TestClassRecords.Lookups.none(), ws, null),
                outcomes,
                (o, prev) -> {
                    ClassReads.Observation obs = seen.classes().get(o.testClass());
                    return TestClassRecords.row(o, obs, TestClassRecords.Lookups.none(), ws, shadow.of(o, obs, prev));
                },
                shadow::kept);
    }

    /** Why the selection runs {@code testClass} over the records of {@code module}, null to replay it. */
    private static @Nullable String selected(Path module, Path classes, Path ws, String testClass) {
        return selected(module, classes, ws, "", testClass);
    }

    /** As {@link #selected(Path, Path, Path, String)}, for the class's row in {@code launch}. */
    private static @Nullable String selected(Path module, Path classes, Path ws, String launch, String testClass) {
        Path file = TestClassRecords.file(module);
        String header = TestClassRecords.header(file);
        ClassShadow keys = new ClassShadow(
                ClassCones.index(List.of(classes)),
                "suite",
                ws,
                TestClassRecording.pooledBy(TestClassRecords.headers(file)),
                l -> ClassKeys.Observed.NONE,
                Map.of(),
                ClassKeys.SuiteReadSet.NONE,
                ClassKeys.SuiteReadSet.NONE,
                SuiteCodeFiles.since(header, ws),
                false,
                "stamp");
        List<String> rows = new ArrayList<>();
        TestClassRecords.forEachRow(file, (key, row) -> {
            if (key.equals(TestClassRecords.rowKey(launch, testClass))) rows.add(row);
        });
        assertThat(rows).as("one row of " + testClass).hasSize(1);
        return keys.whyRun(rows.getFirst(), launch, testClass);
    }

    private static ClassShadow shadow(ClassCones.Index index, Path ws, ClassKeys.Observed pooled) {
        return shadow(index, ws, pooled, Map.of());
    }

    private static ClassShadow shadow(
            ClassCones.Index index, Path ws, ClassKeys.Observed pooled, Map<String, String> programs) {
        return new ClassShadow(
                index,
                "suite",
                ws,
                launch -> pooled,
                launch -> pooled,
                programs,
                ClassKeys.SuiteReadSet.NONE,
                ClassKeys.SuiteReadSet.NONE,
                Long.MAX_VALUE,
                false,
                "stamp");
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
                        ClassKeys.SuiteReadSet.NONE,
                        ws,
                        fallback,
                        new HashMap<>())
                .key();
    }

    private static final String FIXTURE = "cc/jumpkick/runtime/";

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

    private static ClassKeys.Key key(
            ClassCones.Cone cone, ClassKeys.Observed seen, ClassKeys.SuiteReadSet suiteReads, Path ws) {
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

/** A class that calls nothing: what a fixture's bytes are copied from. */
class KeyFixtureFar {
    int value() {
        return 1;
    }
}

class KeyFixtureNamed {}

class KeyFixtureUnrelated {}
