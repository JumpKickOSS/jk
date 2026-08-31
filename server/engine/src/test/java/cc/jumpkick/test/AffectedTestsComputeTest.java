// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.TestSelection;
import java.nio.file.Files;
import java.nio.file.Path;
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
    void tests_for_uses_sources_when_test_classes_are_missing(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("src/test/java/com/acme/FooTest.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package com.acme; class FooTest {}");
        var tests =
                AffectedTestsCompute.testsFor(dir, dir.resolve("target/classes/test"), Set.of(), TestSelection.DEFAULT);
        assertThat(tests).extracting(TestClassIndex.Entry::className).containsExactly("com.acme.FooTest");
    }
}
