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
}
