// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.wire.runtime.PublishLane;
import cc.jumpkick.wire.runtime.ReleaseOutcome;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Which lanes a {@code jk publish} runs, in which order, and what it refuses before anything builds. */
class PublishLanesTest {

    private static final String BUCKET = "s3://acme/app/";

    private static PublishLanes select(List<String> only, boolean stage, boolean library, String release) {
        return PublishLanes.select(only, stage, false, library, release, false);
    }

    @Test
    void every_lane_with_a_target_runs_library_before_release() {
        assertThat(select(List.of(), false, true, BUCKET).lanes())
                .containsExactly(PublishLane.LIBRARY, PublishLane.RELEASE);
        assertThat(select(List.of(), false, false, BUCKET).lanes()).containsExactly(PublishLane.RELEASE);
        assertThat(select(List.of(), false, true, "").lanes()).containsExactly(PublishLane.LIBRARY);
    }

    @Test
    void only_narrows_the_lanes_and_takes_a_comma_list() {
        assertThat(select(List.of("release"), false, true, BUCKET).lanes()).containsExactly(PublishLane.RELEASE);
        assertThat(select(List.of("release,library"), false, true, BUCKET).lanes())
                .containsExactly(PublishLane.LIBRARY, PublishLane.RELEASE);
        assertThat(select(List.of("library", "release"), false, true, BUCKET).lanes())
                .containsExactly(PublishLane.LIBRARY, PublishLane.RELEASE);
    }

    @Test
    void a_lane_with_no_target_is_skipped_unless_only_names_it() {
        assertThat(select(List.of("release"), false, true, "").refusal())
                .isEqualTo("--only release needs [publish] release in jk.toml");
        assertThat(select(List.of("library"), false, false, BUCKET).refusal())
                .isEqualTo("--only library needs [publish] repository in jk.toml");
        assertThat(select(List.of("deliverables"), false, true, BUCKET).refusal())
                .isEqualTo("--only takes library, release or image, not `deliverables`");
        assertThat(select(List.of(), false, false, "").refusal())
                .isEqualTo("nothing to publish; set [publish] repository or [publish] release in jk.toml");
        assertThat(PublishLanes.select(List.of(), false, true, false, "", false).lanes())
                .as("a dry run previews the library lane with no target")
                .containsExactly(PublishLane.LIBRARY);
    }

    @Test
    void the_image_lane_is_refused_until_publish_pushes_images_and_otherwise_left_out() {
        assertThat(PublishLanes.select(List.of("image"), false, false, true, BUCKET, true)
                        .refusal())
                .isEqualTo("--only image: jk publish does not push images yet; use jk image");
        assertThat(PublishLanes.select(List.of(), false, false, true, BUCKET, true)
                        .lanes())
                .containsExactly(PublishLane.LIBRARY, PublishLane.RELEASE);
    }

    @Test
    void stage_runs_the_release_lane_alone() {
        assertThat(select(List.of(), true, true, BUCKET).lanes()).containsExactly(PublishLane.RELEASE);
        assertThat(select(List.of(), true, true, "").refusal()).isEqualTo("--stage needs [publish] release in jk.toml");
        assertThat(select(List.of("library"), true, true, BUCKET).refusal())
                .isEqualTo("--stage uploads release files only; drop --only library");
    }

    @Test
    void a_forge_target_is_refused_until_it_is_supported() {
        assertThat(select(List.of(), false, true, "github").refusal())
                .isEqualTo("release = \"github\" is not supported yet; use an s3:// or gs:// bucket");
        assertThat(select(List.of(), true, false, "forgejo").refusal())
                .isEqualTo("release = \"forgejo\" is not supported yet; use an s3:// or gs:// bucket");
        assertThat(select(List.of("library"), false, true, "gitlab").lanes()).containsExactly(PublishLane.LIBRARY);
    }

    @Test
    void stage_is_refused_on_a_platform_the_release_does_not_list() {
        assertThat(PublishCommand.stageRefusal(List.of(), "linux-x86_64")).isNull();
        assertThat(PublishCommand.stageRefusal(List.of("linux-x86_64", "macos-aarch64"), "linux-x86_64"))
                .isNull();
        assertThat(PublishCommand.stageRefusal(List.of("macos-aarch64", "windows-x86_64"), "linux-x86_64"))
                .isEqualTo("linux-x86_64 is not in [release] platforms; stage on macos-aarch64, windows-x86_64");
    }

    @Test
    void a_dry_run_prints_each_file_with_its_size_digest_destination_and_role() {
        ReleaseOutcome plan = new ReleaseOutcome(
                "s3://acme/app/1.0/",
                false,
                true,
                List.of(
                        new ReleaseOutcome.File(
                                "app-1.0-all.jar",
                                "app",
                                null,
                                42,
                                "ab12",
                                ReleaseOutcome.FINAL,
                                ReleaseOutcome.PLANNED),
                        new ReleaseOutcome.File(
                                "app-1.0-macos-aarch64.tar.gz",
                                "app",
                                "macos-aarch64",
                                -1,
                                "",
                                ReleaseOutcome.STAGING,
                                ReleaseOutcome.PLANNED)),
                null,
                List.of());
        assertThat(PublishCommand.releaseLines(plan))
                .containsExactly(
                        "Release s3://acme/app/1.0/ (dry run)",
                        "  planned  app-1.0-all.jar  42 bytes  sha256 ab12  -> s3://acme/app/1.0/app-1.0-all.jar  (final)",
                        "  planned  app-1.0-macos-aarch64.tar.gz  -> s3://acme/app/1.0/app-1.0-macos-aarch64.tar.gz"
                                + "  (staging)");
    }
}
