// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The wrapper scripts are bootstrappers, not pins: they may depend on exactly two surfaces —
 * the release URL layout (releases.md, including {@code SHA256SUMS}) and the lock's optional
 * one-line {@code jk-min} floor — and nothing else about jk. No version pin, no artifact sha
 * from the lock, no daemon awareness.
 */
class WrapperTemplateTest {

    private static String template(String name) throws Exception {
        try (InputStream in = WrapperCommand.class.getResourceAsStream("wrapper/" + name)) {
            assertThat(in).as("template " + name + " bundled in the jar").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void posix_wrapper_bootstraps_and_touches_only_the_frozen_surfaces() throws Exception {
        String sh = template("jk.sh");
        // The two frozen dependencies: the release layout and the lock's optional floor.
        assertThat(sh).contains("latest/VERSION").contains("SHA256SUMS");
        assertThat(sh).contains("\"jk-min = \"*").contains("$SEARCH/jk-lock.toml");
        // Bin resolution mirrors install.sh/JkDirs; the wrapper never invents ~/.jk.
        assertThat(sh).contains("$BIN_DIR/jk").contains("XDG_BIN_HOME").doesNotContain("$HOME/.jk");
        // Downloads verify against the release's own sums — never a sha read from the lock.
        assertThat(sh).doesNotContain("\"jk = \"*").doesNotContain("sha256 = ");
        // Newest installed wins when it satisfies the floor; a stale channel is a hard error.
        assertThat(sh).contains("ver_ge").contains("requires jk >=");
        // Unix wrapper matches install.sh: .xz, inflated with system xz. No zip.
        assertThat(sh).contains("jk-$OS-$ARCH.xz").contains("xz -dc");
        assertThat(sh).doesNotContain(".zip");
        // Nothing daemon-shaped: the wrapper needs zero engine/endpoint awareness.
        assertThat(sh).doesNotContain(".sock").doesNotContain("endpoint").doesNotContain("gen1");
    }

    @Test
    void windows_wrapper_bootstraps_and_touches_only_the_frozen_surfaces() throws Exception {
        String bat = template("jk.bat");
        assertThat(bat).contains("latest/VERSION").contains("SHA256SUMS");
        assertThat(bat).contains("jk-min");
        assertThat(bat).doesNotContain("\"jk = \"").doesNotContain("sha256 = ");
        assertThat(bat).contains("%BIN_DIR%\\jk.exe").doesNotContain("%USERPROFILE%\\.jk");
        // Windows wrapper matches install.ps1: .zip (no system xz). Not .exe.zip.
        assertThat(bat).contains("jk-windows-x86_64.zip");
        assertThat(bat).doesNotContain(".exe.zip").doesNotContain(".xz");
        assertThat(bat).doesNotContain(".sock").doesNotContain("endpoint");
    }
}
