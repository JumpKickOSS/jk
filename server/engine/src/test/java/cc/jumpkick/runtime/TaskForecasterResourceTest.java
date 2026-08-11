// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.task.ClasspathFingerprint;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Clean-skip must notice a resource tree that drifted from its copy in the output dir. */
class TaskForecasterResourceTest {

    @Test
    void in_sync_copy_is_clean(@TempDir Path tmp) throws Exception {
        Path res = Files.createDirectories(tmp.resolve("res"));
        Path out = Files.createDirectories(tmp.resolve("out"));
        Files.writeString(res.resolve("app.properties"), "a=1\n");
        Files.writeString(out.resolve("app.properties"), "a=1\n");
        assertThat(TaskForecaster.resourcesOutOfSync(res, out)).isFalse();
    }

    @Test
    void missing_copy_is_dirty(@TempDir Path tmp) throws Exception {
        Path res = Files.createDirectories(tmp.resolve("res"));
        Path out = Files.createDirectories(tmp.resolve("out"));
        Files.writeString(res.resolve("new.txt"), "x\n");
        assertThat(TaskForecaster.resourcesOutOfSync(res, out)).isTrue();
    }

    @Test
    void size_change_is_dirty(@TempDir Path tmp) throws Exception {
        Path res = Files.createDirectories(tmp.resolve("res"));
        Path out = Files.createDirectories(tmp.resolve("out"));
        Files.writeString(res.resolve("f"), "longer content\n");
        Files.writeString(out.resolve("f"), "short\n");
        assertThat(TaskForecaster.resourcesOutOfSync(res, out)).isTrue();
    }

    @Test
    void same_size_newer_source_with_different_bytes_is_dirty(@TempDir Path tmp) throws Exception {
        Path res = Files.createDirectories(tmp.resolve("res"));
        Path out = Files.createDirectories(tmp.resolve("out"));
        Files.writeString(out.resolve("f"), "a=1\n");
        Files.writeString(res.resolve("f"), "a=2\n");
        Files.setLastModifiedTime(out.resolve("f"), FileTime.fromMillis(1_000_000));
        assertThat(TaskForecaster.resourcesOutOfSync(res, out)).isTrue();
    }

    @Test
    void nested_dirs_and_absent_resource_root_are_handled(@TempDir Path tmp) throws Exception {
        Path res = tmp.resolve("res");
        Path out = Files.createDirectories(tmp.resolve("out"));
        assertThat(TaskForecaster.resourcesOutOfSync(res, out)).isFalse(); // no resources at all
        Files.createDirectories(res.resolve("sub"));
        Files.writeString(res.resolve("sub/deep.txt"), "d\n");
        assertThat(TaskForecaster.resourcesOutOfSync(res, out)).isTrue();
        Files.createDirectories(out.resolve("sub"));
        Files.writeString(out.resolve("sub/deep.txt"), "d\n");
        assertThat(TaskForecaster.resourcesOutOfSync(res, out)).isFalse();
    }

    @Test
    void projected_package_token_follows_source_resources_not_stale_classes(@TempDir Path tmp) throws Exception {
        // Stale classes/resources + newer source resources: raw classes token differs from the
        // post-copy projection (what live package-jar will hash after copy-resources).
        Path module = tmp.resolve("mod");
        Files.createDirectories(module.resolve("src/main/java"));
        Files.createDirectories(module.resolve("src/main/resources"));
        Files.writeString(
                module.resolve("jk.toml"),
                """
                [project]
                group = "ex"
                name = "mod"
                version = "1.0.0"
                java = 25
                """);
        JkBuild project = JkBuildParser.parse(module.resolve("jk.toml"));
        BuildLayout layout = BuildLayout.of(module, project);
        Path classes = Files.createDirectories(layout.classesDir());
        Files.writeString(classes.resolve("App.class"), "fake-class");
        Files.writeString(classes.resolve("app.properties"), "a=1\n"); // stale
        Files.writeString(module.resolve("src/main/resources/app.properties"), "a=2\n"); // source

        String raw = ClasspathFingerprint.entry(classes);
        String projected = TaskForecaster.classesTokenProjectedAfterResourceCopy(module, false, layout, project);
        assertThat(projected).isNotEqualTo(raw);
        // Projection equals fingerprinting classes after a faithful copy.
        Files.writeString(classes.resolve("app.properties"), "a=2\n");
        assertThat(ClasspathFingerprint.entry(classes)).isEqualTo(projected);
    }

    @Test
    void resource_only_drift_does_not_seed_compile_consumer_cascade() {
        // Producer: only copy-resources dirty, package CACHED — must not mark main-output dirty
        // for compile consumers (ETA was pricing full recompile+test for every dependent).
        var producer = new TaskForecast.Module(
                Path.of("/core"),
                "g:core",
                java.util.List.of(
                        new TaskForecast.Task("compile-main", TaskForecast.Status.CACHED, "", "abc"),
                        new TaskForecast.Task("package-jar", TaskForecast.Status.CACHED, "", "def"),
                        new TaskForecast.Task(
                                "copy-resources", TaskForecast.Status.RUN, "resources changed", null)),
                1,
                0,
                true,
                false);
        assertThat(producer.dirty()).isTrue(); // still schedules the producer (JK-1808)
        assertThat(TaskForecaster.seedsCompileConsumerCascade(producer)).isFalse();

        // Real package miss still seeds cascade.
        var repackage = new TaskForecast.Module(
                Path.of("/core"),
                "g:core",
                java.util.List.of(
                        new TaskForecast.Task("compile-main", TaskForecast.Status.CACHED, "", "abc"),
                        new TaskForecast.Task(
                                "package-jar", TaskForecast.Status.RUN, "repackage · resources changed", null),
                        new TaskForecast.Task(
                                "copy-resources", TaskForecast.Status.RUN, "resources changed", null)),
                1,
                0,
                true,
                false);
        assertThat(TaskForecaster.seedsCompileConsumerCascade(repackage)).isTrue();
    }
}
