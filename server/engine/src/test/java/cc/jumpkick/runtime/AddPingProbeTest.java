// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.m2.MavenSettings;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.task.RunNotices;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk add --ping} looks a coordinate up through the repository group a build would use:
 * with a {@code mirror-of = ["*"]} table in the user's config, the mirror answers, and with a
 * project in hand, the project's own repositories do. The sandbox denies the public hosts, so a
 * probe that went around the mirror fails rather than passing unseen.
 */
class AddPingProbeTest {

    private static final String GROUP = "com.example.ping";
    private static final String ARTIFACT = "lib";

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    /** Fresh per run, so neither the process memos nor a warm store can answer for the stub. */
    private final String version = MavenStub.freshVersion();

    private @Nullable String priorSettings;

    @TempDir
    Path tmp;

    @BeforeEach
    void isolate() throws Exception {
        priorSettings = System.getProperty(MavenSettings.SETTINGS_PROPERTY);
        Path home = Files.createDirectories(tmp.resolve("home"));
        Files.writeString(home.resolve("config.toml"), """
                [repositories.corp]
                url = "%s"
                mirror-of = ["*"]
                """.formatted(http.baseUrl()));
        System.setProperty("jk.env.JK_HOME", home.toString());
        System.setProperty(
                MavenSettings.SETTINGS_PROPERTY,
                tmp.resolve("absent-settings.xml").toString());
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
        RunNotices.clear();
        new MavenStub(http).leaf(GROUP, ARTIFACT, version);
    }

    @AfterEach
    void restore() {
        restore(MavenSettings.SETTINGS_PROPERTY, priorSettings);
    }

    private static void restore(String property, @Nullable String prior) {
        if (prior == null) System.clearProperty(property);
        else System.setProperty(property, prior);
    }

    @Test
    void with_no_project_an_exact_version_is_found_through_the_users_mirror() throws Exception {
        Path noManifest = tmp.resolve("elsewhere").resolve(ManifestPaths.MANIFEST);

        var found = StableVersions.probe(noManifest, GROUP, ARTIFACT, version);

        assertThat(found).map(URI::toString).hasValue(http.base() + MavenStub.path(GROUP, ARTIFACT, version, ".jar"));
        assertThat(http.requested()).contains(MavenStub.path(GROUP, ARTIFACT, version, ".pom"));
    }

    @Test
    void with_no_project_a_versionless_probe_reads_the_metadata_through_the_users_mirror() throws Exception {
        Path noManifest = tmp.resolve("elsewhere").resolve(ManifestPaths.MANIFEST);

        var found = StableVersions.probe(noManifest, GROUP, ARTIFACT, null);

        assertThat(found).map(URI::toString).hasValue(http.base() + MavenStub.metadataPath(GROUP, ARTIFACT));
    }

    @Test
    void a_coordinate_no_repository_serves_is_not_found() throws Exception {
        Path noManifest = tmp.resolve("elsewhere").resolve(ManifestPaths.MANIFEST);

        assertThat(StableVersions.probe(noManifest, GROUP, "absent", "1.0")).isEmpty();
        assertThat(http.requested()).isNotEmpty().allSatisfy(path -> assertThat(path)
                .contains("/absent/"));
    }

    @Test
    void in_a_project_its_own_repositories_answer_for_the_groups_they_claim() throws Exception {
        // `*` would stand in for the project's file:// repository too; external:* leaves it alone.
        Files.writeString(
                Path.of(System.getProperty("jk.env.JK_HOME")).resolve("config.toml"), """
                [repositories.corp]
                url = "%s"
                mirror-of = ["external:*"]
                """.formatted(http.baseUrl()));
        Path repo = tmp.resolve("repo");
        RepoFixtures.module(repo, "com.acme", "thing", "1.0.0");
        Path dir = Files.createDirectories(tmp.resolve("app"));
        Path manifest = Files.writeString(dir.resolve(ManifestPaths.MANIFEST), """
                group   = "com.acme"
                name    = "app"
                version = "0.1.0"

                [repositories.local]
                url = "%s"
                groups = ["com.acme"]
                """.formatted(repo.toUri()));

        var found = StableVersions.probe(manifest, "com.acme", "thing", "1.0.0");

        assertThat(found).hasValueSatisfying(url -> assertThat(Path.of(url).startsWith(repo))
                .isTrue());
        assertThat(http.requested())
                .as("a bound group is asked only of its claimant")
                .isEmpty();
    }
}
