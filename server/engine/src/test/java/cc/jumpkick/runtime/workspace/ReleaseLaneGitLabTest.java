// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.ReleasePlatform;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.ReleaseDeliverables;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.release.ReleaseTarget;
import cc.jumpkick.runtime.base.FakeGitLab;
import cc.jumpkick.runtime.base.ReleaseRun;
import cc.jumpkick.runtime.base.ReleaseStore;
import cc.jumpkick.wire.runtime.PublishLane;
import cc.jumpkick.wire.runtime.ReleaseOutcome;
import cc.jumpkick.wire.runtime.TailArgs;
import java.io.IOException;
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
 * The release lane's workspace half against a loopback GitLab: a final run that lacks a listed
 * platform is refused before anything is written, the final run writes SHA256SUMS and index.json
 * (with each file's package URL) into the generic package and then creates the release linking
 * every file, a re-run changes nothing, and a release that exists without a file is refused
 * before anything builds.
 */
class ReleaseLaneGitLabTest {

    private static final String HOST = ReleasePlatform.current();
    private static final String OTHER = HOST.equals("macos-aarch64") ? "linux-x86_64" : "macos-aarch64";

    @TempDir
    Path tmp;

    private FakeGitLab fake;
    private String jar = "";
    private String hostImage = "";
    private String otherImage = "";

    @BeforeEach
    void start() throws IOException {
        fake = new FakeGitLab();
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    private JkBuild app() throws Exception {
        JkBuild root = JkBuildParser.parse(Files.writeString(tmp.resolve("jk.toml"), """
                name = "app"
                group = "com.example"
                version = "1.0"

                [application]
                main = "com.example.Main"
                assembly = true

                [jlink]

                [publish]
                release = "gitlab"
                sign = false
                sbom = false
                """));
        BuildLayout layout = BuildLayout.of(tmp, root);
        jar = ReleaseDeliverables.local(layout, root, null).getFirst().name();
        hostImage = ReleaseDeliverables.platformNames(layout, root, HOST).getFirst();
        otherImage = ReleaseDeliverables.platformNames(layout, root, OTHER).getFirst();
        return root;
    }

    private ReleaseRun run(JkBuild root, boolean dryRun) {
        return new ReleaseRun(
                tmp,
                root,
                "app",
                "1.0",
                "v1.0",
                ReleaseTarget.parse("gitlab"),
                fake.project(),
                List.of(HOST, OTHER),
                HOST,
                false,
                dryRun);
    }

    private static TailArgs.Publish args() {
        return new TailArgs.Publish(
                null,
                false,
                false,
                null,
                null,
                RepoCredential.ANONYMOUS,
                false,
                List.of(PublishLane.RELEASE),
                false,
                FakeGitLab.TOKEN,
                false);
    }

    private ReleaseStore store() {
        return fake.store("1.0", "v1.0", FakeGitLab.TOKEN, false);
    }

    @Test
    void a_final_run_that_lacks_a_platform_is_refused_before_it_writes() throws Exception {
        JkBuild root = app();
        fake.file("app", "1.0", hostImage, "host image");
        assertThat(ReleaseLane.missingPlatforms(run(root, false), store(), Map.of(tmp, root)))
                .containsExactly("release v1.0 lacks " + OTHER + "; run jk publish --stage on that platform first");
        assertThat(fake.releases).isEmpty();
        assertThat(fake.writes).hasValue(0);
    }

    @Test
    void the_final_run_writes_the_documents_then_creates_the_release_and_a_rerun_changes_nothing() throws Exception {
        JkBuild root = app();
        fake.file("app", "1.0", jar, "fat jar");
        fake.file("app", "1.0", hostImage, "host image");
        fake.file("app", "1.0", otherImage, "other image");
        List<ReleaseOutcome.File> modules = List.of(
                file(jar, null, "fat jar", ReleaseOutcome.UPLOADED),
                file(hostImage, HOST, "host image", ReleaseOutcome.STAGED));
        List<String> expected = List.of("app/" + OTHER + "/" + otherImage);

        ReleaseOutcome first = new ReleaseLane.Final(run(root, false), args(), modules, List.of())
                .over(store())
                .run(expected, tmp);

        String packages = fake.web() + FakeGitLab.PROJECT + "/packages/generic/app/1.0/";
        assertThat(first.finish()).isEqualTo("published v1.0");
        assertThat(first.destination())
                .isEqualTo(fake.project().packages(fake.project().api(), "app", "1.0"));
        assertThat(first.files())
                .extracting(ReleaseOutcome.File::name, ReleaseOutcome.File::status)
                .containsExactly(
                        tuple(jar, ReleaseOutcome.UPLOADED),
                        tuple(hostImage, ReleaseOutcome.STAGED),
                        tuple(otherImage, ReleaseOutcome.STAGED),
                        tuple("SHA256SUMS", ReleaseOutcome.UPLOADED),
                        tuple("index.json", ReleaseOutcome.UPLOADED));
        assertThat(fake.text("1.0", "SHA256SUMS"))
                .contains(Hashing.sha256Hex("other image") + "  " + otherImage + "\n");
        assertThat(Objects.requireNonNull(fake.text("1.0", "index.json")))
                .contains("\"tag\":\"v1.0\"")
                .contains("\"url\":\"" + packages + jar + "\"");
        assertThat(Objects.requireNonNull(fake.release("v1.0")).links())
                .containsOnlyKeys(jar, hostImage, otherImage, "SHA256SUMS", "index.json");

        int writes = fake.writes.get();
        ReleaseOutcome again = new ReleaseLane.Final(run(root, false), args(), modules, List.of())
                .over(store())
                .run(expected, tmp);
        assertThat(again.finish()).isEqualTo("v1.0 was already published");
        assertThat(again.files())
                .filteredOn(f -> f.module().isEmpty())
                .allMatch(f -> ReleaseOutcome.EXISTING.equals(f.status()));
        assertThat(fake.writes)
                .as("a re-run of a published release writes nothing")
                .hasValue(writes);
        assertThat(ReleaseLane.publishedWithout(run(root, false), store(), Map.of(tmp, root)))
                .isEmpty();
    }

    @Test
    void a_release_that_exists_without_a_file_is_refused_before_anything_builds() throws Exception {
        JkBuild root = app();
        fake.file("app", "1.0", hostImage, "host image");
        fake.file("app", "1.0", otherImage, "other image");
        fake.release("v1.0", hostImage, otherImage);

        assertThat(ReleaseLane.publishedWithout(run(root, false), store(), Map.of(tmp, root)))
                .containsExactly("v1.0 is already published without " + jar + "; raise the version");
        assertThat(fake.writes).hasValue(0);
    }

    @Test
    void a_dry_run_names_the_release_it_would_create_and_sends_nothing() throws Exception {
        JkBuild root = app();
        ReleaseOutcome plan = new ReleaseLane.Final(run(root, true), args(), List.of(), List.of())
                .run(List.of("app/" + OTHER + "/" + otherImage), tmp);

        assertThat(plan.files())
                .extracting(ReleaseOutcome.File::name)
                .containsExactly(otherImage, "SHA256SUMS", "index.json");
        assertThat(plan.finish()).isEqualTo("would create the release v1.0");
        assertThat(fake.releases).isEmpty();
        assertThat(fake.writes).hasValue(0);
    }

    private static ReleaseOutcome.File file(String name, @Nullable String platform, String body, String status) {
        return new ReleaseOutcome.File(
                name,
                "app",
                platform,
                body.length(),
                Hashing.sha256Hex(body),
                platform == null ? ReleaseOutcome.FINAL : ReleaseOutcome.STAGING,
                status);
    }
}
