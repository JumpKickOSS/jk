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
        assertEquals("generate", BuildLogicAnchor.BEFORE_COMPILE.stageWireName());

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
