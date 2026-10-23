// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.runtime.base.FilteredResources;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The forecast's run-tests key reads the resource roots the live run copies for the default
 * suite: {@code [resources] test-dirs} and the staged {@code test-filtered} roots. A key that left
 * them out would match the last green marker after an edit there, and the suite would not run.
 */
class ForecastTestResourceRootsTest {

    private static JkBuild project(Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "m"
                version = "1.0.0"
                java    = 25

                [resources]
                test-dirs     = ["fixtures"]
                test-filtered = ["templates"]
                """);
        Files.createDirectories(dir.resolve("src/test/java"));
        Files.writeString(dir.resolve("src/test/java/T.java"), "class T {}");
        Files.createDirectories(dir.resolve("fixtures"));
        Files.writeString(dir.resolve("fixtures/data.txt"), "one");
        Files.createDirectories(dir.resolve("templates"));
        Files.writeString(dir.resolve("templates/t.txt"), "v=${project.version}");
        return JkBuildParser.parse(dir.resolve("jk.toml"));
    }

    private static @Nullable String key(Path dir, JkBuild project) throws Exception {
        Path classes = Files.createDirectories(dir.resolve("target/classes"));
        return PlannerSupport.runTestsStampKey(dir, project, false, classes, dir.resolve("jk-lock.toml"), List.of());
    }

    @Test
    void an_edit_to_a_test_dirs_root_moves_the_forecast_key(@TempDir Path dir) throws Exception {
        JkBuild project = project(dir);
        BuildLayout layout = BuildLayout.of(dir, project);
        Set<String> unresolved = new TreeSet<>();
        FilteredResources.testRoots(dir, project, layout, unresolved);
        String before = key(dir, project);
        assertThat(before).isNotNull();

        Files.writeString(dir.resolve("fixtures/data.txt"), "two");

        assertThat(key(dir, project)).isNotEqualTo(before);
    }

    @Test
    void a_test_filtered_root_its_stage_no_longer_matches_is_forecast_to_run(@TempDir Path dir) throws Exception {
        JkBuild project = project(dir);
        BuildLayout layout = BuildLayout.of(dir, project);
        FilteredResources.testRoots(dir, project, layout, new TreeSet<>());
        assertThat(key(dir, project)).as("staged and current").isNotNull();

        Files.writeString(dir.resolve("templates/t.txt"), "v=${project.version}!");

        assertThat(key(dir, project))
                .as("the run restages it, so nothing prices it")
                .isNull();
    }
}
