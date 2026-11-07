// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.watch;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.watch.AppWatchLoop.Pass;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
            Files.writeString(resources.resolve("application.yaml"), "app:\n  greeting: Howdy\n");
            assertThat(pass(watch)).as("a resource").isEqualTo(Pass.COPY_RESOURCES);

            Files.writeString(proto.resolve("greet.proto"), "syntax = \"proto3\";\n");
            assertThat(pass(watch))
                    .as("a generator's input outside resources: the compile goal runs the generator")
                    .isEqualTo(Pass.COPY_RESOURCES);

            Files.writeString(java.resolve("Main.java"), "package ex; class Main {}\n");
            assertThat(pass(watch)).as("a source").isEqualTo(Pass.RECOMPILE);

            Files.writeString(project.resolve("jk.toml"), "name = \"app\"\nversion = \"1\"\n");
            assertThat(pass(watch)).as("the manifest").isEqualTo(Pass.REBUILD);
        }
    }

    private static Pass pass(SourceWatch watch) throws Exception {
        return Pass.of(watch.pollChange(30, TimeUnit.SECONDS).orElseThrow());
    }
}
