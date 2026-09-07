// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildLogicFixtures.generated;
import static cc.jumpkick.runtime.BuildLogicFixtures.mergedFiles;
import static cc.jumpkick.runtime.BuildLogicFixtures.runTwice;
import static cc.jumpkick.runtime.BuildLogicFixtures.writeLineCountGroovy;
import static cc.jumpkick.runtime.BuildLogicFixtures.writeStampGroovy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.BuildLogicToml;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.task.ActionCache;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class BuildLogicSupportTest {

    @Test
    void convention_jk_dir_runs_and_cache_hits(@TempDir Path dir) throws Exception {
        Path project = scaffold(dir);
        Files.createDirectories(project.resolve(".jk"));
        writeLineCountGroovy(project.resolve(".jk/after-resources.groovy"));
        runTwice(project, dir.resolve("cache"));
    }

    /**
     * `jk: always` exempts a script from the verdict cache: it judges state the key cannot see, so
     * identical sources are not a reason to skip it.
     */
    @Test
    void an_always_script_runs_on_identical_inputs_and_never_cache_hits(@TempDir Path dir) throws Exception {
        Path project = scaffold(dir);
        Files.createDirectories(project.resolve(".jk"));
        Path runs = project.resolve("sweep-runs.txt");
        Files.writeString(project.resolve(".jk/after-resources.groovy"), """
                // jk: always
                new File(projectDir.toFile(), 'sweep-runs.txt') << 'ran\\n'
                """);
        ActionCache ac = new ActionCache(new Cas(dir.resolve("cache/cas")), dir.resolve("cache/actions"));
        BuildLayout layout = BuildLayout.of(project, JkBuildParser.parse(project.resolve("jk.toml")));
        Path classes = Files.createDirectories(layout.classesDir());
        StringBuilder labels = new StringBuilder();
        for (int i = 0; i < 2; i++) {
            assertTrue(BuildLogicSupport.run(
                    project, layout, ac, classes, s -> labels.append(s).append(';')));
        }
        assertFalse(labels.toString().contains("cache hit"), labels.toString());
        assertEquals(2, Files.readAllLines(runs).size(), "ran on both builds");
    }

    @Test
    void visible_jk_dir_runs_and_cache_hits(@TempDir Path dir) throws Exception {
        Path project = scaffold(dir);
        Files.createDirectories(project.resolve("jk"));
        writeLineCountGroovy(project.resolve("jk/after-resources.groovy"));
        runTwice(project, dir.resolve("cache"));
    }

    @Test
    void visible_jk_wins_over_hidden_when_both_exist(@TempDir Path dir) throws Exception {
        Path project = scaffold(dir);
        Files.createDirectories(project.resolve("jk"));
        Files.createDirectories(project.resolve(".jk"));
        Files.writeString(
                project.resolve("jk/after-resources.groovy"),
                "outDir.resolve('which.txt').toFile().text = 'visible'\n");
        Files.writeString(
                project.resolve(".jk/after-resources.groovy"),
                "outDir.resolve('which.txt').toFile().text = 'hidden'\n");

        ActionCache ac = new ActionCache(new Cas(dir.resolve("cache/cas")), dir.resolve("cache/actions"));
        BuildLayout layout = BuildLayout.of(project, JkBuildParser.parse(project.resolve("jk.toml")));
        Path classes = layout.classesDir();
        Files.createDirectories(classes);

        assertTrue(BuildLogicSupport.run(project, layout, ac, classes, s -> {}));
        assertEquals("visible", Files.readString(classes.resolve("which.txt")).trim());
    }

    @Test
    void logic_path_override(@TempDir Path dir) throws Exception {
        Path project = scaffold(dir);
        Files.writeString(project.resolve("jk.toml"), """
                group = "t"
                name = "t"
                version = "0.0.1"
                jdk = 25
                [build]
                logic = "custom-logic"
                """);
        Files.createDirectories(project.resolve("custom-logic"));
        writeLineCountGroovy(project.resolve("custom-logic/after-resources.groovy"));
        assertTrue(Files.notExists(project.resolve(".jk")));
        runTwice(project, dir.resolve("cache"));
    }

    @Test
    void two_scripts_are_independently_cached(@TempDir Path dir) throws Exception {
        Path project = scaffold(dir);
        Files.createDirectories(project.resolve(".jk"));
        writeLineCountGroovy(project.resolve(".jk/after-resources.groovy"));
        writeStampGroovy(project.resolve(".jk/after-resources-stamp.groovy"));

        ActionCache ac = new ActionCache(new Cas(dir.resolve("cache/cas")), dir.resolve("cache/actions"));
        BuildLayout layout = BuildLayout.of(project, JkBuildParser.parse(project.resolve("jk.toml")));
        Path classes = layout.classesDir();
        Files.createDirectories(classes);

        StringBuilder labels = new StringBuilder();
        assertTrue(BuildLogicSupport.run(
                project, layout, ac, classes, s -> labels.append(s).append(';')));
        assertTrue(Files.isRegularFile(classes.resolve("line-count.txt")));
        assertTrue(Files.isRegularFile(classes.resolve("stamp.txt")));
        assertTrue(labels.toString().contains("after-resources"), labels.toString());
        assertTrue(labels.toString().contains("after-resources-stamp"), labels.toString());

        Files.delete(classes.resolve("line-count.txt"));
        Files.delete(classes.resolve("stamp.txt"));
        labels.setLength(0);
        assertTrue(BuildLogicSupport.run(
                project, layout, ac, classes, s -> labels.append(s).append(';')));
        assertTrue(labels.toString().contains("cache hit"), labels.toString());
        assertEquals(2, labels.toString().split("cache hit", -1).length - 1);
    }

    @Test
    void scripts_run_at_two_anchors(@TempDir Path dir) throws Exception {
        Path project = scaffold(dir);
        Files.createDirectories(project.resolve(".jk"));
        Files.writeString(
                project.resolve(".jk/before-compile.groovy"),
                "outDir.resolve('before-compile.txt').toFile().text = 'g'\n");
        Files.writeString(
                project.resolve(".jk/after-compile.groovy"),
                "outDir.resolve('after-compile.txt').toFile().text = 'c'\n");
        Files.writeString(
                project.resolve(".jk/before-package.groovy"),
                "outDir.resolve('before-package.txt').toFile().text = 'p'\n");

        ActionCache ac = new ActionCache(new Cas(dir.resolve("cache/cas")), dir.resolve("cache/actions"));
        BuildLayout layout = BuildLayout.of(project, JkBuildParser.parse(project.resolve("jk.toml")));
        Path classes = layout.classesDir();
        Files.createDirectories(classes);

        StringBuilder labels = new StringBuilder();
        assertTrue(BuildLogicSupport.run(
                project, layout, ac, classes, BuildLogicAnchor.BEFORE_COMPILE, s -> labels.append(s)
                        .append(';')));
        assertTrue(Files.isRegularFile(generated(layout, "before-compile", "before-compile.txt")));
        assertFalse(Files.exists(classes.resolve("before-compile.txt")), "codegen must not land in classes/");
        assertTrue(labels.toString().contains("before-compile"), labels.toString());
        assertEquals(BuildStage.GENERATE, BuildLogicAnchor.BEFORE_COMPILE.stage());

        labels.setLength(0);
        assertTrue(BuildLogicSupport.run(
                project, layout, ac, classes, BuildLogicAnchor.AFTER_COMPILE, s -> labels.append(s)
                        .append(';')));
        assertTrue(Files.isRegularFile(classes.resolve("after-compile.txt")));
        assertTrue(labels.toString().contains("after-compile"), labels.toString());

        labels.setLength(0);
        assertTrue(BuildLogicSupport.run(
                project, layout, ac, classes, BuildLogicAnchor.BEFORE_PACKAGE, s -> labels.append(s)
                        .append(';')));
        assertTrue(Files.isRegularFile(classes.resolve("before-package.txt")));
        assertTrue(labels.toString().contains("before-package"), labels.toString());

        Files.delete(classes.resolve("after-compile.txt"));
        labels.setLength(0);
        assertTrue(BuildLogicSupport.run(
                project, layout, ac, classes, BuildLogicAnchor.AFTER_COMPILE, s -> labels.append(s)
                        .append(';')));
        assertTrue(labels.toString().contains("cache hit"), labels.toString());
        assertTrue(Files.isRegularFile(classes.resolve("after-compile.txt")));
    }

    @Test
    void every_produced_file_survives_a_cache_hit(@TempDir Path dir) throws Exception {
        Path project = scaffold(dir);
        Files.createDirectories(project.resolve(".jk"));
        Files.writeString(project.resolve(".jk/after-compile.groovy"), """
                outDir.resolve('root.txt').toFile().text = 'r'
                new File(outDir.toFile(), 'nested/deep').mkdirs()
                new File(outDir.toFile(), 'nested/deep/leaf.txt').text = 'l'
                new File(outDir.toFile(), 'nested/mid.txt').text = 'm'
                """);

        ActionCache ac = new ActionCache(new Cas(dir.resolve("cache/cas")), dir.resolve("cache/actions"));
        BuildLayout layout = BuildLayout.of(project, JkBuildParser.parse(project.resolve("jk.toml")));
        Path classes = layout.classesDir();
        Files.createDirectories(classes);

        StringBuilder labels = new StringBuilder();
        assertTrue(BuildLogicSupport.run(
                project, layout, ac, classes, BuildLogicAnchor.AFTER_COMPILE, s -> labels.append(s)
                        .append(';')));
        assertFalse(labels.toString().contains("cache hit"), labels.toString());
        List<String> firstRun = mergedFiles(classes);
        assertEquals(List.of("nested/deep/leaf.txt", "nested/mid.txt", "root.txt"), firstRun);

        for (String rel : firstRun) Files.delete(classes.resolve(rel));
        labels.setLength(0);
        assertTrue(BuildLogicSupport.run(
                project, layout, ac, classes, BuildLogicAnchor.AFTER_COMPILE, s -> labels.append(s)
                        .append(';')));
        assertTrue(labels.toString().contains("cache hit"), labels.toString());
        assertEquals(firstRun, mergedFiles(classes), "a cache hit must replay every produced file");
    }

    @Test
    void multiple_anchors_in_one_build_share_a_single_project_hash(@TempDir Path dir) throws Exception {
        Path project = scaffold(dir);
        Files.createDirectories(project.resolve(".jk"));
        Files.writeString(
                project.resolve(".jk/before-compile.groovy"),
                "outDir.resolve('before-compile.txt').toFile().text = 'g'\n");
        Files.writeString(
                project.resolve(".jk/after-compile.groovy"),
                "outDir.resolve('after-compile.txt').toFile().text = 'c'\n");
        Files.writeString(
                project.resolve(".jk/before-package.groovy"),
                "outDir.resolve('before-package.txt').toFile().text = 'p'\n");

        ActionCache ac = new ActionCache(new Cas(dir.resolve("cache/cas")), dir.resolve("cache/actions"));
        BuildLayout layout = BuildLayout.of(project, JkBuildParser.parse(project.resolve("jk.toml")));
        Path classes = layout.classesDir();
        Files.createDirectories(classes);

        int before = BuildLogicSupport.PROJECT_INPUT_TOKENS_CALLS_FOR_TESTS.get();
        var sharedTokens = new AtomicReference<List<String>>();
        assertTrue(BuildLogicSupport.run(
                project, layout, ac, classes, BuildLogicAnchor.BEFORE_COMPILE, s -> {}, sharedTokens));
        assertTrue(BuildLogicSupport.run(
                project, layout, ac, classes, BuildLogicAnchor.AFTER_COMPILE, s -> {}, sharedTokens));
        assertTrue(BuildLogicSupport.run(
                project, layout, ac, classes, BuildLogicAnchor.BEFORE_PACKAGE, s -> {}, sharedTokens));
        int after = BuildLogicSupport.PROJECT_INPUT_TOKENS_CALLS_FOR_TESTS.get();

        assertEquals(
                1, after - before, "three anchors sharing one reference must hash the project once, not three times");
        assertTrue(Files.isRegularFile(generated(layout, "before-compile", "before-compile.txt")));
        assertTrue(Files.isRegularFile(classes.resolve("after-compile.txt")));
        assertTrue(Files.isRegularFile(classes.resolve("before-package.txt")));
    }

    @Test
    void a_product_source_edit_invalidates_a_task_that_reads_the_project(@TempDir Path dir) throws Exception {
        Path project = scaffold(dir);
        Files.createDirectories(project.resolve(".jk"));
        writeLineCountGroovy(project.resolve(".jk/after-compile.groovy"));

        ActionCache ac = new ActionCache(new Cas(dir.resolve("cache/cas")), dir.resolve("cache/actions"));
        BuildLayout layout = BuildLayout.of(project, JkBuildParser.parse(project.resolve("jk.toml")));
        Path classes = layout.classesDir();
        Files.createDirectories(classes);

        assertTrue(BuildLogicSupport.run(project, layout, ac, classes, BuildLogicAnchor.AFTER_COMPILE, s -> {}));
        String first = Files.readString(classes.resolve("line-count.txt")).trim();

        Files.writeString(project.resolve("src/main/java/demo/More.java"), "package demo;\npublic class More {\n}\n");
        StringBuilder labels = new StringBuilder();
        assertTrue(BuildLogicSupport.run(
                project, layout, ac, classes, BuildLogicAnchor.AFTER_COMPILE, s -> labels.append(s)
                        .append(';')));

        assertFalse(labels.toString().contains("cache hit"), labels.toString());
        assertNotEquals(
                first, Files.readString(classes.resolve("line-count.txt")).trim());
    }

    @Test
    void a_jk_toml_edit_invalidates_a_task_that_reads_the_project_file(@TempDir Path dir) throws Exception {
        Path project = scaffold(dir);
        Files.createDirectories(project.resolve(".jk"));
        Files.writeString(project.resolve(".jk/after-compile.groovy"), """
                outDir.resolve('version.txt').toFile().text = projectDir.resolve('jk.toml').toFile().text
                """);

        ActionCache ac = new ActionCache(new Cas(dir.resolve("cache/cas")), dir.resolve("cache/actions"));
        BuildLayout layout = BuildLayout.of(project, JkBuildParser.parse(project.resolve("jk.toml")));
        Path classes = layout.classesDir();
        Files.createDirectories(classes);

        assertTrue(BuildLogicSupport.run(project, layout, ac, classes, BuildLogicAnchor.AFTER_COMPILE, s -> {}));
        String first = Files.readString(classes.resolve("version.txt"));

        Files.writeString(project.resolve("jk.toml"), """
                group = "t"
                name = "t"
                version = "0.0.2"
                jdk = 25
                """);
        StringBuilder labels = new StringBuilder();
        assertTrue(BuildLogicSupport.run(
                project, layout, ac, classes, BuildLogicAnchor.AFTER_COMPILE, s -> labels.append(s)
                        .append(';')));

        assertFalse(labels.toString().contains("cache hit"), labels.toString());
        assertNotEquals(first, Files.readString(classes.resolve("version.txt")));
    }

    @Test
    void logic_off_skips_even_if_jk_exists(@TempDir Path dir) throws Exception {
        Path project = dir.resolve("proj");
        Files.createDirectories(project.resolve(".jk"));
        Files.writeString(project.resolve("jk.toml"), """
                group = "t"
                name = "t"
                version = "0.0.1"
                jdk = 25
                [build]
                logic = "off"
                """);
        Files.writeString(
                project.resolve(".jk/after-resources.groovy"), "outDir.resolve('x.txt').toFile().text = 'x'\n");
        assertTrue(BuildLogicToml.resolve(project).isEmpty());
    }

    @Test
    void compiled_java_under_jk_is_rejected(@TempDir Path dir) throws Exception {
        Path project = scaffold(dir);
        Files.createDirectories(project.resolve(".jk"));
        Files.writeString(
                project.resolve(".jk/after-resources.groovy"), "outDir.resolve('x.txt').toFile().text = 'x'\n");
        Files.writeString(project.resolve(".jk/X.java"), "class X {}");

        ActionCache ac = new ActionCache(new Cas(dir.resolve("cache/cas")), dir.resolve("cache/actions"));
        BuildLayout layout = BuildLayout.of(project, JkBuildParser.parse(project.resolve("jk.toml")));
        Path classes = layout.classesDir();
        Files.createDirectories(classes);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> BuildLogicSupport.run(project, layout, ac, classes, BuildLogicAnchor.AFTER_RESOURCES, s -> {}));
        assertTrue(ex.getMessage().contains("stem scripts only"), ex.getMessage());
        assertTrue(ex.getMessage().contains("X.java"), ex.getMessage());
    }

    /**
     * The workspace root's anchor runs where a module anchor could not: a directory with no
     * sources, no classes tree, and nothing to merge into.
     */
    @Test
    void workspace_root_anchor_runs_with_no_sources_and_merges_nothing(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("ws");
        Files.createDirectories(root.resolve(".jk"));
        Files.writeString(root.resolve("jk.toml"), """
                group = "t"
                name = "ws"
                version = "0.0.1"
                jdk = 25

                [workspace]
                modules = []
                """);
        Files.writeString(
                root.resolve(".jk/after-build.groovy"), "outDir.resolve('verdict.txt').toFile().text = 'clean'\n");

        ActionCache ac = new ActionCache(new Cas(dir.resolve("cache/cas")), dir.resolve("cache/actions"));
        BuildLayout layout = BuildLayout.of(root, JkBuildParser.parse(root.resolve("jk.toml")));

        StringBuilder labels = new StringBuilder();
        assertTrue(BuildLogicSupport.run(
                root,
                layout,
                ac, /* classesDir */
                null,
                BuildLogicAnchor.AFTER_BUILD,
                s -> labels.append(s).append(';')));

        Path out = layout.generatedSourcesDir("jk-logic-out-after-build").resolve("verdict.txt");
        assertTrue(Files.isRegularFile(out), "expected the root script's own output, got labels: " + labels);
        assertEquals("clean", Files.readString(out).trim());
    }

    @Test
    void a_module_stem_at_the_workspace_root_is_rejected(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("ws");
        Files.createDirectories(root.resolve(".jk"));
        Files.writeString(root.resolve("jk.toml"), """
                group = "t"
                name = "ws"
                version = "0.0.1"
                jdk = 25

                [workspace]
                modules = []
                """);
        Files.writeString(root.resolve(".jk/before-compile.groovy"), "// nothing to be before\n");

        ActionCache ac = new ActionCache(new Cas(dir.resolve("cache/cas")), dir.resolve("cache/actions"));
        BuildLayout layout = BuildLayout.of(root, JkBuildParser.parse(root.resolve("jk.toml")));

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> BuildLogicSupport.run(root, layout, ac, null, BuildLogicAnchor.AFTER_BUILD, s -> {}));
        assertTrue(ex.getMessage().contains("before-compile.groovy"), ex.getMessage());
        assertTrue(ex.getMessage().contains("after-build"), ex.getMessage());
    }

    @Test
    void the_root_stem_inside_a_module_is_rejected(@TempDir Path dir) throws Exception {
        Path project = scaffold(dir);
        Files.createDirectories(project.resolve(".jk"));
        Files.writeString(project.resolve(".jk/after-build.groovy"), "// wrong scope\n");

        ActionCache ac = new ActionCache(new Cas(dir.resolve("cache/cas")), dir.resolve("cache/actions"));
        BuildLayout layout = BuildLayout.of(project, JkBuildParser.parse(project.resolve("jk.toml")));
        Path classes = layout.classesDir();
        Files.createDirectories(classes);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> BuildLogicSupport.run(project, layout, ac, classes, BuildLogicAnchor.AFTER_RESOURCES, s -> {}));
        assertTrue(ex.getMessage().contains("after-build.groovy"), ex.getMessage());
        assertTrue(ex.getMessage().contains("before-compile"), ex.getMessage());
    }

    /**
     * A check produces nothing, and "these inputs are clean" is its whole result. Before an
     * empty outDir meant no record at all, so such a script re-ran on every build forever — and the
     * only way to get caching was to fabricate an output nobody reads.
     */
    @Test
    void a_script_that_writes_nothing_caches_its_verdict(@TempDir Path dir) throws Exception {
        Path project = scaffold(dir);
        Files.createDirectories(project.resolve(".jk"));
        Path ran = dir.resolve("ran.log");
        Files.writeString(
                project.resolve(".jk/after-resources.groovy"),
                "new File('" + ran.toString().replace("\\", "\\\\") + "').append('x')\n");

        ActionCache ac = new ActionCache(new Cas(dir.resolve("cache/cas")), dir.resolve("cache/actions"));
        BuildLayout layout = BuildLayout.of(project, JkBuildParser.parse(project.resolve("jk.toml")));
        Path classes = layout.classesDir();
        Files.createDirectories(classes);

        StringBuilder labels = new StringBuilder();
        assertTrue(BuildLogicSupport.run(
                project, layout, ac, classes, s -> labels.append(s).append(';')));
        assertEquals(1, Files.readString(ran).length(), "first build runs it");

        labels.setLength(0);
        assertTrue(BuildLogicSupport.run(
                project, layout, ac, classes, s -> labels.append(s).append(';')));
        assertEquals(1, Files.readString(ran).length(), "identical inputs must not re-run it");
        assertTrue(labels.toString().contains("cache hit"), labels.toString());

        // A test source is an input too: the anchor runs after MAIN compile, but the script can
        // read the whole module, so the key covers the whole module.
        Files.createDirectories(project.resolve("src/test/java/demo"));
        Files.writeString(project.resolve("src/test/java/demo/AppTest.java"), "package demo; class AppTest {}\n");
        assertTrue(BuildLogicSupport.run(project, layout, ac, classes, s -> {}));
        assertEquals(2, Files.readString(ran).length(), "a changed test source re-runs it");

        // …and so is the script itself.
        Files.writeString(
                project.resolve(".jk/after-resources.groovy"),
                "new File('" + ran.toString().replace("\\", "\\\\") + "').append('y')\n");
        assertTrue(BuildLogicSupport.run(project, layout, ac, classes, s -> {}));
        assertEquals(3, Files.readString(ran).length(), "an edited script re-runs it");
    }

    /** Only success is a verdict. A red script must go red again, not replay its own failure. */
    @Test
    void a_failing_script_is_not_cached(@TempDir Path dir) throws Exception {
        Path project = scaffold(dir);
        Files.createDirectories(project.resolve(".jk"));
        Files.writeString(project.resolve(".jk/after-resources.groovy"), "throw new IllegalStateException('nope')\n");

        ActionCache ac = new ActionCache(new Cas(dir.resolve("cache/cas")), dir.resolve("cache/actions"));
        BuildLayout layout = BuildLayout.of(project, JkBuildParser.parse(project.resolve("jk.toml")));
        Path classes = layout.classesDir();
        Files.createDirectories(classes);

        for (int i = 0; i < 2; i++) {
            assertThrows(
                    IllegalStateException.class, () -> BuildLogicSupport.run(project, layout, ac, classes, s -> {}));
        }
    }

    /**
     * A workspace-root check reads every member, so its key has to as well. Keying it on the root
     * directory alone would replay a stale verdict the moment any member changed.
     */
    @Test
    void a_root_script_re_runs_when_any_member_changes(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("ws");
        Files.createDirectories(root.resolve(".jk"));
        Files.writeString(root.resolve("jk.toml"), """
                group = "t"
                name = "ws"
                version = "0.0.1"
                jdk = 25

                [workspace]
                modules = ["core"]
                """);
        Files.createDirectories(root.resolve("core/src/main/java/demo"));
        Files.writeString(root.resolve("core/jk.toml"), """
                group = "t"
                name = "core"
                version = "0.0.1"
                jdk = 25
                """);
        Files.writeString(root.resolve("core/src/main/java/demo/A.java"), "package demo; class A {}\n");
        Path ran = dir.resolve("root-ran.log");
        Files.writeString(
                root.resolve(".jk/after-build.groovy"),
                "new File('" + ran.toString().replace("\\", "\\\\") + "').append('x')\n");

        ActionCache ac = new ActionCache(new Cas(dir.resolve("cache/cas")), dir.resolve("cache/actions"));
        BuildLayout layout = BuildLayout.of(root, JkBuildParser.parse(root.resolve("jk.toml")));

        assertTrue(BuildLogicSupport.run(root, layout, ac, null, BuildLogicAnchor.AFTER_BUILD, s -> {}));
        assertEquals(1, Files.readString(ran).length());

        assertTrue(BuildLogicSupport.run(root, layout, ac, null, BuildLogicAnchor.AFTER_BUILD, s -> {}));
        assertEquals(1, Files.readString(ran).length(), "a no-op workspace must not re-run the root script");

        Files.writeString(root.resolve("core/src/main/java/demo/A.java"), "package demo; class A { int x; }\n");
        assertTrue(BuildLogicSupport.run(root, layout, ac, null, BuildLogicAnchor.AFTER_BUILD, s -> {}));
        assertEquals(2, Files.readString(ran).length(), "a changed member must re-run the root script");

        // A workspace is more than the union of its members' source roots. The check that motivated
        // this reads baselines and build scripts at the root, none of which belongs to any member.
        Files.writeString(root.resolve("some-baseline.txt"), "42\n");
        assertTrue(BuildLogicSupport.run(root, layout, ac, null, BuildLogicAnchor.AFTER_BUILD, s -> {}));
        assertEquals(3, Files.readString(ran).length(), "a changed non-member file must re-run it too");

        // …but build output must not, or the key would be a function of its own result.
        Files.createDirectories(root.resolve("target/core"));
        Files.writeString(root.resolve("target/core/A.class"), "bytes\n");
        assertTrue(BuildLogicSupport.run(root, layout, ac, null, BuildLogicAnchor.AFTER_BUILD, s -> {}));
        assertEquals(3, Files.readString(ran).length(), "target/ is output, not input");
    }

    @Test
    void gate_stem_runs_at_a_workspace_root(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("ws");
        Files.createDirectories(root.resolve(".jk"));
        Files.writeString(root.resolve("jk.toml"), """
                group = "t"
                name = "ws"
                version = "0.0.1"
                jdk = 25

                [workspace]
                modules = []
                """);
        Files.writeString(root.resolve(".jk/guard.groovy"), "outDir.resolve('verdict.txt').toFile().text = 'gate'\n");

        ActionCache ac = new ActionCache(new Cas(dir.resolve("cache/cas")), dir.resolve("cache/actions"));
        BuildLayout layout = BuildLayout.of(root, JkBuildParser.parse(root.resolve("jk.toml")));

        assertTrue(BuildLogicSupport.run(root, layout, ac, null, BuildLogicAnchor.GUARD, s -> {}));
        Path out = layout.generatedSourcesDir("jk-logic-out-gate").resolve("verdict.txt");
        assertEquals("guard", Files.readString(out).trim());
    }

    @Test
    void gate_stem_inside_a_member_is_rejected(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("ws");
        Path core = root.resolve("core");
        Files.createDirectories(core.resolve(".jk"));
        Files.createDirectories(core.resolve("src/main/java/demo"));
        Files.writeString(root.resolve("jk.toml"), """
                group = "t"
                name = "ws"
                version = "0.0.1"
                jdk = 25

                [workspace]
                modules = ["core"]
                """);
        Files.writeString(core.resolve("jk.toml"), """
                group = "t"
                name = "core"
                version = "0.0.1"
                jdk = 25
                """);
        Files.writeString(core.resolve("src/main/java/demo/A.java"), "package demo; class A {}\n");
        Files.writeString(core.resolve(".jk/guard.groovy"), "// wrong scope\n");

        ActionCache ac = new ActionCache(new Cas(dir.resolve("cache/cas")), dir.resolve("cache/actions"));
        BuildLayout layout = BuildLayout.of(core, JkBuildParser.parse(core.resolve("jk.toml")));
        Path classes = layout.classesDir();
        Files.createDirectories(classes);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> BuildLogicSupport.run(core, layout, ac, classes, BuildLogicAnchor.AFTER_RESOURCES, s -> {}));
        assertTrue(ex.getMessage().contains("guard.groovy"), ex.getMessage());
        assertTrue(ex.getMessage().contains("module"), ex.getMessage());
    }

    @Test
    void gate_on_a_standalone_is_legal_and_does_not_run_on_module_anchors(@TempDir Path dir) throws Exception {
        Path project = scaffold(dir);
        Files.createDirectories(project.resolve(".jk"));
        Path ran = dir.resolve("gate-ran.log");
        Files.writeString(
                project.resolve(".jk/guard.groovy"),
                "new File('" + ran.toString().replace("\\", "\\\\") + "').append('x')\n");

        ActionCache ac = new ActionCache(new Cas(dir.resolve("cache/cas")), dir.resolve("cache/actions"));
        BuildLayout layout = BuildLayout.of(project, JkBuildParser.parse(project.resolve("jk.toml")));
        Path classes = layout.classesDir();
        Files.createDirectories(classes);

        assertTrue(BuildLogicSupport.run(project, layout, ac, classes, BuildLogicAnchor.AFTER_RESOURCES, s -> {}));
        assertFalse(Files.exists(ran), "inner module anchors must not run gate");

        assertTrue(BuildLogicSupport.run(project, layout, ac, null, BuildLogicAnchor.GUARD, s -> {}));
        assertEquals(1, Files.readString(ran).length());
        assertTrue(BuildLogicSupport.run(project, layout, ac, null, BuildLogicAnchor.GUARD, s -> {}));
        assertEquals(1, Files.readString(ran).length(), "unchanged tree caches the gate verdict");
    }

    @Test
    void a_groovy_syntax_error_gets_one_prefix_and_the_dump_verbatim() {
        // What the fork host throws for a syntax error: the compiler dump, verbatim (its second
        // line names file and line — that is groovyc's own shape, not jk's).
        String dump = "org.codehaus.groovy.control.MultipleCompilationErrorsException: startup failed:\n"
                + ".jk/before-compile.groovy: 2: Unexpected input: '(' @ line 2, column 1.\n"
                + "1 error";
        IllegalStateException wrapped =
                BuildLogicSupport.scriptFailure(Path.of(".jk/before-compile.groovy"), new IllegalStateException(dump));

        assertEquals("[build] logic script before-compile.groovy failed:\n" + dump, wrapped.getMessage());
        RuntimeException rendered = BuildLogicSupport.taskFailure("before-compile", wrapped);
        assertEquals(wrapped.getMessage(), rendered.getMessage(), "the task layer adds no second prefix");
        assertEquals(1, countOccurrences(rendered.getMessage(), "[build] logic"));
    }

    @Test
    void a_kts_compile_error_leads_with_its_own_file_and_line() {
        // The .kts host hands back the compiler output bare; the one prefix names the file, and
        // the very next line is the script's own file:line (made it trustworthy).
        String dump = "before-compile.kts:5: Unresolved reference 'thisSymbolDoesNotExist'.";
        IllegalStateException wrapped =
                BuildLogicSupport.scriptFailure(Path.of(".jk/before-compile.kts"), new IllegalStateException(dump));

        String[] lines = wrapped.getMessage().split("\n", 2);
        assertEquals("[build] logic script before-compile.kts failed:", lines[0]);
        assertEquals(dump, lines[1], "compiler output verbatim, location first in the body");
        assertEquals(
                wrapped.getMessage(),
                BuildLogicSupport.taskFailure("before-compile", wrapped).getMessage());
    }

    @Test
    void a_host_lifecycle_failure_keeps_its_own_single_prefix() {
        IllegalStateException died =
                new IllegalStateException("[build] logic: the .kts host died running guard.kts (exit 137)");
        IllegalStateException wrapped = BuildLogicSupport.scriptFailure(Path.of(".jk/guard.kts"), died);
        assertEquals(died.getMessage(), wrapped.getMessage());
        assertEquals(
                1,
                countOccurrences(BuildLogicSupport.taskFailure("guard", wrapped).getMessage(), "[build] logic"));
    }

    @Test
    void a_non_logic_failure_still_names_the_task() {
        RuntimeException rendered =
                BuildLogicSupport.taskFailure("before-compile", new IllegalStateException("disk full"));
        assertEquals("[build] logic task before-compile failed: disk full", rendered.getMessage());
    }

    private static int countOccurrences(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) n++;
        return n;
    }

    private static Path scaffold(Path dir) throws Exception {
        Path project = dir.resolve("proj");
        Files.createDirectories(project.resolve("src/main/java/demo"));
        Files.writeString(project.resolve("jk.toml"), """
                group = "t"
                name = "t"
                version = "0.0.1"
                jdk = 25
                """);
        Files.writeString(
                project.resolve("src/main/java/demo/App.java"),
                "package demo; public class App { public static void main(String[] a) {} }\n");
        return project;
    }
}
