// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.RequestScope;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.FreshnessStamp;
import cc.jumpkick.task.IoLedger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The outputs-missing probes. Presence must see class files at ANY package depth: a depth-capped
 * walk missed everything under three segments, so all {@code cc.jumpkick.*} modules read as
 * "outputs missing" and every fully-cached workspace was re-run on every build. Wholeness is
 * judged against the compile record: a tree that lost an output the record owns is missing an
 * output, whatever else it holds.
 */
class ModuleOutputsTest {

    @Test
    void deep_packages_count_as_content(@TempDir Path dir) throws Exception {
        Path classes = dir.resolve("classes/main");
        Path deep = Files.createDirectories(classes.resolve("cc/jumpkick/jsonl/inner"));
        Files.writeString(deep.resolve("Deep.class"), "x");
        assertThat(ModuleOutputs.classesDirHasContent(classes))
                .as("cc/jumpkick/jsonl/inner/Deep.class is content, whatever its depth")
                .isTrue();
    }

    @Test
    void a_tree_is_whole_only_while_every_output_its_record_owns_is_present(@TempDir Path dir) throws Exception {
        Cas cas = new Cas(dir.resolve("cas"));
        ActionCache ac = new ActionCache(cas, dir.resolve("actions"));
        String sha = cas.hashFromPath(cas.put("bytes".getBytes(StandardCharsets.UTF_8)))
                .orElseThrow();
        ac.storeWithOutputs("compile-main@x", "key-a", Map.of(), Map.of("p/A.class", sha, "p/q/B.class", sha));
        Path classes = Files.createDirectories(dir.resolve("classes/main"));
        Files.createDirectories(classes.resolve("p/q"));
        Files.writeString(classes.resolve("p/A.class"), "bytes");
        Files.writeString(classes.resolve("p/q/B.class"), "bytes");

        assertThat(ModuleOutputs.compileOutputsOnDisk(ac, "key-a", classes)).isTrue();
        Files.writeString(classes.resolve("p/Extra.class"), "unowned");
        assertThat(ModuleOutputs.compileOutputsOnDisk(ac, "key-a", classes))
                .as("extra files are the restore's prune, not this probe's concern")
                .isTrue();

        Files.delete(classes.resolve("p/q/B.class"));
        assertThat(ModuleOutputs.compileOutputsOnDisk(ac, "key-a", classes))
                .as("a strict subset of the record's outputs")
                .isFalse();
        assertThat(ModuleOutputs.compileOutputsOnDisk(ac, "no-such-key", classes))
                .as("no record: nothing to hold the tree against")
                .isTrue();
        assertThat(ModuleOutputs.compileOutputsOnDisk(ac, null, classes)).isTrue();

        // Through the stamp: the tree's own .jstamp names the key it was compiled under.
        FreshnessStamp.write(
                classes,
                BuildStamps.JAVA,
                "compile-main",
                "key-a",
                List.of(),
                FreshnessStamp.ClasspathTokens.of(List.of()),
                25,
                "");
        assertThat(ModuleOutputs.compileOutputsOnDisk(ac, classes)).isFalse();
        Files.writeString(classes.resolve("p/q/B.class"), "bytes");
        assertThat(ModuleOutputs.compileOutputsOnDisk(ac, classes)).isTrue();
    }

    @Test
    void a_whole_verdict_holds_for_the_request_and_a_missing_one_never_does(@TempDir Path dir) throws Exception {
        Cas cas = new Cas(dir.resolve("cas"));
        ActionCache ac = new ActionCache(cas, dir.resolve("actions"));
        String sha = cas.hashFromPath(cas.put("bytes".getBytes(StandardCharsets.UTF_8)))
                .orElseThrow();
        ac.storeWithOutputs("compile-main@x", "key-a", Map.of(), Map.of("p/A.class", sha, "p/q/B.class", sha));
        Path classes = Files.createDirectories(dir.resolve("classes/main"));
        Files.createDirectories(classes.resolve("p/q"));
        Files.writeString(classes.resolve("p/A.class"), "bytes");
        Files.writeString(classes.resolve("p/q/B.class"), "bytes");

        // Inside one request the first yes is the request's answer: the five arms that ask about
        // the same (key, tree) pair share it, so the record is read and the tree walked once.
        inRequest(() -> {
            assertThat(ModuleOutputs.compileOutputsOnDisk(ac, "key-a", classes)).isTrue();
            Files.delete(classes.resolve("p/q/B.class"));
            assertThat(ModuleOutputs.compileOutputsOnDisk(ac, "key-a", classes))
                    .as("a later arm of the same request gets the remembered yes, not a re-walk")
                    .isTrue();
        });

        // The next request probes afresh, and a no is never remembered: once the module's plan
        // restores the tree its later arms see the restored tree.
        inRequest(() -> {
            assertThat(ModuleOutputs.compileOutputsOnDisk(ac, "key-a", classes))
                    .as("a new request re-reads the tree")
                    .isFalse();
            Files.writeString(classes.resolve("p/q/B.class"), "bytes");
            assertThat(ModuleOutputs.compileOutputsOnDisk(ac, "key-a", classes))
                    .as("the restored tree is whole again within the same request")
                    .isTrue();
        });

        // Off a request every probe is live.
        Files.delete(classes.resolve("p/q/B.class"));
        assertThat(ModuleOutputs.compileOutputsOnDisk(ac, "key-a", classes)).isFalse();
    }

