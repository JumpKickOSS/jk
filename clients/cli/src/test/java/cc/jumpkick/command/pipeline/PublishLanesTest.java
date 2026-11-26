// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.forge.ForgeAuth;
import cc.jumpkick.forge.TokenStore;
import cc.jumpkick.wire.runtime.PublishLane;
import cc.jumpkick.wire.runtime.ReleaseOutcome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Which lanes a {@code jk publish} runs, in which order, and what it refuses before anything builds. */
class PublishLanesTest {

    private static final String BUCKET = "s3://acme/app/";

    private static PublishLanes select(List<String> only, boolean stage, boolean library, String release) {
        return PublishLanes.select(only, stage, false, library, release, false, false);
    }

    @Test
    void a_snapshot_runs_the_library_lane_alone_and_names_what_it_skips() {
        PublishLanes all = PublishLanes.select(List.of(), false, false, true, BUCKET, true, true);
        assertThat(all.lanes()).containsExactly(PublishLane.LIBRARY);
        assertThat(all.note()).isEqualTo("a -SNAPSHOT skips the release and image lanes");

        PublishLanes plain = PublishLanes.select(List.of(), false, false, true, "", false, true);
        assertThat(plain.lanes()).containsExactly(PublishLane.LIBRARY);
        assertThat(plain.note()).isNull();
    }

    @Test
    void a_snapshot_refuses_the_lanes_that_ship_releases() {
        assertThat(PublishLanes.select(List.of("release"), false, false, true, BUCKET, false, true)
                        .refusal())
                .isEqualTo("--only release: a -SNAPSHOT goes to the library lane only");
        assertThat(PublishLanes.select(List.of("image"), false, false, true, "", true, true)
                        .refusal())
                .isEqualTo("--only image: a -SNAPSHOT goes to the library lane only");
        assertThat(PublishLanes.select(List.of(), true, false, true, BUCKET, false, true)
                        .refusal())
                .isEqualTo("--stage ships a release; a -SNAPSHOT goes to the library lane only");
        assertThat(PublishLanes.select(List.of(), false, false, false, BUCKET, true, true)
                        .refusal())
                .isEqualTo("a -SNAPSHOT publishes libraries only; set [publish] repository");
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
                .isEqualTo("nothing to publish; set [publish] repository or release, or [image] registry");
        assertThat(PublishLanes.select(List.of(), false, true, false, "", false, false)
                        .lanes())
                .as("a dry run previews the library lane with no target")
                .containsExactly(PublishLane.LIBRARY);
    }

    @Test
    void a_registry_adds_the_image_lane_last() {
        assertThat(PublishLanes.select(List.of(), false, false, true, BUCKET, true, false)
                        .lanes())
                .containsExactly(PublishLane.LIBRARY, PublishLane.RELEASE, PublishLane.IMAGE);
        assertThat(PublishLanes.select(List.of("image,library"), false, false, true, BUCKET, true, false)
                        .lanes())
                .containsExactly(PublishLane.LIBRARY, PublishLane.IMAGE);
        assertThat(PublishLanes.select(List.of(), false, false, false, "", true, false)
                        .lanes())
                .as("a registry alone is something to publish")
                .containsExactly(PublishLane.IMAGE);
        assertThat(PublishLanes.select(List.of("image"), false, false, true, BUCKET, false, false)
                        .refusal())
                .isEqualTo("--only image needs [image] registry in jk.toml");
    }

    @Test
    void a_stage_run_never_pushes_an_image() {
        assertThat(PublishLanes.select(List.of(), true, false, true, BUCKET, true, false)
                        .lanes())
                .containsExactly(PublishLane.RELEASE);
        assertThat(PublishLanes.select(List.of("image"), true, false, false, BUCKET, true, false)
                        .refusal())
                .isEqualTo("--stage uploads release files only; drop --only image");
    }

    @Test
    void stage_runs_the_release_lane_alone() {
        assertThat(select(List.of(), true, true, BUCKET).lanes()).containsExactly(PublishLane.RELEASE);
        assertThat(select(List.of(), true, true, "").refusal()).isEqualTo("--stage needs [publish] release in jk.toml");
        assertThat(select(List.of("library"), true, true, BUCKET).refusal())
                .isEqualTo("--stage uploads release files only; drop --only library");
    }

