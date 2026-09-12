// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.command.toolchain.WrapperCommand;
import cc.jumpkick.repo.ReleaseVerifier;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
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
        // The same refusal JkDirs makes: a relative JK_HOME is not a home jk would read.
        assertThat(sh).contains("JK_HOME must be an absolute path");
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
        assertThat(bat).contains("jk-windows-x86_64-!VERSION!.zip");
        assertThat(bat).contains("JK_HOME must be an absolute path");
        assertThat(bat).doesNotContain(".exe.zip").doesNotContain(".xz");
        assertThat(bat).doesNotContain(".sock").doesNotContain("endpoint");
    }

    /**
     * The Windows wrapper reads three values it does not control — the latest {@code VERSION}
     * from the release host, the {@code jk-min} floor from the repository's lock, and the
     * installed {@code VERSION} file — and every PowerShell snippet it runs is a command string.
     * A value spliced into one could end a quoted literal and run code before the signature is
     * ever checked, so each is checked to be a version token first and then handed over through
     * the environment only.
     */
    @Test
    void windows_wrapper_validates_untrusted_values_and_never_splices_them_into_powershell() throws Exception {
        String bat = template("jk.bat");
        // The gate: a findstr regex over the whole value, letters/digits/._- only.
        assertThat(bat).contains("findstr /r /c:\"^[0-9A-Za-z._-][0-9A-Za-z._-]*$\"");
        // Every untrusted value passes the gate before anything uses it.
        for (String value : List.of("VERSION", "FLOOR", "INSTALLED")) {
            assertThat(bat).as("%s is checked", value).contains("call :require_version_token " + value + " ");
        }
        assertThat(bat.indexOf("call :require_version_token VERSION"))
                .as("VERSION is checked before it names a download")
                .isLessThan(bat.indexOf("$env:JK_WRAPPER_VERSION"));
        assertThat(bat.indexOf("call :require_version_token FLOOR"))
                .as("FLOOR is checked before the first version compare")
                .isLessThan(bat.indexOf("call :version_ge"));
        assertThat(bat.indexOf("call :require_version_token INSTALLED"))
                .as("INSTALLED is checked before it is compared")
                .isLessThan(bat.indexOf("call :version_ge INSTALLED"));
        // No PowerShell command line carries a wrapper variable; values travel as $env:.
        Pattern spliced = Pattern.compile("[%!](VERSION|FLOOR|INSTALLED|FILE|TMP|JK_RELEASES_URL)[%!]");
        for (String line : bat.split("\\R")) {
            if (!line.contains("powershell")) continue;
            assertThat(spliced.matcher(line).find())
                    .as("a wrapper variable is spliced into PowerShell text: %s", line)
                    .isFalse();
        }
        // %VAR% expands when cmd parses the line, before any check could run and with & | " live.
        // Only delayed expansion (!VAR!) is inert, so the untrusted values are never read that way.
        assertThat(bat).doesNotContain("%VERSION%").doesNotContain("%FLOOR%").doesNotContain("%INSTALLED%");
    }
}
