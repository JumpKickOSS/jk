// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.repo.ReleaseVerifier;
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
        assertThat(sh).contains("latest/VERSION").contains("SHA256SUMS.sig");
        assertThat(sh).contains(ReleaseVerifier.BUILT_IN_KEY);
        assertThat(sh).contains("\"jk-min = \"*").contains("$SEARCH/jk-lock.toml");
        // Bin resolution mirrors install.sh/JkDirs: one home, one bin, no cascade to drift from.
        assertThat(sh).contains("$BIN_DIR/jk").contains("${JK_HOME:-$HOME/.jk}/bin");
        assertThat(sh).doesNotContain("XDG_").doesNotContain("JK_BIN_DIR").doesNotContain("JK_INSTALL_DIR");
        // Downloads authenticate the manifest, then verify its exact artifact entry.
        assertThat(sh).contains("openssl dgst -sha256 -verify").contains("matches != 1");
        assertThat(sh).doesNotContain("\"jk = \"*").doesNotContain("sha256 = ");
        // Newest installed wins when it satisfies the floor; a stale channel is a hard error.
        assertThat(sh).contains("ver_ge").contains("requires jk >=");
        // Unix wrapper matches install.sh: .xz, inflated with system xz. No zip.
        assertThat(sh).contains("jk-$OS-$ARCH-$VERSION.xz").contains("xz -dc");
        assertThat(sh).doesNotContain(".zip");
        // Nothing daemon-shaped: the wrapper needs zero engine/endpoint awareness.
        assertThat(sh).doesNotContain(".sock").doesNotContain("endpoint").doesNotContain("gen1");
    }

    @Test
    void windows_wrapper_bootstraps_and_touches_only_the_frozen_surfaces() throws Exception {
        String bat = template("jk.bat");
        assertThat(bat).contains("latest/VERSION").contains("SHA256SUMS.sig");
        assertThat(bat).contains("RSASignaturePadding]::Pkcs1").contains("$count -ne 1");
        assertThat(bat).contains(ReleaseVerifier.BUILT_IN_RSA_MODULUS).contains(ReleaseVerifier.BUILT_IN_RSA_EXPONENT);
        assertThat(bat).contains("jk-min");
        assertThat(bat).doesNotContain("\"jk = \"").doesNotContain("sha256 = ");
        assertThat(bat).contains("%BIN_DIR%\\jk.exe").contains("%USERPROFILE%\\.jk");
        assertThat(bat).doesNotContain("JK_BIN_DIR").doesNotContain("JK_INSTALL_DIR");
        // Windows wrapper matches install.ps1: .zip (no system xz). Not .exe.zip.
        assertThat(bat).contains("jk-windows-x86_64-%VERSION%.zip");
        assertThat(bat).doesNotContain(".exe.zip").doesNotContain(".xz");
        assertThat(bat).doesNotContain(".sock").doesNotContain("endpoint");
    }
}
