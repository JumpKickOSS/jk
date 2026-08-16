// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The wrapper scripts are FROZEN (engine-versioning-plan §7): they may depend on exactly two
 * surfaces — the release URL layout (releases.md) and the lock's one-line toolchain pin — and
 * nothing else about jk. This test pins that contract so a template edit that reaches deeper
 * fails loudly.
 */
class WrapperTemplateTest {

    private static String template(String name) throws Exception {
        try (InputStream in = WrapperCommand.class.getResourceAsStream("wrapper/" + name)) {
            assertThat(in).as("template " + name + " bundled in the jar").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void posix_wrapper_touches_only_the_frozen_surfaces() throws Exception {
        String sh = template("jk.sh");
        // The two frozen dependencies…
        assertThat(sh).contains("jk-lock.toml").contains("\"jk = \"*").contains("latest/VERSION");
        assertThat(sh).contains("versions/$VERSION/bin/jk");
        // …and the sha pin gates the download.
        assertThat(sh).contains("sha256");
        // Unix wrapper matches install.sh: .xz, inflated with system xz. No zip.
        assertThat(sh).contains("jk-$OS-$ARCH.xz").contains("xz -dc");
        assertThat(sh).doesNotContain(".zip");
        // Workspace member wrappers walk up to the root lock, and a pinless
        // bootstrap warns instead of silently trusting the download.
        assertThat(sh).contains("$SEARCH/jk-lock.toml").contains("WARNING");
        // Nothing daemon-shaped: the wrapper needs zero engine/endpoint awareness. (The word
        // "engine" itself appears in the doc-reference comment — assert on the mechanisms.)
        assertThat(sh).doesNotContain(".sock").doesNotContain("endpoint").doesNotContain("gen1");
    }

    @Test
    void windows_wrapper_touches_only_the_frozen_surfaces() throws Exception {
        String bat = template("jk.bat");
        assertThat(bat).contains("jk-lock.toml").contains("latest/VERSION");
        assertThat(bat).contains("versions\\%VERSION%\\bin");
        assertThat(bat).contains("SHA256");
        // Windows wrapper matches install.ps1: .zip (no system xz). Not .exe.zip.
        assertThat(bat).contains("jk-windows-x86_64.zip");
        assertThat(bat).doesNotContain(".exe.zip").doesNotContain(".xz");
        assertThat(bat).doesNotContain(".sock").doesNotContain("endpoint");
    }
}
