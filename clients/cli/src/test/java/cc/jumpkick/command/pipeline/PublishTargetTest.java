// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.wire.protocol.ProjectInfo;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A snapshot publishes to {@code [publish] snapshot-repository}; a release to {@code repository}. */
class PublishTargetTest {

    @TempDir
    Path dir;

    private Path manifest() throws IOException {
        Path manifest = dir.resolve("jk.toml");
        Files.writeString(manifest, """
                [repositories.rel]
                url = "https://releases.acme.dev/"
                [repositories.snap]
                url = "https://snapshots.acme.dev/"
                publish-url = "https://nexus.acme.dev/snapshots/"
                """);
        return manifest;
    }

    private static ProjectInfo info(String repository, String snapshotRepository) {
        return ProjectInfo.decode(
                "{\"type\":\"project-info-ack\",\"version\":\"1.0.0-SNAPSHOT\",\"publishRepository\":\"" + repository
                        + "\",\"publishSnapshotRepository\":\"" + snapshotRepository + "\"}");
    }

    private PublishTarget.Resolution resolve(ProjectInfo info, boolean snapshot) throws IOException {
        return PublishTarget.resolve(info, dir, manifest(), null, snapshot, true, true, name -> null);
    }

    private static PublishTarget target(PublishTarget.Resolution resolved) {
        return Objects.requireNonNull(resolved.target(), resolved.refusal());
    }

    @Test
    void a_snapshot_goes_to_the_snapshot_repository() throws Exception {
        var resolved = resolve(info("https://releases.acme.dev/", "https://nexus.acme.dev/snapshots/"), true);

        assertThat(target(resolved).repoUrl()).isEqualTo(URI.create("https://nexus.acme.dev/snapshots/"));
    }

    @Test
    void a_release_goes_to_the_release_repository_whatever_the_snapshot_one_says() throws Exception {
        var resolved = resolve(info("https://releases.acme.dev/", "https://nexus.acme.dev/snapshots/"), false);

        assertThat(target(resolved).repoUrl()).isEqualTo(URI.create("https://releases.acme.dev/"));
    }

    @Test
    void a_snapshot_with_no_snapshot_repository_goes_to_the_release_one() throws Exception {
        var resolved = resolve(info("https://releases.acme.dev/", ""), true);

        assertThat(target(resolved).repoUrl()).isEqualTo(URI.create("https://releases.acme.dev/"));
    }

    @Test
    void a_snapshot_is_refused_at_central() throws Exception {
        var resolved = resolve(info("central", ""), true);

        assertThat(resolved.target()).isNull();
        assertThat(resolved.refusal()).contains("Maven Central takes no -SNAPSHOT");
    }
}
