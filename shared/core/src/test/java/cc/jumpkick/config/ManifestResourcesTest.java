// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.BuildBlock;
import org.junit.jupiter.api.Test;

class ManifestResourcesTest {

    private static BuildBlock.Resources parse(String table) {
        return JkBuildParser.parse("group = \"g\"\nname = \"m\"\nversion = \"1.0\"\n\n" + table)
                .build()
                .resources();
    }

    @Test
    void absent_is_empty() {
        assertThat(parse("")).isEqualTo(BuildBlock.Resources.EMPTY);
    }

    @Test
    void every_key_parses_and_quoted_and_dotted_property_names_read_alike() {
        BuildBlock.Resources r = parse("""
                [resources]
                dirs = ["src/extra/resources"]
                filtered = ["src/filter/resources"]
                test-dirs = ["src/extra/test-resources"]
                test-filtered = ["src/filter/test-resources"]

                [resources.properties]
                "remoting.version" = "3391"
                changelog.url = "https://www.jenkins.io/changelog"
                plain = "x"
                """);
        assertThat(r.dirs()).containsExactly("src/extra/resources");
        assertThat(r.filtered()).containsExactly("src/filter/resources");
        assertThat(r.testDirs()).containsExactly("src/extra/test-resources");
        assertThat(r.testFiltered()).containsExactly("src/filter/test-resources");
        assertThat(r.properties())
                .containsEntry("remoting.version", "3391")
                .containsEntry("changelog.url", "https://www.jenkins.io/changelog")
                .containsEntry("plain", "x");
    }

    @Test
    void an_unknown_key_a_directory_outside_the_module_and_a_non_string_value_are_refused() {
        assertThatThrownBy(() -> parse("[resources]\nincludes = [\"*.properties\"]\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[resources] unknown key `includes`");
        assertThatThrownBy(() -> parse("[resources]\nfiltered = [\"../shared\"]\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("inside the module");
        assertThatThrownBy(() -> parse("[resources.properties]\nport = 8080\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("`port` must be a string");
    }
}
