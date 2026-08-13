// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PathUtilTest {

    @Test
    void resolveUserPath_expands_tilde_and_home_relative_to_the_same_absolute() {
        Path home = PathUtil.userHome();
        Path fromTilde = PathUtil.resolveUserPath("~/src/oss/jk");
        Path fromRelative = PathUtil.resolveUserPath("src/oss/jk");
        assertThat(fromTilde).isEqualTo(home.resolve("src/oss/jk").normalize());
        assertThat(fromRelative).isEqualTo(fromTilde);
    }

    @Test
    void resolveUserPath_bare_tilde_is_home() {
        assertThat(PathUtil.resolveUserPath("~")).isEqualTo(PathUtil.userHome());
        assertThat(PathUtil.resolveUserPath("~/")).isEqualTo(PathUtil.userHome());
    }

    @Test
    void resolveUserPath_keeps_absolute_paths() {
        Path abs = Path.of("/tmp/jk-workspace").toAbsolutePath().normalize();
        assertThat(PathUtil.resolveUserPath(abs.toString())).isEqualTo(abs);
        assertThat(PathUtil.resolveUserPath("  " + abs + "  ")).isEqualTo(abs);
    }

    @Test
    void resolveUserPath_does_not_expand_other_users_tilde() {
        // "~alice/…" is a relative path segment starting with ~alice, not home expansion.
        Path got = PathUtil.resolveUserPath("~alice/project");
        assertThat(got).isEqualTo(PathUtil.userHome().resolve("~alice/project").normalize());
    }

    @Test
    void resolveUserPath_rejects_blank() {
        assertThatThrownBy(() -> PathUtil.resolveUserPath(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> PathUtil.resolveUserPath("   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PathUtil.resolveUserPath(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
