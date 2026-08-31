// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.TestSelection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AffectedTestsComputeTest {

    @Test
    void scan_test_sources_finds_traditional_layout(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("src/test/java/com/acme/FooTest.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package com.acme; class FooTest {}");
        var tests = AffectedTestsCompute.scanTestSources(dir, TestSelection.DEFAULT);
        assertThat(tests).extracting(TestClassIndex.Entry::className).containsExactly("com.acme.FooTest");
    }

    @Test
    void has_local_dirty_is_path_prefix_not_the_whole_cone(@TempDir Path dir) {
        Path cli = dir.resolve("clients/cli");
        assertThat(AffectedTestsCompute.hasLocalDirty(
                        dir,
                        cli,
                        List.of("clients/cli/src/main/java/Foo.java", "server/engine/src/test/java/BarTest.java")))
                .isTrue();
        assertThat(AffectedTestsCompute.hasLocalDirty(
                        dir, dir.resolve("server/engine"), List.of("clients/cli/src/main/java/Foo.java")))
                .isFalse();
    }

    @Test
    void tests_for_uses_sources_when_test_classes_are_missing(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("src/test/java/com/acme/FooTest.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package com.acme; class FooTest {}");
        var tests =
                AffectedTestsCompute.testsFor(dir, dir.resolve("target/classes/test"), Set.of(), TestSelection.DEFAULT);
        assertThat(tests).extracting(TestClassIndex.Entry::className).containsExactly("com.acme.FooTest");
    }
}
