// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A GitLab release as a release store, against a loopback API: files go to the generic package
 * with no release yet, a file already there with the same bytes is left alone (by the listed
 * checksum, else by reading it), other bytes stop the run, publishing creates the release with a
 * link to every file once, a release that exists takes no new file, and a refusal reads as one
 * line that never shows the token.
 */
class ReleaseGitLabTest {

    @TempDir
    Path tmp;

    private FakeGitLab fake;

    @BeforeEach
    void start() throws IOException {
        fake = new FakeGitLab();
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    private ReleaseGitLab store() {
        return fake.store("1.0", "v1.0", FakeGitLab.TOKEN, false);
    }

    private Path file(String name, String text) throws IOException {
        return Files.writeString(tmp.resolve(name), text);
    }

    @Test
    void a_staged_file_goes_to_the_generic_package_and_no_release_yet() throws Exception {
        ReleaseGitLab store = store();
        assertThat(store.published()).isFalse();
        assertThat(store.present("app-1.0-linux-x86_64.tar.gz")).isFalse();

        assertThat(store.put("app-1.0-linux-x86_64.tar.gz", file("image.tar.gz", "image")))
                .isEqualTo(ReleaseStore.Put.UPLOADED);

        assertThat(fake.text("1.0", "app-1.0-linux-x86_64.tar.gz")).isEqualTo("image");
        assertThat(fake.releases).isEmpty();
        assertThat(store.present("app-1.0-linux-x86_64.tar.gz")).isTrue();
        assertThat(store.held("app-1.0-linux-x86_64.tar.gz"))
                .contains(new ReleaseStore.Held(5, Hashing.sha256Hex("image")));
        assertThat(store.url("app-1.0-linux-x86_64.tar.gz"))
                .isEqualTo(fake.web() + FakeGitLab.PROJECT + "/packages/generic/app/1.0/app-1.0-linux-x86_64.tar.gz");
        assertThat(fake.auth).containsOnly("bearer");
    }

    @Test
    void only_the_package_of_this_name_and_version_counts() throws Exception {
        fake.file("app-cli", "1.0", "app-1.0-all.jar", "someone else's");
        fake.file("app", "0.9", "app-1.0-all.jar", "an older version's");

        assertThat(store().present("app-1.0-all.jar")).isFalse();
        assertThat(store().put("app-1.0-all.jar", file("app.jar", "fat jar"))).isEqualTo(ReleaseStore.Put.UPLOADED);
    }

    @Test
    void a_file_with_the_same_bytes_is_skipped_by_its_checksum() throws Exception {
        fake.file("app", "1.0", "app-1.0-all.jar", "fat jar");

        assertThat(store().put("app-1.0-all.jar", file("app.jar", "fat jar"))).isEqualTo(ReleaseStore.Put.EXISTING);
        assertThat(fake.writes).hasValue(0);
        assertThat(fake.downloads).as("the listed checksum settles it").hasValue(0);
    }

    @Test
    void without_a_listed_checksum_the_file_is_read_and_hashed() throws Exception {
        fake.checksums = false;
        fake.file("app", "1.0", "app-1.0-all.jar", "fat jar");

        ReleaseGitLab store = store();
        assertThat(store.put("app-1.0-all.jar", file("app.jar", "fat jar"))).isEqualTo(ReleaseStore.Put.EXISTING);
        assertThat(store.held("app-1.0-all.jar")).contains(new ReleaseStore.Held(7, Hashing.sha256Hex("fat jar")));
        assertThat(fake.downloads.get()).isPositive();
        assertThat(fake.writes).hasValue(0);
    }

    @Test
    void a_file_with_other_bytes_stops_the_run() throws Exception {
        fake.file("app", "1.0", "app-1.0-all.jar", "fat jar");

        assertThatThrownBy(() -> store().put("app-1.0-all.jar", file("app.jar", "rebuilt")))
                .isInstanceOf(IOException.class)
                .hasMessage("app-1.0-all.jar is already in the package app 1.0 with other bytes;"
                        + " raise the version in jk.toml");
        assertThat(fake.writes).hasValue(0);
    }

    @Test
    void a_group_that_refuses_duplicates_answers_a_race_with_the_file_that_won() throws Exception {
        fake.duplicates = false;
        ReleaseGitLab store = store();
        assertThat(store.present("SHA256SUMS"))
                .as("the list is read before the race")
                .isFalse();
        fake.file("app", "1.0", "SHA256SUMS", "sums");

        assertThat(store.put("SHA256SUMS", "sums".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(ReleaseStore.Put.EXISTING);
    }

    @Test
    void publishing_creates_the_release_with_a_link_to_every_file_once() throws Exception {
        fake.file("app", "1.0", "app-1.0-all.jar", "fat jar");
        fake.file("app", "1.0", "SHA256SUMS", "sums");
        ReleaseGitLab store = store();

        assertThat(store.publish()).isEqualTo("published v1.0");
        FakeGitLab.Release release = Objects.requireNonNull(fake.release("v1.0"));
        assertThat(release.ref()).isEqualTo("c0ffee");
        assertThat(release.links())
                .containsOnlyKeys("app-1.0-all.jar", "SHA256SUMS")
                .containsEntry("app-1.0-all.jar", store.url("app-1.0-all.jar"));
        assertThat(store().published()).isTrue();

        int writes = fake.writes.get();
        assertThat(store().publish()).isEqualTo("v1.0 was already published");
        assertThat(fake.writes).hasValue(writes);
    }

    @Test
    void a_published_release_takes_no_new_file() throws Exception {
        fake.file("app", "1.0", "app-1.0-all.jar", "fat jar");
        fake.release("v1.0", "app-1.0-all.jar");
        ReleaseGitLab store = store();

        assertThat(store.present("app-1.0-all.jar")).isTrue();
        assertThat(store.put("app-1.0-all.jar", file("app.jar", "fat jar"))).isEqualTo(ReleaseStore.Put.EXISTING);
        assertThatThrownBy(() -> store.put("SHA256SUMS", "sums".getBytes(StandardCharsets.UTF_8)))
                .hasMessage("v1.0 is already published without SHA256SUMS; raise the version");
        assertThat(fake.writes).hasValue(0);
    }

    @Test
    void a_file_the_release_does_not_link_is_not_in_it() throws Exception {
        fake.file("app", "1.0", "app-1.0-all.jar", "fat jar");
        fake.file("app", "1.0", "SHA256SUMS", "sums");
        fake.release("v1.0", "app-1.0-all.jar");

        assertThat(store().present("SHA256SUMS")).isFalse();
    }

    @Test
    void the_ci_job_token_goes_as_job_token() throws Exception {
        ReleaseGitLab store = fake.store("1.0", "v1.0", FakeGitLab.JOB_TOKEN, true);
        assertThat(store.put("app-1.0-all.jar", file("app.jar", "fat jar"))).isEqualTo(ReleaseStore.Put.UPLOADED);
        assertThat(store.publish()).isEqualTo("published v1.0");
        assertThat(fake.auth).containsOnly("job");
    }

    @Test
    void a_refusal_names_what_the_token_needs_and_never_shows_it() {
        fake.refuse = 403;
        assertThatThrownBy(() -> store().put("SHA256SUMS", new byte[] {1})).hasMessage(ReleaseGitLab.SCOPE);
        assertThat(ReleaseGitLab.SCOPE).hasSizeLessThanOrEqualTo(78);

        fake.refuse = 0;
        assertThatThrownBy(() ->
                        fake.store("1.0", "v1.0", "wrong-token-entirely", false).published())
                .hasMessage("GitLab refused the token; run jk auth login gitlab or set GITLAB_TOKEN")
                .hasMessageNotContaining("wrong-token-entirely");

        fake.refuse = 429;
        assertThatThrownBy(() -> store().published()).hasMessage("GitLab rate limit reached; try again later");
    }

    @Test
    void a_package_file_downloads_checked_against_its_checksum() throws Exception {
        fake.file("app", "1.0", "app-1.0-linux-aarch64", "arm ELF");
        ReleaseGitLab store = store();
        Path file = tmp.resolve("target/staged/app-1.0-linux-aarch64");

        assertThat(store.fetch("app-1.0-linux-aarch64", file))
                .contains(new ReleaseStore.Held(7, Hashing.sha256Hex("arm ELF")));
        assertThat(file).hasContent("arm ELF");
        assertThat(store.fetch("app-1.0-linux-s390x", tmp.resolve("absent"))).isEmpty();
        assertThat(tmp.resolve("absent")).doesNotExist();
    }
}
