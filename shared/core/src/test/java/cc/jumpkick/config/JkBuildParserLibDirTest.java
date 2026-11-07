// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static cc.jumpkick.config.JkBuildParserFixtures.PROJECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.LibDir;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** {@code [application] lib-dir}: the thin jar with a {@code Class-Path} and its lib directory. */
class JkBuildParserLibDirTest {

    private static JkBuild app(String keys) {
        return JkBuildParser.parse(PROJECT + "\n[application]\nmain = \"com.example.Main\"\n" + keys);
    }

    @Test
    void absent_means_no_layout() {
        assertThat(app("").libDir()).isNull();
        assertThat(app("lib-dir = false\n").libDir()).isNull();
    }

    @Test
    void true_is_libs_with_versioned_names_and_nothing_excluded() {
        LibDir libDir = Objects.requireNonNull(app("lib-dir = true\n").libDir());
        assertThat(libDir.dir()).isEqualTo("libs");
        assertThat(libDir.versions()).isTrue();
        assertThat(libDir.exclude()).isEmpty();
    }

    @Test
    void a_name_a_version_switch_and_exclusions_shape_it() {
        LibDir libDir = Objects.requireNonNull(app("""
                lib-dir      = "lib/runtime/"
                lib-versions = false
                lib-exclude  = ["okio", "com.example:extra", "org.unwanted:*"]
                """).libDir());
        assertThat(libDir.dir()).isEqualTo("lib/runtime");
        assertThat(libDir.versions()).isFalse();
        assertThat(libDir.excludes("com.squareup.okio", "okio")).isTrue();
        assertThat(libDir.excludes("com.example", "extra")).isTrue();
        assertThat(libDir.excludes("org.other", "extra")).isFalse();
        assertThat(libDir.excludes("org.unwanted", "anything")).isTrue();
        assertThat(libDir.excludes("org.wanted", "okio-jvm")).isFalse();
    }

    @Test
    void a_directory_outside_target_or_target_itself_is_refused() {
        for (String bad : new String[] {"\"\"", "\".\"", "\"../out\"", "\"/opt/libs\"", "\"a/../b\"", "3"}) {
            assertThatThrownBy(() -> app("lib-dir = " + bad + "\n"))
                    .as(bad)
                    .isInstanceOf(JkBuildParseException.class)
                    .hasMessageContaining("lib-dir");
        }
    }

    @Test
    void shaping_keys_without_the_layout_are_refused() {
        assertThatThrownBy(() -> app("lib-versions = false\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("lib-dir = true");
        assertThatThrownBy(() -> app("lib-dir = true\nlib-exclude = [\"a:b:c\"]\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("lib-exclude");
    }

    @Test
    void a_top_level_lib_dir_is_a_misplaced_application_key() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + "\nlib-dir = true\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("belongs in [application]");
    }
}
