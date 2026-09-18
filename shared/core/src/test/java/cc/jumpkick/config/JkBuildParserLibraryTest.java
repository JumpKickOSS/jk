// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.JkBuild;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code [library]} is what a library ships beyond its thin jar: a fat jar, and the packages that
 * jar relocates. A module is a library or an application, never both.
 */
class JkBuildParserLibraryTest {

    private static final String PROJECT = """
            group = "com.ex"
            name = "lucene9-shaded"
            version = "1.0.0"
            java = 25
            """;

    @Test
    void library_absent_means_no_fat_jar_and_no_relocation() {
        JkBuild build = JkBuildParser.parse(PROJECT);
        assertThat(build.libraryOpt()).isEmpty();
        assertThat(build.assembly()).isFalse();
        assertThat(build.relocate()).isEmpty();
        assertThat(build.relocates()).isFalse();
    }

    @Test
    void library_assembly_is_the_fat_jar_of_a_module_that_is_not_an_application() {
        JkBuild build = JkBuildParser.parse(PROJECT + "\n[library]\nassembly = true\n");
        assertThat(build.isApplication()).isFalse();
        assertThat(build.assembly()).isTrue();
        assertThat(build.relocates()).isFalse();
        assertThat(build.libraryOpt().orElseThrow().assembly()).isTrue();
    }

    @Test
    void library_relocate_implies_the_fat_jar_and_keeps_declaration_order() {
        JkBuild build = JkBuildParser.parse(PROJECT
                + "\n[library]\nrelocate = { \"org.apache.lucene\" = \"com.ex.shaded.lucene9\","
                + " \"org.apache.commons\" = \"com.ex.shaded.commons\" }\n");
        assertThat(build.assembly()).as("relocation is a fact of the fat jar").isTrue();
        assertThat(build.relocates()).isTrue();
        assertThat(build.relocate())
                .containsExactly(
                        Map.entry("org.apache.lucene", "com.ex.shaded.lucene9"),
                        Map.entry("org.apache.commons", "com.ex.shaded.commons"));
    }

    @Test
    void library_and_application_are_exclusive() {
        assertThatThrownBy(() -> JkBuildParser.parse(
                        PROJECT + "\n[application]\nmain = \"com.ex.App\"\n\n[library]\nassembly = true\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[library] and [application] are exclusive");
    }

    @Test
    void library_rejects_a_key_it_does_not_have() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + "\n[library]\nmain = \"com.ex.App\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[library] unknown key `main`")
                .hasMessageContaining("assembly, relocate");
    }

    @Test
    void library_relocate_needs_a_package_on_both_sides() {
        assertThatThrownBy(
                        () -> JkBuildParser.parse(PROJECT + "\n[library]\nrelocate = { \"org.apache.lucene\" = 3 }\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[library].relocate.org.apache.lucene");
    }

    @Test
    void a_top_level_assembly_key_names_both_tables_it_could_belong_to() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + "\nassembly = true\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("belongs in [application] (or in [library]");
    }
}