    private interface Body {
        void run() throws Exception;
    }

    /** Run {@code body} the way a real request runs: with an {@link IoLedger} opened around it. */
    private static void inRequest(Body body) throws Exception {
        IoLedger.open(new IoLedger());
        try {
            body.run();
        } finally {
            RequestScope.release();
            IoLedger.close();
        }
    }

    @Test
    void empty_or_classless_trees_are_not_content(@TempDir Path dir) throws Exception {
        Path classes = Files.createDirectories(dir.resolve("classes/main"));
        assertThat(ModuleOutputs.classesDirHasContent(classes)).isFalse();
        Files.createDirectories(classes.resolve("cc/jumpkick/only/dirs"));
        Files.writeString(classes.resolve("cc/jumpkick/only/dirs/notes.txt"), "not a class");
        assertThat(ModuleOutputs.classesDirHasContent(classes)).isFalse();
        assertThat(ModuleOutputs.classesDirHasContent(dir.resolve("absent"))).isFalse();
    }

    /**
     * A module with resources and no sources still has outputs a sibling reads: the classes tree
     * its resources are copied into is on every dependent's compile classpath, and its jar is what
     * their package steps read. Both gone while the resources stand is a missing output; a module
     * with neither sources nor resources asks for nothing.
     */
    @Test
    void a_resources_only_modules_tree_and_jar_are_outputs_too(@TempDir Path root) throws Exception {
        Path res = Files.createDirectories(root.resolve("res"));
        Files.writeString(res.resolve("jk.toml"), """
                group   = "com.example"
                name    = "res"
                version = "1.0.0"
                java    = 25
                """);
        JkBuild build = JkBuildParser.parse(res.resolve("jk.toml"));
        BuildLayout layout = BuildLayout.of(root, res, build);

        assertThat(ModuleOutputs.packageOutputsMissing(root, res, build))
                .as("no sources, no resources: nothing is produced, so nothing is missing")
                .isFalse();

        Files.createDirectories(res.resolve("src/main/resources"));
        Files.writeString(res.resolve("src/main/resources/hello.txt"), "hello");
        assertThat(ModuleOutputs.packageOutputsMissing(root, res, build))
                .as("resources with no classes tree and no jar")
                .isTrue();

        Files.createDirectories(layout.classesDir());
        Files.writeString(layout.classesDir().resolve("hello.txt"), "hello");
        assertThat(ModuleOutputs.packageOutputsMissing(root, res, build))
                .as("the copied tree without the jar")
                .isTrue();

        Files.createDirectories(layout.mainJar().getParent());
        Files.writeString(layout.mainJar(), "jar");
        assertThat(ModuleOutputs.packageOutputsMissing(root, res, build))
                .as("tree and jar present")
                .isFalse();

        Files.delete(layout.classesDir().resolve("hello.txt"));
        assertThat(ModuleOutputs.packageOutputsMissing(root, res, build))
                .as("an empty tree is a missing tree")
                .isTrue();
    }

    /**
     * The test view a tests-enabled build leaves: absent test classes for a module with tests, or
     * an absent fixtures tree for a module whose fixtures root holds sources, each read as a
     * missing output; a module with neither asks for nothing.
     */
    @Test
    void the_test_view_is_missing_when_its_trees_are_empty_and_something_would_fill_them(@TempDir Path root)
            throws Exception {
        Path lib = Files.createDirectories(root.resolve("lib"));
        Files.writeString(lib.resolve("jk.toml"), """
                group   = "com.example"
                name    = "lib"
                version = "1.0.0"
                java    = 25

                [test]
                fixtures = true
                """);
        JkBuild build = JkBuildParser.parse(lib.resolve("jk.toml"));
        BuildLayout layout = BuildLayout.of(root, lib, build);

        assertThat(ModuleOutputs.testViewMissing(layout, build, lib, () -> true))
                .as("a module with tests and no test classes on disk")
                .isTrue();
        assertThat(ModuleOutputs.testViewMissing(layout, build, lib, () -> false))
                .as("fixtures declared but the root holds no sources: nothing to restore")
                .isFalse();

        Files.createDirectories(lib.resolve("src/fixtures/java/com/example"));
        Files.writeString(lib.resolve("src/fixtures/java/com/example/Fx.java"), "package com.example; class Fx {}");
        assertThat(ModuleOutputs.testViewMissing(layout, build, lib, () -> false))
                .as("fixture sources with no fixtures tree")
                .isTrue();

        Path fixtures = Files.createDirectories(layout.testFixturesClassesDir().resolve("com/example"));
        Files.writeString(fixtures.resolve("Fx.class"), "x");
        Path tests = Files.createDirectories(layout.testClassesDir().resolve("com/example"));
        Files.writeString(tests.resolve("LibTest.class"), "x");
        assertThat(ModuleOutputs.testViewMissing(layout, build, lib, () -> {
                    throw new AssertionError("a present test classes tree is not held against the sources");
                }))
                .isFalse();
    }
}
