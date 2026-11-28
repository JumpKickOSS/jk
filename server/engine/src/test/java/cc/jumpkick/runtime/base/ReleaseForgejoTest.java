// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A Forgejo release as a release store, against a loopback API: the first upload creates the
 * draft at the tagged commit, an attachment already there with the same bytes is left alone (read
 * through its UUID, since Forgejo lists no digest), other bytes stop the run, publishing flips the
 * draft once, a published release takes no new attachment, and a refusal reads as one line.
 */
class ReleaseForgejoTest {

    @TempDir
    Path tmp;

    private FakeForgejo fake;

    @BeforeEach
    void start() throws IOException {
        fake = new FakeForgejo();
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
        ReleaseForgejo store = fake.store("v1.0", FakeForgejo.TOKEN);
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
                .as("Forgejo names its own download URL")
                .isNull();

        assertThat(fake.store("v1.0", FakeForgejo.TOKEN).put("SHA256SUMS", "sums".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(ReleaseStore.Put.UPLOADED);
        assertThat(fake.releases).as("a second store finds the draft").hasSize(1);
    }

    @Test
    void an_attachment_with_the_same_bytes_is_skipped_after_reading_it() throws Exception {
        FakeForgejo.Release draft = fake.release("v1.0", true);
        fake.asset(draft, "app-1.0-all.jar", "fat jar");

        assertThat(fake.store("v1.0", FakeForgejo.TOKEN).put("app-1.0-all.jar", file("app.jar", "fat jar")))
                .isEqualTo(ReleaseStore.Put.EXISTING);
        assertThat(fake.writes).hasValue(0);
        assertThat(fake.downloads).as("read once through /attachments/").hasValue(1);
    }

    @Test
    void an_attachment_of_another_size_stops_the_run_without_reading_it() throws Exception {
        FakeForgejo.Release draft = fake.release("v1.0", true);
        fake.asset(draft, "app-1.0-all.jar", "fat jar");

        assertThatThrownBy(() ->
                        fake.store("v1.0", FakeForgejo.TOKEN).put("app-1.0-all.jar", file("app.jar", "a rebuilt jar")))
                .isInstanceOf(IOException.class)
                .hasMessage("app-1.0-all.jar is already in v1.0 with other bytes; raise the version in jk.toml");
        assertThat(fake.downloads).hasValue(0);
        assertThat(fake.text("v1.0", "app-1.0-all.jar")).isEqualTo("fat jar");
    }

    @Test
    void publishing_flips_the_draft_once() throws Exception {
        fake.release("v1.0", true);

        assertThat(fake.store("v1.0", FakeForgejo.TOKEN).publish()).isEqualTo("published v1.0");
        assertThat(fake.releases.getFirst().draft).isFalse();
        assertThat(fake.store("v1.0", FakeForgejo.TOKEN).published()).isTrue();
        int writes = fake.writes.get();
        assertThat(fake.store("v1.0", FakeForgejo.TOKEN).publish()).isEqualTo("v1.0 was already published");
        assertThat(fake.writes).hasValue(writes);
    }

    @Test
    void a_published_release_takes_no_new_attachment() throws Exception {
        FakeForgejo.Release out = fake.release("v1.0", false);
        fake.asset(out, "app-1.0-all.jar", "fat jar");
        ReleaseForgejo store = fake.store("v1.0", FakeForgejo.TOKEN);

        assertThat(store.put("app-1.0-all.jar", file("app.jar", "fat jar"))).isEqualTo(ReleaseStore.Put.EXISTING);
        assertThatThrownBy(() -> store.put("SHA256SUMS", "sums".getBytes(StandardCharsets.UTF_8)))
                .hasMessage("v1.0 is already published without SHA256SUMS; raise the version");
        assertThat(fake.writes).hasValue(0);
    }

    @Test
    void a_refusal_names_what_the_token_needs_and_never_shows_it() {
        fake.refuse = 403;
        assertThatThrownBy(() -> fake.store("v1.0", FakeForgejo.TOKEN).put("SHA256SUMS", new byte[] {1}))
                .hasMessage(ReleaseForgejo.SCOPE);
        assertThat(ReleaseForgejo.SCOPE).hasSizeLessThanOrEqualTo(78);

        fake.refuse = 404;
        assertThatThrownBy(() -> fake.store("v1.0", FakeForgejo.TOKEN).published())
                .hasMessage("Forgejo shows no repository acme/app to this token");

        fake.refuse = 0;
        assertThatThrownBy(() -> fake.store("v1.0", "wrong-token-entirely").published())
                .hasMessage("Forgejo refused the token; run jk auth login forgejo or set FORGEJO_TOKEN")
                .hasMessageNotContaining("wrong-token-entirely");
    }

    @Test
    void a_draft_attachment_downloads_by_its_uuid() throws Exception {
        FakeForgejo.Release draft = fake.release("v1.0", true);
        fake.asset(draft, "app-1.0-linux-aarch64", "arm ELF");
        ReleaseForgejo store = fake.store("v1.0", FakeForgejo.TOKEN);
        Path file = tmp.resolve("target/staged/app-1.0-linux-aarch64");

        assertThat(store.fetch("app-1.0-linux-aarch64", file))
                .contains(new ReleaseStore.Held(7, Hashing.sha256Hex("arm ELF")));
        assertThat(file).hasContent("arm ELF");
        assertThat(fake.downloads).hasValue(1);
        assertThat(store.fetch("app-1.0-linux-s390x", tmp.resolve("absent"))).isEmpty();
        assertThat(tmp.resolve("absent")).doesNotExist();
    }
}
