// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code [test] idle-timeout}: jk's default unless set, a positive duration when it is. */
class ManifestTestIdleTimeoutTest {

    @TempDir
    Path tmp;

    private long parse(String toml) throws Exception {
        Path f = tmp.resolve("jk.toml");
        Files.writeString(f, "name = \"m\"\ngroup = \"g\"\nversion = \"1.0\"\n" + toml);
        return JkBuildParser.parse(f).build().testIdleTimeoutMs();
    }

    @Test
    void unset_means_the_default() throws Exception {
        assertThat(parse("")).isZero();
        assertThat(parse("[test]\nassertions = true\n")).isZero();
    }

    @Test
    void the_timeout_is_a_duration() throws Exception {
        assertThat(parse("[test]\nidle-timeout = \"45m\"\n")).isEqualTo(45 * 60_000L);
        assertThat(parse("[test]\nidle-timeout = 90\n")).isEqualTo(90_000);
    }

    @Test
    void anything_else_is_refused() {
        assertThatThrownBy(() -> parse("[test]\nidle-timeout = \"never\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[test].idle-timeout must be a positive duration");
        assertThatThrownBy(() -> parse("[test]\nidle-timeout = \"0s\"\n")).isInstanceOf(JkBuildParseException.class);
    }
}
