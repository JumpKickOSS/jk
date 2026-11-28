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

/** A release in an object store: create-only files that resume, and latest.json by compare-and-swap. */
class ReleaseBucketTest {

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

    @Test
    void a_file_goes_up_once_and_a_rerun_with_the_same_bytes_leaves_it() throws Exception {
        ReleaseBucket bucket = fake.bucket("app/", "1.0");
        Path jar = Files.writeString(tmp.resolve("app-1.0-all.jar"), "jar bytes");

        assertThat(bucket.put("app-1.0-all.jar", jar)).isEqualTo(ReleaseStore.Put.UPLOADED);
        assertThat(fake.text("app/1.0/app-1.0-all.jar")).isEqualTo("jar bytes");
        assertThat(bucket.put("app-1.0-all.jar", jar)).isEqualTo(ReleaseStore.Put.EXISTING);
        assertThat(bucket.present("app-1.0-all.jar")).isTrue();
        assertThat(bucket.present("app-1.0.zip")).isFalse();
        assertThat(bucket.held("app-1.0-all.jar")).contains(new ReleaseStore.Held(9, Hashing.sha256Hex("jar bytes")));
        assertThat(fake.unguarded).hasValue(0);
    }

    @Test
    void other_bytes_under_a_released_name_stop_the_run() throws Exception {
        ReleaseBucket bucket = fake.bucket("app/", "1.0");
        bucket.put("index.json", "{}".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> bucket.put("index.json", "{\"x\":1}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IOException.class)
                .hasMessageEndingWith("index.json is already there with other bytes; raise the version in jk.toml");
        assertThat(fake.text("app/1.0/index.json")).isEqualTo("{}");
    }

    @Test
    void latest_moves_forward_by_compare_and_swap_and_never_back() throws Exception {
        assertThat(fake.bucket("app/", "1.0").publishLatest()).isEqualTo("1.0");
        assertThat(fake.text("app/latest.json")).isEqualTo("{\"version\":\"1.0\",\"index\":\"1.0/index.json\"}\n");

        assertThat(fake.bucket("app/", "1.1").publishLatest()).isEqualTo("1.1");
        assertThat(fake.text("app/latest.json")).contains("\"1.1\"");

        assertThat(fake.bucket("app/", "1.0.1").publishLatest())
                .as("a patch of an older line")
                .isEqualTo("1.1");
        assertThat(fake.text("app/latest.json")).contains("\"1.1\"");
        assertThat(fake.bucket("app/", "1.1").publishLatest()).as("a re-run").isEqualTo("1.1");
        assertThat(fake.unguarded).hasValue(0);
    }

    @Test
    void a_staged_file_downloads_into_place_and_an_absent_one_writes_nothing() throws Exception {
        ReleaseBucket bucket = fake.bucket("app/", "1.0");
        bucket.put("app-1.0-linux-aarch64", "arm ELF".getBytes(StandardCharsets.UTF_8));
        Path file = tmp.resolve("target/staged/app-1.0-linux-aarch64");

        assertThat(bucket.fetch("app-1.0-linux-aarch64", file))
                .contains(new ReleaseStore.Held(7, Hashing.sha256Hex("arm ELF")));
        assertThat(file).hasContent("arm ELF");
        assertThat(bucket.fetch("app-1.0-linux-s390x", tmp.resolve("absent"))).isEmpty();
        assertThat(tmp.resolve("absent")).doesNotExist();
    }
}
