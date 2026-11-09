// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.watch;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.watch.AppWatchLoop.Pass;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What {@code jk dev} runs for a change: a manifest edit rebuilds, a source edit recompiles, and an
 * edit to resources alone runs the compile goal's resource copy, never a package build.
 */
class WatchPassTest {

    @Test
    void a_manifest_change_rebuilds_whatever_else_changed_with_it() {
        assertThat(Pass.of(new SourceWatch.Changes(false, false, true))).isEqualTo(Pass.REBUILD);
        assertThat(Pass.of(new SourceWatch.Changes(true, true, true))).isEqualTo(Pass.REBUILD);
    }

    @Test
    void a_source_change_recompiles_even_beside_a_resource_change() {
        assertThat(Pass.of(new SourceWatch.Changes(true, false, false))).isEqualTo(Pass.RECOMPILE);
        assertThat(Pass.of(new SourceWatch.Changes(true, true, false))).isEqualTo(Pass.RECOMPILE);
    }

    @Test
    void a_resource_change_alone_copies_resources() {
        assertThat(Pass.of(new SourceWatch.Changes(false, true, false))).isEqualTo(Pass.COPY_RESOURCES);
    }

    @Test
    void the_watch_classifies_each_edit_by_what_it_touched(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(project.resolve("jk.toml"), "name = \"app\"\n");
        Path src = project.resolve("src");
        Path resources = Files.createDirectories(src.resolve("main/resources"));
        Path java = Files.createDirectories(src.resolve("main/java/ex"));
        Path proto = Files.createDirectories(src.resolve("main/proto"));

        try (SourceWatch watch = SourceWatch.open(project, List.of(src), 200)) {
            assertThat(pass(watch, resources, "application.yaml"))
                    .as("a resource")
                    .isEqualTo(Pass.COPY_RESOURCES);
            assertThat(pass(watch, proto, "greet.proto"))
                    .as("a generator's input outside resources: the compile goal runs the generator")
                    .isEqualTo(Pass.COPY_RESOURCES);
            assertThat(pass(watch, java, "Main.java")).as("a source").isEqualTo(Pass.RECOMPILE);
            assertThat(pass(watch, project, "jk.toml")).as("the manifest").isEqualTo(Pass.REBUILD);
            assertThat(watch.classify(project, project.resolve("README.md")))
                    .as("the project directory itself, outside the watched roots")
                    .isNull();
        }
    }

    @Test
    void an_edit_on_disk_reaches_the_watch(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(project.resolve("jk.toml"), "name = \"app\"\n");
        Path java = Files.createDirectories(project.resolve("src/main/java/ex"));

        try (SourceWatch watch = SourceWatch.open(project, List.of(project.resolve("src")), 200)) {
            Files.writeString(java.resolve("Main.java"), "package ex; class Main {}\n");
            assertThat(Pass.of(watch.pollChange(30, TimeUnit.SECONDS).orElseThrow()))
                    .isEqualTo(Pass.RECOMPILE);
        }
    }

    private static Pass pass(SourceWatch watch, Path dir, String file) throws Exception {
        Path changed = dir.resolve(file);
        Files.writeString(changed, "x\n");
        return Pass.of(Objects.requireNonNull(watch.classify(dir, changed), file));
    }
}
