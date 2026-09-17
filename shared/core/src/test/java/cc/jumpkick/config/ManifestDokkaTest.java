// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.VersionSelector;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code [dokka]} — the Dokka release and output format a Kotlin module's javadoc jar is built
 * with. Absent, the defaults apply; a key off its default is read; the rest is a parse error.
 */
class ManifestDokkaTest {

    @TempDir
    Path tmp;

    private JkBuild parse(String toml) throws Exception {
        Path f = tmp.resolve("jk.toml");
        Files.writeString(f, "name = \"m\"\ngroup = \"g\"\nversion = \"1.0\"\n" + toml);
        return JkBuildParser.parse(f);
    }

    @Test
    void absent_means_the_default_release_in_javadoc_format() throws Exception {
        JkBuild.Dokka dokka = parse("").build().dokka();
        assertThat(dokka).isEqualTo(JkBuild.Dokka.DEFAULT);
        assertThat(dokka.isDefault()).isTrue();
        assertThat(dokka.version()).isInstanceOf(VersionSelector.Exact.class);
        assertThat(((VersionSelector.Exact) dokka.version()).version()).isEqualTo(JkBuild.Dokka.DEFAULT_VERSION);
        assertThat(dokka.format()).isEqualTo(JkBuild.Dokka.Format.JAVADOC);
    }

    @Test
    void version_and_format_are_read() throws Exception {
        JkBuild.Dokka dokka =
                parse("[dokka]\nversion = \"^2\"\nformat = \"html\"\n").build().dokka();
        assertThat(dokka.version()).isInstanceOf(VersionSelector.Caret.class);
        assertThat(dokka.version().raw()).isEqualTo("^2");
        assertThat(dokka.format()).isEqualTo(JkBuild.Dokka.Format.HTML);
        assertThat(dokka.isDefault()).isFalse();
    }

    @Test
    void an_unknown_key_or_format_is_a_parse_error() {
        assertThatThrownBy(() -> parse("[dokka]\nrelease = \"2.2.0\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[dokka] unknown key `release`")
                .hasMessageContaining("version, format");
        assertThatThrownBy(() -> parse("[dokka]\nformat = \"pdf\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[dokka] format")
                .hasMessageContaining("javadoc or html");
        assertThatThrownBy(() -> parse("dokka = \"2.2.0\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("must be a table");
    }
}
