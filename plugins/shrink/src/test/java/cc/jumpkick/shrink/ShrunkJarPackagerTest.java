// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.shrink;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** JK-1126: top-level `$` class names vs nested types. */
class ShrunkJarPackagerTest {

    @Test
    void nested_class_with_outer_peer_is_skipped() {
        Set<String> paths = Set.of("com/ex/Outer.class", "com/ex/Outer$Inner.class");
        assertThat(ShrunkJarPackager.isNestedClassFile("com/ex/Outer$Inner.class", paths)).isTrue();
        assertThat(ShrunkJarPackager.isNestedClassFile("com/ex/Outer.class", paths)).isFalse();
    }

    @Test
    void top_level_dollar_name_without_outer_is_kept() {
        // Legal JVM top-level simple name containing `$` — no Outer.class peer.
        Set<String> paths = Set.of("com/ex/Foo$Bar.class");
        assertThat(ShrunkJarPackager.isNestedClassFile("com/ex/Foo$Bar.class", paths)).isFalse();
    }

    @Test
    void appendModuleClassKeeps_includes_top_level_dollar_class(@TempDir Path tmp) throws Exception {
        Path classes = tmp.resolve("classes");
        Path pkg = classes.resolve("com/ex");
        Files.createDirectories(pkg);
        Files.write(pkg.resolve("Outer.class"), new byte[] {(byte) 0xCA, (byte) 0xFE});
        Files.write(pkg.resolve("Outer$Inner.class"), new byte[] {(byte) 0xCA, (byte) 0xFE});
        Files.write(pkg.resolve("Foo$Bar.class"), new byte[] {(byte) 0xCA, (byte) 0xFE});

        StringBuilder pro = new StringBuilder();
        ShrunkJarPackager.appendModuleClassKeeps(pro, classes);
        String rules = pro.toString();
        assertThat(rules).contains("-keep class com.ex.Outer ");
        assertThat(rules).contains("-keep class com.ex.Foo$Bar ");
        assertThat(rules).doesNotContain("-keep class com.ex.Outer$Inner ");
    }
}
