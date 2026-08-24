// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FormatPlansCollectTest {

    @Test
    void single_walk_finds_java_and_kotlin_and_skips_build_trees(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src/main/java");
        Path kt = tmp.resolve("src/main/kotlin");
        Path target = tmp.resolve("target/classes");
        Path build = tmp.resolve("build/generated");
        Files.createDirectories(src);
        Files.createDirectories(kt);
        Files.createDirectories(target);
        Files.createDirectories(build);
        Path keepJava = src.resolve("Keep.java");
        Path keepKt = kt.resolve("Keep.kt");
        Files.writeString(keepJava, "class Keep {}");
        Files.writeString(keepKt, "class Keep");
        // Would be expensive to descend in a real repo — must not be collected.
        Files.writeString(target.resolve("Gen.java"), "class Gen {}");
        Files.writeString(build.resolve("Gen.kt"), "class Gen");

        FormatPlans.CollectedSources found = FormatPlans.collectSources(tmp);
        assertThat(found.javaFiles()).containsExactly(keepJava);
        assertThat(found.kotlinFiles()).containsExactly(keepKt);
        assertThat(found.total()).isEqualTo(2);
    }

    @Test
    void ancestor_named_build_does_not_hide_sources(@TempDir Path tmp) throws Exception {
        Path project = tmp.resolve("build/tmp/proj");
        Path src = project.resolve("src/main/java");
        Files.createDirectories(src);
        Path keep = src.resolve("Keep.java");
        Files.writeString(keep, "class Keep {}");
        assertThat(FormatPlans.collectSources(project).javaFiles()).containsExactly(keep);
    }

    /**
     * The mtime/size freshness index is the outer filter — a recorded path is not sent to the
     * worker at all next run — so what it records decides what a second run can still see.
     *
     * <p>{@code unparseable} must not be recorded. Recording it would hide the finding behind the
     * index one run after it was first reported, which is the same disappearing act, one layer up,
     * that {@code applyRewrite} was doing when it read a ParseError as "nothing to change".
     */
    @Test
    void an_unparseable_file_is_never_recorded_fresh() {
        assertThat(FormatPlans.recordsFreshness("unparseable", false))
                .as("apply mode: the rewrite pass still has not run on this file")
                .isFalse();
        assertThat(FormatPlans.recordsFreshness("unparseable", true)).isFalse();
        assertThat(FormatPlans.recordsFreshness("error", false)).isFalse();
    }

    /** …and the statuses that were recorded before still are. */
    @Test
    void settled_files_are_still_recorded_fresh() {
        assertThat(FormatPlans.recordsFreshness("clean", true)).isTrue();
        assertThat(FormatPlans.recordsFreshness("clean", false)).isTrue();
        assertThat(FormatPlans.recordsFreshness("skipped", false)).isTrue();
        assertThat(FormatPlans.recordsFreshness("changed", false))
                .as("apply mode wrote the formatted bytes, so the file on disk is now clean")
                .isTrue();
        assertThat(FormatPlans.recordsFreshness("changed", true))
                .as("--check wrote nothing, so those bytes are still the unformatted ones")
                .isFalse();
    }
}
