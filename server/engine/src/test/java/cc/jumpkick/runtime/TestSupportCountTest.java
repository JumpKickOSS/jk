// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A test source's annotation count is read once per content, however many estimates ask. */
class TestSupportCountTest {

    @Test
    void a_source_is_counted_again_only_when_it_changes(@TempDir Path dir) throws Exception {
        Path src =
                Files.writeString(dir.resolve("FooTest.java"), "class FooTest { @Test void a() {} @Test void b() {} }");
        assertThat(TestSupport.countTests(src)).isEqualTo(2);

        Files.writeString(src, "class FooTest { @Test void a() {} @Test void b() {} @Test void c() {} }");
        Files.setLastModifiedTime(src, FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        assertThat(TestSupport.countTests(src))
                .as("a changed file is counted again")
                .isEqualTo(3);

        assertThat(TestSupport.countTests(dir.resolve("Missing.java"))).isZero();
    }
}
