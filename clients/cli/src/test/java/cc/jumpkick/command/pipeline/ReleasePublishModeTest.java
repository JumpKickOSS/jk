// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.command.pipeline.ReleaseCommand.PublishMode;
import cc.jumpkick.wire.protocol.ProjectInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Who publishes a release: the flag, then {@code [release] publish}, then what the project has. */
class ReleasePublishModeTest {

    @TempDir
    Path root;

    private static ProjectInfo info(String repository, String registry, String configured) {
        return ProjectInfo.decode("{\"type\":\"project-info-ack\",\"publishRepository\":\"" + repository
                + "\",\"imageRegistry\":\"" + registry + "\",\"releasePublish\":\"" + configured + "\"}");
    }

    private void ciRunsPublish() throws IOException {
        Path file = root.resolve(".github/workflows/publish.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "steps:\n  - run: jk publish\n");
    }

    @Test
    void no_destination_publishes_nothing() throws IOException {
        ciRunsPublish();
        assertThat(ReleaseCommand.publishMode(null, info("", "", ""), root, true))
                .isEqualTo(PublishMode.NONE);
    }

    @Test
    void a_destination_and_a_ci_file_that_runs_jk_publish_leave_it_to_ci() throws IOException {
        ciRunsPublish();
        assertThat(ReleaseCommand.publishMode(null, info("central", "", ""), root, true))
                .isEqualTo(PublishMode.CI);
    }

    @Test
    void a_repository_and_no_such_ci_file_publish_from_here() {
        assertThat(ReleaseCommand.publishMode(null, info("central", "", ""), root, true))
                .isEqualTo(PublishMode.LOCAL);
    }

    @Test
    void a_release_bucket_alone_is_a_destination_to_publish_from_here() {
        ProjectInfo bucket =
                ProjectInfo.decode("{\"type\":\"project-info-ack\",\"publishRelease\":\"s3://acme/app/\"}");
        assertThat(ReleaseCommand.publishMode(null, bucket, root, true)).isEqualTo(PublishMode.LOCAL);
    }

    @Test
    void an_image_registry_alone_is_a_destination_to_publish_from_here() {
        assertThat(ReleaseCommand.publishMode(null, info("", "ghcr.io/acme", ""), root, true))
                .isEqualTo(PublishMode.LOCAL);
    }

    @Test
    void the_flag_beats_the_manifest_and_the_manifest_beats_the_inference() throws IOException {
        ciRunsPublish();
        assertThat(ReleaseCommand.publishMode("local", info("central", "", "ci"), root, true))
                .isEqualTo(PublishMode.LOCAL);
        assertThat(ReleaseCommand.publishMode(null, info("central", "", "none"), root, true))
                .isEqualTo(PublishMode.NONE);
    }

    @Test
    void outside_git_no_ci_file_is_read() throws IOException {
        ciRunsPublish();
        assertThat(ReleaseCommand.publishMode(null, info("central", "", ""), root, false))
                .isEqualTo(PublishMode.LOCAL);
    }
}
