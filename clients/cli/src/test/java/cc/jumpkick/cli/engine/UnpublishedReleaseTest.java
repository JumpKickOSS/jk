// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.EngineInstall;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A client whose version the release site does not publish — one built from a checkout — names
 * itself an unpublished build and the remedy when its engine download 404s; any other failure of
 * the checksum fetch keeps the plain download error.
 */
class UnpublishedReleaseTest {

    private static final String VERSION = "0.15.3";

    @Test
    void a_404_on_the_release_checksums_names_the_unpublished_build_and_the_remedy(@TempDir Path root)
            throws Exception {
        try (StubReleaseDirectory release = new StubReleaseDirectory(VERSION)) {
            release.status("SHA256SUMS", 404);
            EngineInstall install = new EngineInstall(root.resolve("lib"));

            assertThatThrownBy(() -> EngineJarFetcher.fetch(
                            release.base(), VERSION, new Cas(root.resolve("cache")), install, release.verifier()))
                    .isInstanceOf(IOException.class)
                    .hasMessage("jk 0.15.3 is not a published release, so its engine cannot be downloaded — this"
                            + " looks like an unpublished build from a checkout: run `jk install -S`"
                            + " there, or set JK_HOME to a home where this build is installed");
            assertThat(install.resolve(VERSION)).isEmpty();
            assertThat(release.requested()).containsExactly("SHA256SUMS");
        }
    }

    @Test
    void the_manifest_404_names_the_missing_release() throws Exception {
        try (StubReleaseDirectory release = new StubReleaseDirectory(VERSION)) {
            release.status("SHA256SUMS", 404);

            assertThatThrownBy(() -> ReleaseArtifacts.manifest(release.base(), VERSION, release.verifier()))
                    .isInstanceOf(ReleaseArtifacts.NotPublished.class)
                    .hasMessage("no jk 0.15.3 release at " + release.base() + "/0.15.3/ — HTTP 404");
        }
    }

    @Test
    void another_status_on_the_checksums_keeps_the_download_error(@TempDir Path root) throws Exception {
        try (StubReleaseDirectory release = new StubReleaseDirectory(VERSION)) {
            release.status("SHA256SUMS", 403);

            assertThatThrownBy(() -> EngineJarFetcher.fetch(
                            release.base(),
                            VERSION,
                            new Cas(root.resolve("cache")),
                            new EngineInstall(root.resolve("lib")),
                            release.verifier()))
                    .isInstanceOf(IOException.class)
                    .isNotInstanceOf(ReleaseArtifacts.NotPublished.class)
                    .hasMessageStartingWith("could not download the release checksums from ")
                    .hasMessageEndingWith(" — HTTP 403")
                    .hasMessageNotContaining("unpublished");
        }
    }
}
