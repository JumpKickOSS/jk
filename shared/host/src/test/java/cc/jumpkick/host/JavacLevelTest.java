// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class JavacLevelTest {

    @Test
    void the_level_is_release_unless_the_arguments_export_a_system_module() {
        assertThat(JavacLevel.options(17, List.of())).containsExactly("--release", "17");
        assertThat(JavacLevel.options(17, List.of("-parameters", "--add-exports", "my.mod/my.pkg=ALL-UNNAMED")))
                .containsExactly("--release", "17");
        assertThat(JavacLevel.options(
                        17, List.of("--add-exports", "jdk.javadoc/jdk.javadoc.internal.tool=ALL-UNNAMED")))
                .containsExactly("-source", "17", "-target", "17", "-Xlint:-options");
        assertThat(JavacLevel.options(21, List.of("--add-reads=java.base=ALL-UNNAMED")))
                .containsExactly("-source", "21", "-target", "21", "-Xlint:-options");
        assertThat(JavacLevel.options(0, List.of())).isEmpty();
    }

    @Test
    void a_dangling_add_exports_exports_nothing() {
        assertThat(JavacLevel.exportsSystemModule(List.of("--add-exports"))).isFalse();
        assertThat(JavacLevel.exportsSystemModule(List.of("--add-modules", "jdk.javadoc")))
                .isFalse();
    }
}