    @Test
    void every_forge_is_a_release_target() {
        for (String forge : List.of("github", "gitlab", "forgejo")) {
            assertThat(select(List.of(), false, true, forge).lanes())
                    .as(forge)
                    .containsExactly(PublishLane.LIBRARY, PublishLane.RELEASE);
            assertThat(select(List.of(), true, false, forge).lanes()).as(forge).containsExactly(PublishLane.RELEASE);
        }
        assertThat(select(List.of("library"), false, true, "gitlab").lanes()).containsExactly(PublishLane.LIBRARY);
        assertThat(select(List.of(), true, false, "gitea").refusal())
                .isEqualTo("[publish] release: `gitea` is not github, gitlab, forgejo or an s3:// or gs:// URL");
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
                List.of(),
                List.of());
        assertThat(PublishCommand.releaseLines(plan))
                .containsExactly(
                        "Release s3://acme/app/1.0/ (dry run)",
                        "  planned  app-1.0-all.jar  42 bytes  sha256 ab12  -> s3://acme/app/1.0/app-1.0-all.jar  (final)",
                        "  planned  app-1.0-macos-aarch64.tar.gz  -> s3://acme/app/1.0/app-1.0-macos-aarch64.tar.gz"
                                + "  (staging)");
    }

    @Test
    void a_github_release_takes_the_forge_token_for_the_host_of_origin(@TempDir Path dir) throws Exception {
        origin(dir, "git@github.example.com:acme/app.git");
        TokenStore store = new TokenStore(dir.resolve("creds"));
        ForgeAuth none = new ForgeAuth(store, k -> null, argv -> Optional.empty());
        assertThatThrownBy(() -> PublishLanes.releaseToken(dir, "github", none, k -> null))
                .hasMessage("no token for github.example.com; run jk auth login github or set GH_TOKEN");

        store.write("github.example.com", "stored-token");
        assertThat(PublishLanes.releaseToken(dir, "github", none, k -> null))
                .isEqualTo(new PublishLanes.ReleaseToken("stored-token", false));
        ForgeAuth env = new ForgeAuth(store, Map.of("GH_TOKEN", "env-token")::get, argv -> Optional.empty());
        assertThat(PublishLanes.releaseToken(dir, "github", env, k -> null).value())
                .isEqualTo("env-token");
    }

    @Test
    void a_forge_release_is_refused_when_origin_is_on_another_forge(@TempDir Path dir) throws Exception {
        origin(dir, "https://gitlab.com/acme/app.git");
        ForgeAuth auth = new ForgeAuth(new TokenStore(dir.resolve("creds")), k -> "t", argv -> Optional.empty());
        assertThatThrownBy(() -> PublishLanes.releaseToken(dir, "github", auth, k -> null))
                .hasMessage("origin https://gitlab.com/acme/app.git is not a GitHub remote");
        assertThatThrownBy(() -> PublishLanes.releaseToken(dir, "forgejo", auth, k -> null))
                .hasMessage("origin https://gitlab.com/acme/app.git is not a Forgejo remote");
    }

    @Test
    void a_gitlab_release_takes_the_chain_then_the_ci_job_token(@TempDir Path dir) throws Exception {
        origin(dir, "git@gitlab.acme.corp:platform/tools/app.git");
        TokenStore store = new TokenStore(dir.resolve("creds"));
        ForgeAuth none = new ForgeAuth(store, k -> null, argv -> Optional.empty());
        assertThatThrownBy(() -> PublishLanes.releaseToken(dir, "gitlab", none, k -> null))
                .hasMessage("no token for gitlab.acme.corp; run jk auth login gitlab or set GITLAB_TOKEN");

        Map<String, String> ci = Map.of("CI_JOB_TOKEN", "job-token");
        assertThat(PublishLanes.releaseToken(dir, "gitlab", none, ci::get))
                .isEqualTo(new PublishLanes.ReleaseToken("job-token", true));
        ForgeAuth env = new ForgeAuth(store, Map.of("GITLAB_TOKEN", "pat")::get, argv -> Optional.empty());
        assertThat(PublishLanes.releaseToken(dir, "gitlab", env, ci::get))
                .as("a configured token wins over the job's")
                .isEqualTo(new PublishLanes.ReleaseToken("pat", false));
    }

    @Test
    void a_forgejo_release_takes_the_gitea_chain_for_the_host_of_origin(@TempDir Path dir) throws Exception {
        origin(dir, "https://codeberg.org/acme/app.git");
        TokenStore store = new TokenStore(dir.resolve("creds"));
        ForgeAuth none = new ForgeAuth(store, k -> null, argv -> Optional.empty());
        Map<String, String> ci = Map.of("CI_JOB_TOKEN", "job-token");
        assertThatThrownBy(() -> PublishLanes.releaseToken(dir, "forgejo", none, ci::get))
                .as("a job token is GitLab's alone")
                .hasMessage("no token for codeberg.org; run jk auth login forgejo or set FORGEJO_TOKEN");

        store.write("codeberg.org", "stored-token");
        assertThat(PublishLanes.releaseToken(dir, "forgejo", none, k -> null).value())
                .isEqualTo("stored-token");
        ForgeAuth env = new ForgeAuth(store, Map.of("FORGEJO_TOKEN", "env-token")::get, argv -> Optional.empty());
        assertThat(PublishLanes.releaseToken(dir, "forgejo", env, k -> null).value())
                .isEqualTo("env-token");
    }

    @Test
    void a_github_dry_run_names_the_draft_it_would_publish() {
        ReleaseOutcome plan = new ReleaseOutcome(
                "https://github.com/acme/app/releases/download/v1.0/",
                false,
                true,
                List.of(new ReleaseOutcome.File(
                        "SHA256SUMS", "", null, -1, "", ReleaseOutcome.FINAL, ReleaseOutcome.PLANNED)),
                "would publish the draft release v1.0",
                List.of(),
                List.of());
        assertThat(PublishCommand.releaseLines(plan))
                .containsExactly(
                        "Release https://github.com/acme/app/releases/download/v1.0/ (dry run)",
                        "  planned  SHA256SUMS  -> https://github.com/acme/app/releases/download/v1.0/SHA256SUMS"
                                + "  (final)",
                        "  would publish the draft release v1.0");
    }

    private static void origin(Path dir, String url) throws Exception {
        Files.createDirectories(dir.resolve(".git"));
        Files.writeString(dir.resolve(".git/config"), "[remote \"origin\"]\n\turl = " + url + "\n");
    }
}
