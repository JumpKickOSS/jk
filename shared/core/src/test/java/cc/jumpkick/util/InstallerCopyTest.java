// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Tripwire for the installer copies. {@code hosting/public/} is what the CDN serves to
 * {@code curl … | bash} and {@code irm … | iex}; the repo-root copies are the ones reviewed,
 * hand-run and documented. A fix landed in only one of a pair ships the web a different
 * installer from the one this repo tests, and nothing else notices.
 */
class InstallerCopyTest {

    @Test
    void cdn_copies_are_byte_identical_to_the_repo_root_installers() throws IOException {
        Path repo = findRepoRoot();

        for (String name : new String[] {"install.sh", "install.ps1"}) {
            Path root = repo.resolve(name);
            Path served = repo.resolve("hosting/public").resolve(name);
            assertThat(Files.readAllBytes(served))
                    .as("hosting/public/%s must be a byte-identical copy of %s", name, name)
                    .isEqualTo(Files.readAllBytes(root));
        }
    }

    /**
     * {@code scripts/install.ps1} predates the repo-root entrypoint and was once a full duplicate
     * — a third copy to keep in step. It must stay a forwarder.
     */
    @Test
    void the_scripts_shim_forwards_instead_of_duplicating() throws IOException {
        Path repo = findRepoRoot();

        String shim = Files.readString(repo.resolve("scripts/install.ps1"));
        assertThat(shim).contains("install.ps1").doesNotContain("Invoke-WebRequest");
        assertThat(shim.lines().count()).isLessThan(20);
    }

    private static Path findRepoRoot() {
        return RepoRoot.find(InstallerCopyTest.class);
    }
}
