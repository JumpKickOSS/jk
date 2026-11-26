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
import cc.jumpkick.runtime.base.FakeForgejo;
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
 * The release lane's workspace half against a loopback Forgejo: a final run that lacks a listed
 * platform is refused before anything is written, the final run writes SHA256SUMS and index.json
 * (no URLs: Forgejo names them) into the draft and publishes it, a re-run changes nothing, and a
 * published release that lacks a file is refused before anything builds.
 */
class ReleaseLaneForgejoTest {

    private static final String HOST = ReleasePlatform.current();
    private static final String OTHER = HOST.equals("macos-aarch64") ? "linux-x86_64" : "macos-aarch64";

    @TempDir
    Path tmp;

    private FakeForgejo fake;
    private String jar = "";
    private String hostImage = "";
    private String otherImage = "";

    @BeforeEach
    void start() throws IOException {
        fake = new FakeForgejo();
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
                release = "forgejo"
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
                ReleaseTarget.parse("forgejo"),
                fake.repository(),
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
                FakeForgejo.TOKEN,
                false);
    }

    private ReleaseStore store() {
        return fake.store("v1.0", FakeForgejo.TOKEN);
    }

    @Test
    void a_final_run_that_lacks_a_platform_is_refused_before_it_writes() throws Exception {
        JkBuild root = app();
        assertThat(ReleaseLane.missingPlatforms(run(root, false), store(), Map.of(tmp, root)))
                .containsExactly("release v1.0 lacks " + OTHER + "; run jk publish --stage on that platform first");
        assertThat(fake.releases).isEmpty();
        assertThat(fake.writes).hasValue(0);
    }

    @Test
    void the_final_run_writes_the_documents_then_publishes_the_draft_and_a_rerun_changes_nothing() throws Exception {
        JkBuild root = app();
        FakeForgejo.Release draft = fake.release("v1.0", true);
        fake.asset(draft, jar, "fat jar");
        fake.asset(draft, hostImage, "host image");
        fake.asset(draft, otherImage, "other image");
        List<ReleaseOutcome.File> modules = List.of(
                file(jar, null, "fat jar", ReleaseOutcome.UPLOADED),
                file(hostImage, HOST, "host image", ReleaseOutcome.STAGED));
        List<String> expected = List.of("app/" + OTHER + "/" + otherImage);

        ReleaseOutcome first = new ReleaseLane.Final(run(root, false), args(), modules, List.of())
                .over(store())
                .run(expected, tmp);

        assertThat(first.finish()).isEqualTo("published v1.0");
        assertThat(first.destination()).isEqualTo(fake.web() + "/acme/app/releases/download/v1.0/");
        assertThat(first.files())
                .extracting(ReleaseOutcome.File::name, ReleaseOutcome.File::status)
                .containsExactly(
                        tuple(jar, ReleaseOutcome.UPLOADED),
                        tuple(hostImage, ReleaseOutcome.STAGED),
                        tuple(otherImage, ReleaseOutcome.STAGED),
                        tuple("SHA256SUMS", ReleaseOutcome.UPLOADED),
                        tuple("index.json", ReleaseOutcome.UPLOADED));
        assertThat(draft.draft).isFalse();
        assertThat(fake.text("v1.0", "SHA256SUMS"))
                .contains(Hashing.sha256Hex("other image") + "  " + otherImage + "\n");
        assertThat(Objects.requireNonNull(fake.text("v1.0", "index.json")))
                .contains("\"tag\":\"v1.0\"")
                .doesNotContain("\"url\"");

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
    void a_published_release_that_lacks_a_file_is_refused_before_anything_builds() throws Exception {
        JkBuild root = app();
        FakeForgejo.Release out = fake.release("v1.0", false);
        fake.asset(out, hostImage, "host image");
        fake.asset(out, otherImage, "other image");

        assertThat(ReleaseLane.publishedWithout(run(root, false), store(), Map.of(tmp, root)))
                .containsExactly("v1.0 is already published without " + jar + "; raise the version");
        assertThat(fake.writes).hasValue(0);
    }

    @Test
    void a_dry_run_names_the_draft_it_would_publish_and_sends_nothing() throws Exception {
        JkBuild root = app();
        ReleaseOutcome plan = new ReleaseLane.Final(run(root, true), args(), List.of(), List.of())
                .run(List.of("app/" + OTHER + "/" + otherImage), tmp);

        assertThat(plan.files())
                .extracting(ReleaseOutcome.File::name)
                .containsExactly(otherImage, "SHA256SUMS", "index.json");
        assertThat(plan.finish()).isEqualTo("would publish the draft release v1.0");
        assertThat(fake.releases).isEmpty();
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
