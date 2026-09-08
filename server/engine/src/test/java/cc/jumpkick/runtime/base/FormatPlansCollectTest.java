// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FormatPlansCollectTest {

    @Test
    void single_walk_finds_jvm_sources_and_skips_build_trees(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src/main/java");
        Path kt = tmp.resolve("src/main/kotlin");
        Path groovy = tmp.resolve("src/main/groovy");
        Path scala = tmp.resolve("src/main/scala");
        Path target = tmp.resolve("target/classes");
        Path build = tmp.resolve("build/generated");
        Files.createDirectories(src);
        Files.createDirectories(kt);
        Files.createDirectories(groovy);
        Files.createDirectories(scala);
        Files.createDirectories(target);
        Files.createDirectories(build);
        Path keepJava = src.resolve("Keep.java");
        Path keepKt = kt.resolve("Keep.kt");
        Path keepGroovy = groovy.resolve("Keep.groovy");
        Path keepScala = scala.resolve("Keep.scala");
        Files.writeString(keepJava, "class Keep {}");
        Files.writeString(keepKt, "class Keep");
        Files.writeString(keepGroovy, "class Keep {}");
        Files.writeString(keepScala, "class Keep");
        // Would be expensive to descend in a real repo — must not be collected.
        Files.writeString(target.resolve("Gen.java"), "class Gen {}");
        Files.writeString(build.resolve("Gen.kt"), "class Gen");
        Files.writeString(tmp.resolve("build.gradle"), "plugins { id 'java' }");

        FormatSources.CollectedSources found = FormatSources.collectSources(tmp);
        assertThat(found.javaFiles()).containsExactly(keepJava);
        assertThat(found.kotlinFiles()).containsExactly(keepKt);
        assertThat(found.groovyFiles()).containsExactly(keepGroovy);
        assertThat(found.scalaFiles()).containsExactly(keepScala);
        assertThat(found.total()).isEqualTo(4);
    }

    /**
     * A git worktree carries a {@code .git} pointer file, a nested clone a {@code .git} directory;
     * either holds another branch's sources, and neither is this root's to format or to report.
     */
    @Test
    void a_nested_checkout_is_not_collected_whatever_its_directory_is_called(@TempDir Path tmp) throws Exception {
        Path own = tmp.resolve("src/main/java");
        Files.createDirectories(own);
        Path keep = own.resolve("Keep.java");
        Files.writeString(keep, "class Keep {}");

        Path worktree = tmp.resolve(".worktrees/agent-a/src/main/java");
        Files.createDirectories(worktree);
        Files.writeString(tmp.resolve(".worktrees/agent-a/.git"), "gitdir: /elsewhere/.git/worktrees/agent-a\n");
        Files.writeString(worktree.resolve("Theirs.java"), "class Theirs {}");

        Path clone = tmp.resolve("vendor/other/src/main/java");
        Files.createDirectories(clone);
        Files.createDirectories(tmp.resolve("vendor/other/.git"));
        Files.writeString(clone.resolve("Cloned.java"), "class Cloned {}");

        assertThat(FormatSources.collectSources(tmp).javaFiles()).containsExactly(keep);
    }

    @Test
    void ancestor_named_build_does_not_hide_sources(@TempDir Path tmp) throws Exception {
        Path project = tmp.resolve("build/tmp/proj");
        Path src = project.resolve("src/main/java");
        Files.createDirectories(src);
        Path keep = src.resolve("Keep.java");
        Files.writeString(keep, "class Keep {}");
        assertThat(FormatSources.collectSources(project).javaFiles()).containsExactly(keep);
    }

    /**
     * The mtime/size freshness index is the outer filter — a recorded path is not sent to the
     * worker at all next run — so errors must not be recorded.
     */
    @Test
    void an_error_is_never_recorded_fresh() {
        assertThat(FormatWorker.recordsFreshness("error", false)).isFalse();
        assertThat(FormatWorker.recordsFreshness("error", true)).isFalse();
    }

    /** …and the statuses that were recorded before still are. */
    @Test
    void settled_files_are_still_recorded_fresh() {
        assertThat(FormatWorker.recordsFreshness("clean", true)).isTrue();
        assertThat(FormatWorker.recordsFreshness("clean", false)).isTrue();
        assertThat(FormatWorker.recordsFreshness("skipped", false)).isTrue();
        assertThat(FormatWorker.recordsFreshness("changed", false))
                .as("apply mode wrote the formatted bytes, so the file on disk is now clean")
                .isTrue();
        assertThat(FormatWorker.recordsFreshness("changed", true))
                .as("--check wrote nothing, so those bytes are still the unformatted ones")
                .isFalse();
    }
}
