// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.ReleaseSources;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code [multi-release]} — each key a Java release, each value the source root or roots compiled at it. */
class ManifestMultiReleaseTest {

    @TempDir
    Path tmp;

    private JkBuild parse(String toml) throws Exception {
        Path f = tmp.resolve("jk.toml");
        Files.writeString(f, "name = \"m\"\ngroup = \"g\"\nversion = \"1.0\"\n" + toml);
        return JkBuildParser.parse(f);
    }

    @Test
    void absent_is_an_ordinary_jar() throws Exception {
        assertThat(parse("").build().multiRelease()).isEmpty();
        assertThat(parse("").build().isMultiRelease()).isFalse();
    }

    @Test
    void a_string_or_an_array_names_the_roots_in_release_order() throws Exception {
        JkBuild build =
                parse("[multi-release]\n25 = [\"src/main/java25\", \"src/gen/java25\"]\n21 = \"src/main/java21\"\n");
        assertThat(build.build().multiRelease())
                .containsExactly(
                        new ReleaseSources(21, List.of("src/main/java21")),
                        new ReleaseSources(25, List.of("src/main/java25", "src/gen/java25")));
        assertThat(build.build().isMultiRelease()).isTrue();
    }

    @Test
    void a_key_that_is_not_a_release_or_is_below_nine_is_a_parse_error() {
        assertThatThrownBy(() -> parse("[multi-release]\njava21 = \"src/main/java21\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("`java21` is not a Java release");
        assertThatThrownBy(() -> parse("[multi-release]\n8 = \"src/main/java8\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("at least 9");
        assertThatThrownBy(() -> parse("[multi-release]\n21 = 3\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[multi-release] 21 must be a source root");
        assertThatThrownBy(() -> parse("multi-release = \"src/main/java21\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("`multi-release` must be a table");
    }
}
