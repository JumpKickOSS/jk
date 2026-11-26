// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.ReleaseDeliverables;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.release.ReleaseTarget;
import cc.jumpkick.repo.ReleaseVerifier;
import cc.jumpkick.runtime.base.FakeReleaseBucket;
import cc.jumpkick.runtime.base.ReleaseBucket;
import cc.jumpkick.runtime.base.ReleaseRun;
import cc.jumpkick.wire.runtime.PublishLane;
import cc.jumpkick.wire.runtime.ReleaseOutcome;
import cc.jumpkick.wire.runtime.TailArgs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The release lane's workspace half against a loopback bucket: a final run refuses a release that
 * lacks a listed platform before it writes anything, writes SHA256SUMS, index.json and latest.json
 * once every platform is there, and a re-run finds them and changes nothing.
 */
class ReleaseLaneTest {

    private static final String MAC = "macos-aarch64";
    private static final String LINUX = "linux-x86_64";

    @TempDir
    Path tmp;

    private FakeReleaseBucket fake;

    @BeforeEach
    void start() throws IOException {
        fake = new FakeReleaseBucket();
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    private JkBuild app() throws Exception {
        return app("sign = false");
    }

    private JkBuild app(String signing) throws Exception {
        return JkBuildParser.parse(Files.writeString(tmp.resolve("jk.toml"), """
                name = "app"
                group = "com.example"
                version = "1.0"

                [application]
                main = "com.example.Main"
                assembly = true

                [jlink]

                [publish]
                release = "s3://releases/app/"
                """ + signing + "\n"));
    }

    private ReleaseRun run(JkBuild root, boolean stage, boolean dryRun) {
        return new ReleaseRun(
                tmp,
                root,
                "app",
                "1.0",
                "v1.0",
                ReleaseTarget.parse("s3://releases/app/"),
                null,
                List.of(LINUX, MAC),
                LINUX,
                stage,
                dryRun);
    }

    private static TailArgs.Publish args(boolean stage, boolean dryRun) {
        return new TailArgs.Publish(
                null,
                false,
                dryRun,
                null,
                null,
                RepoCredential.ANONYMOUS,
                false,
                List.of(PublishLane.RELEASE),
                stage,
                null);
    }

    private static ReleaseOutcome.File uploaded(String name, @Nullable String platform, String body) {
        return new ReleaseOutcome.File(
                name,
                "app",
                platform,
                body.length(),
                Hashing.sha256Hex(body),
                platform == null ? ReleaseOutcome.FINAL : ReleaseOutcome.STAGING,
                ReleaseOutcome.UPLOADED);
    }

    @Test
    void a_final_run_names_the_platform_whose_files_are_not_staged() throws Exception {
        JkBuild root = app();
        ReleaseRun run = run(root, false, false);
        ReleaseBucket bucket = fake.bucket("app/", "1.0");
        String macImage = ReleaseDeliverables.platformNames(BuildLayout.of(tmp, root), root, MAC)
                .getFirst();

        // The preflight, before anything builds.
        assertThat(ReleaseLane.missingPlatforms(run, bucket, Map.of(tmp, root)))
                .containsExactly("release v1.0 lacks macos-aarch64; run jk publish --stage on that platform first");

        // And the final step itself, should a staged file vanish mid-run: nothing is written.
        ReleaseLane.Final lane = new ReleaseLane.Final(run, args(false, false), List.of(), List.of()).over(bucket);
        assertThatThrownBy(() -> lane.run(List.of("app/" + MAC + "/" + macImage), tmp))
                .isInstanceOf(IOException.class)
                .hasMessage("release v1.0 lacks macos-aarch64; run jk publish --stage on that platform first");
        assertThat(fake.objects).isEmpty();

        fake.putText("app/1.0/" + macImage, "mac image");
        assertThat(ReleaseLane.missingPlatforms(run, bucket, Map.of(tmp, root))).isEmpty();
    }

    @Test
    void a_final_run_writes_the_sums_the_index_and_latest_and_a_rerun_changes_nothing() throws Exception {
        JkBuild root = app();
        ReleaseRun run = run(root, false, false);
        ReleaseBucket bucket = fake.bucket("app/", "1.0");
        String macImage = "app-1.0-" + MAC + ".tar.gz";
        fake.putText("app/1.0/app-1.0-all.jar", "fat jar");
        fake.putText("app/1.0/" + macImage, "mac image");
        List<ReleaseOutcome.File> modules = List.of(uploaded("app-1.0-all.jar", null, "fat jar"));
        List<String> expected = List.of("app/" + MAC + "/" + macImage);

        ReleaseOutcome first = new ReleaseLane.Final(run, args(false, false), modules, List.of())
                .over(bucket)
                .run(expected, tmp);

        assertThat(first.finish()).isEqualTo("latest.json names 1.0");
        assertThat(first.files())
                .extracting(ReleaseOutcome.File::name, ReleaseOutcome.File::status)
                .containsExactly(
                        tuple("app-1.0-all.jar", ReleaseOutcome.UPLOADED),
                        tuple(macImage, ReleaseOutcome.STAGED),
                        tuple("SHA256SUMS", ReleaseOutcome.UPLOADED),
                        tuple("index.json", ReleaseOutcome.UPLOADED));
        byte[] sums = Objects.requireNonNull(fake.text("app/1.0/SHA256SUMS")).getBytes(StandardCharsets.UTF_8);
        assertThat(ReleaseVerifier.find(sums, "app-1.0-all.jar")).contains(Hashing.sha256Hex("fat jar"));
        assertThat(ReleaseVerifier.find(sums, macImage)).contains(Hashing.sha256Hex("mac image"));
        assertThat(fake.text("app/1.0/index.json"))
                .contains("\"tag\":\"v1.0\"")
                .contains("\"os\":\"macos\",\"arch\":\"aarch64\"")
                .contains("\"url\":\"s3://releases/app/1.0/app-1.0-all.jar\"");
        assertThat(fake.text("app/latest.json")).contains("\"1.0/index.json\"");
        assertThat(Files.readString(tmp.resolve("target/release/1.0/SHA256SUMS")))
                .isEqualTo(fake.text("app/1.0/SHA256SUMS"));

        int writes = fake.writes.get();
        ReleaseOutcome again = new ReleaseLane.Final(run, args(false, false), modules, List.of())
                .over(bucket)
                .run(expected, tmp);
        assertThat(again.files())
                .filteredOn(f -> f.module().isEmpty())
                .allMatch(f -> ReleaseOutcome.EXISTING.equals(f.status()));
        assertThat(fake.writes).as("a re-run writes nothing").hasValue(writes);
        assertThat(fake.unguarded).hasValue(0);
    }

    @Test
    void a_release_nothing_could_sign_says_so_and_one_signing_is_off_for_does_not() throws Exception {
        fake.putText("app/1.0/app-1.0-all.jar", "fat jar");
        List<ReleaseOutcome.File> modules = List.of(uploaded("app-1.0-all.jar", null, "fat jar"));
        ReleaseOutcome unsigned = new ReleaseLane.Final(
                        run(app(""), false, false), args(false, false), modules, List.of())
                .over(fake.bucket("app/", "1.0"))
                .env(name -> null)
                .run(List.of(), tmp);
        assertThat(unsigned.notes())
                .containsExactly("release v1.0 is unsigned: set [publish] key-file, or sign = false");
        assertThat(unsigned.notes().getFirst()).hasSizeLessThanOrEqualTo(78);

        ReleaseOutcome off = new ReleaseLane.Final(run(app(), false, false), args(false, false), modules, List.of())
                .over(fake.bucket("app/", "1.0"))
                .run(List.of(), tmp);
        assertThat(off.notes()).isEmpty();
    }

    @Test
    void a_stage_run_reports_its_uploads_and_writes_no_release_document() throws Exception {
        JkBuild root = app();
        List<ReleaseOutcome.File> staged = List.of(uploaded("app-1.0-" + LINUX + ".tar.gz", LINUX, "linux image"));
        ReleaseOutcome outcome = new ReleaseLane.Final(run(root, true, false), args(true, false), staged, List.of())
                .over(fake.bucket("app/", "1.0"))
                .run(List.of(), tmp);

        assertThat(outcome.stage()).isTrue();
        assertThat(outcome.finish()).isNull();
        assertThat(outcome.files()).containsExactlyElementsOf(staged);
        assertThat(fake.objects).isEmpty();
    }

    @Test
    void a_dry_run_lists_every_file_and_document_and_sends_nothing() throws Exception {
        JkBuild root = app();
        String macImage = "app-1.0-" + MAC + ".tar.gz";
        ReleaseOutcome plan = new ReleaseLane.Final(run(root, false, true), args(false, true), List.of(), List.of())
                .run(List.of("app/" + MAC + "/" + macImage), tmp);

        assertThat(plan.files())
                .extracting(ReleaseOutcome.File::name)
                .containsExactly(macImage, "SHA256SUMS", "index.json");
        assertThat(plan.finish()).isEqualTo("would point latest.json at 1.0");
        assertThat(plan.files()).allMatch(f -> ReleaseOutcome.PLANNED.equals(f.status()));
        assertThat(plan.files().getFirst().role()).isEqualTo(ReleaseOutcome.STAGING);
        assertThat(plan.files().getLast().role()).isEqualTo(ReleaseOutcome.FINAL);
        assertThat(fake.objects).isEmpty();
    }
}
