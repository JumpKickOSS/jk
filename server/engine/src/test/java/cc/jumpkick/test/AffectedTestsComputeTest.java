// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.model.JkBuild;
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
        var tests = AffectedTestsCompute.scanTestSources(dir, false, List.of("test"));
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
        var tests = AffectedTestsCompute.testsFor(
                dir, PLAIN, dir.resolve("target/classes/test"), Set.of(), TestSelection.DEFAULT);
        assertThat(tests).extracting(TestClassIndex.Entry::className).containsExactly("com.acme.FooTest");
    }

    /** A class-pattern suite's classes are its own: plain jk test leaves them out, --suite runs only them. */
    @Test
    void tests_for_honours_class_pattern_suites(@TempDir Path dir) throws Exception {
        Path pkg = Files.createDirectories(dir.resolve("src/test/java/com/acme"));
        Files.writeString(pkg.resolve("FooTest.java"), "package com.acme; class FooTest {}");
        Files.writeString(pkg.resolve("FooIT.java"), "package com.acme; class FooIT {}");
        Files.writeString(pkg.resolve("FlakyIT.java"), "package com.acme; class FlakyIT {}");
        JkBuild build = JkBuildParser.parse("""
                group = "com.acme"
                name = "m"
                version = "1.0.0"

                [test.suites.integration]
                classes = ["*IT"]
                exclude-classes = ["Flaky*"]
                """);
        Path classes = dir.resolve("target/classes/test");

        assertThat(AffectedTestsCompute.testsFor(dir, build, classes, Set.of(), TestSelection.DEFAULT))
                .extracting(TestClassIndex.Entry::className)
                .containsExactly("com.acme.FooTest");
        TestSelection integration = TestSelection.of(List.of("integration"), false, List.of(), List.of());
        assertThat(AffectedTestsCompute.testsFor(dir, build, classes, Set.of(), integration))
                .extracting(TestClassIndex.Entry::className)
                .containsExactly("com.acme.FooIT");
    }

    private static final JkBuild PLAIN = JkBuildParser.parse("""
            group = "com.acme"
            name = "m"
            version = "1.0.0"
            """);
}
