// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A GitHub Release as a release store, against a loopback API: the first upload creates the draft,
 * an asset already there with the same bytes is left alone (by the listed digest, else by reading
 * it), other bytes stop the run, an empty asset a broken upload left is replaced, publishing flips
 * the draft, and a refusal reads as one line naming the scope or the rate limit.
 */
class ReleaseGitHubTest {

    @TempDir
    Path tmp;

    private FakeGitHub fake;

    @BeforeEach
    void start() throws IOException {
        fake = new FakeGitHub();
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    private Path file(String name, String text) throws IOException {
        return Files.writeString(tmp.resolve(name), text);
    }

    @Test
    void the_first_upload_creates_the_draft_at_the_tagged_commit() throws Exception {
        ReleaseGitHub store = fake.store("v1.0", FakeGitHub.TOKEN);
        assertThat(store.published()).isFalse();
        assertThat(store.present("app-1.0-all.jar")).isFalse();

        assertThat(store.put("app-1.0-all.jar", file("app.jar", "fat jar"))).isEqualTo(ReleaseStore.Put.UPLOADED);

        assertThat(fake.releases).singleElement().satisfies(r -> {
            assertThat(r.tag).isEqualTo("v1.0");
            assertThat(r.draft).isTrue();
            assertThat(r.commitish).isEqualTo("c0ffee");
        });
        assertThat(fake.text("v1.0", "app-1.0-all.jar")).isEqualTo("fat jar");
        assertThat(store.held("app-1.0-all.jar")).contains(new ReleaseStore.Held(7, Hashing.sha256Hex("fat jar")));
        assertThat(store.url("app-1.0-all.jar"))
                .as("GitHub names its own download URL")
                .isNull();

        ReleaseGitHub other = fake.store("v1.0", FakeGitHub.TOKEN);
        assertThat(other.put("SHA256SUMS", "sums".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(ReleaseStore.Put.UPLOADED);
        assertThat(fake.releases).as("a second store finds the draft").hasSize(1);
    }

    @Test
    void an_asset_with_the_same_bytes_is_skipped_by_its_digest() throws Exception {
        FakeGitHub.Release draft = fake.release("v1.0", true);
        fake.asset(draft, "app-1.0-all.jar", "fat jar");

        assertThat(fake.store("v1.0", FakeGitHub.TOKEN).put("app-1.0-all.jar", file("app.jar", "fat jar")))
                .isEqualTo(ReleaseStore.Put.EXISTING);
        assertThat(fake.writes).hasValue(0);
        assertThat(fake.downloads).as("the listed digest settles it").hasValue(0);
    }

    @Test
    void without_a_listed_digest_the_asset_is_read_and_hashed() throws Exception {
        fake.digests = false;
        FakeGitHub.Release draft = fake.release("v1.0", true);
        fake.asset(draft, "app-1.0-all.jar", "fat jar");

        ReleaseGitHub store = fake.store("v1.0", FakeGitHub.TOKEN);
        assertThat(store.put("app-1.0-all.jar", file("app.jar", "fat jar"))).isEqualTo(ReleaseStore.Put.EXISTING);
        assertThat(store.held("app-1.0-all.jar")).contains(new ReleaseStore.Held(7, Hashing.sha256Hex("fat jar")));
        assertThat(fake.downloads.get()).isPositive();
        assertThat(fake.writes).hasValue(0);
    }

    @Test
    void an_asset_with_other_bytes_stops_the_run() throws Exception {
        FakeGitHub.Release draft = fake.release("v1.0", true);
        fake.asset(draft, "app-1.0-all.jar", "fat jar");

        assertThatThrownBy(
                        () -> fake.store("v1.0", FakeGitHub.TOKEN).put("app-1.0-all.jar", file("app.jar", "rebuilt")))
                .isInstanceOf(IOException.class)
                .hasMessage("app-1.0-all.jar is already in v1.0 with other bytes; raise the version in jk.toml");
        assertThat(fake.text("v1.0", "app-1.0-all.jar")).isEqualTo("fat jar");
    }

    @Test
    void an_empty_asset_a_broken_upload_left_is_replaced() throws Exception {
        FakeGitHub.Release draft = fake.release("v1.0", true);
        fake.starter(draft, "app-1.0-all.jar");

        ReleaseGitHub store = fake.store("v1.0", FakeGitHub.TOKEN);
        assertThat(store.present("app-1.0-all.jar")).isFalse();
        assertThat(store.put("app-1.0-all.jar", file("app.jar", "fat jar"))).isEqualTo(ReleaseStore.Put.UPLOADED);
        assertThat(fake.text("v1.0", "app-1.0-all.jar")).isEqualTo("fat jar");
    }

    @Test
    void publishing_flips_the_draft_once() throws Exception {
        fake.release("v1.0", true);
        ReleaseGitHub store = fake.store("v1.0", FakeGitHub.TOKEN);

        assertThat(store.publish()).isEqualTo("published v1.0");
        assertThat(fake.releases.getFirst().draft).isFalse();
        assertThat(fake.store("v1.0", FakeGitHub.TOKEN).published()).isTrue();
        int writes = fake.writes.get();
        assertThat(fake.store("v1.0", FakeGitHub.TOKEN).publish()).isEqualTo("v1.0 was already published");
        assertThat(fake.writes).hasValue(writes);
    }

    @Test
    void a_published_release_takes_no_new_asset() throws Exception {
        FakeGitHub.Release out = fake.release("v1.0", false);
        fake.asset(out, "app-1.0-all.jar", "fat jar");
        ReleaseGitHub store = fake.store("v1.0", FakeGitHub.TOKEN);

        assertThat(store.put("app-1.0-all.jar", file("app.jar", "fat jar"))).isEqualTo(ReleaseStore.Put.EXISTING);
        assertThatThrownBy(() -> store.put("SHA256SUMS", "sums".getBytes(StandardCharsets.UTF_8)))
                .hasMessage("v1.0 is already published without SHA256SUMS; raise the version");
        assertThat(fake.writes).hasValue(0);
    }

    @Test
    void a_refusal_names_the_scope_the_release_needs() {
        fake.refuse = 403;
        assertThatThrownBy(() -> fake.store("v1.0", FakeGitHub.TOKEN).put("SHA256SUMS", new byte[] {1}))
                .hasMessage("GitHub refused the release; the token needs repo scope or contents: write");
        assertThat(ReleaseGitHub.SCOPE.length()).isLessThanOrEqualTo(78);

        fake.refuse = 404;
        assertThatThrownBy(() -> fake.store("v1.0", FakeGitHub.TOKEN).published())
                .hasMessage(ReleaseGitHub.SCOPE);
    }

    @Test
    void a_token_github_does_not_take_says_how_to_log_in() {
        assertThatThrownBy(() -> fake.store("v1.0", "wrong-token-entirely").published())
                .hasMessage("GitHub refused the token; run jk auth login github or set GH_TOKEN")
                .hasMessageNotContaining("wrong-token-entirely");
    }

    @Test
    void a_rate_limit_fails_with_when_to_try_again_instead_of_retrying() {
        fake.refuse = 403;
        fake.refusalHeaders = Map.of("x-ratelimit-remaining", "0", "x-ratelimit-reset", "1800000000");
        assertThatThrownBy(() -> fake.store("v1.0", FakeGitHub.TOKEN).published())
                .hasMessage("GitHub rate limit reached; try again after 08:00 UTC");

        fake.refusalHeaders = Map.of("retry-after", "60");
        assertThatThrownBy(() -> fake.store("v1.0", FakeGitHub.TOKEN).published())
                .hasMessage("GitHub rate limit reached; try again in 60 s");
    }
}
