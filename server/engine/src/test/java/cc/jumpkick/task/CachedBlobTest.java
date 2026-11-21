// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CachedBlobTest {

    @AfterEach
    void reset() {
        FileHashMemo.reset();
    }

    @Test
    void a_missing_path_or_a_directory_is_no_blob(@TempDir Path tmp) throws IOException {
        assertThat(CachedBlob.at(tmp.resolve("absent"))).isNull();
        assertThat(CachedBlob.at(Files.createDirectories(tmp.resolve("dir")))).isNull();
    }

    @Test
    void a_blob_is_laid_down_when_its_bytes_hash_to_its_digest(@TempDir Path tmp) throws IOException {
        for (int size : new int[] {5, (int) CachedBlob.CLONE_MIN_BYTES + 1}) {
            byte[] bytes = new byte[size];
            Path file = Files.write(tmp.resolve("blob-" + size), bytes);
            CachedBlob blob = Objects.requireNonNull(CachedBlob.at(file));
            Path target = tmp.resolve("out-" + size);

            assertThat(blob.size()).isEqualTo(size);
            assertThat(blob.copyTo(target, Hashing.sha256Hex(file))).isEqualTo(CachedBlob.Copy.LAID);
            assertThat(Files.readAllBytes(target)).isEqualTo(bytes);
        }
    }

    @Test
    void bytes_that_do_not_hash_to_the_digest_are_corrupt_and_leave_no_target(@TempDir Path tmp) throws IOException {
        String wrong = Hashing.sha256Hex(Files.writeString(tmp.resolve("other"), "other"));
        for (int size : new int[] {5, (int) CachedBlob.CLONE_MIN_BYTES + 1}) {
            Path file = Files.write(tmp.resolve("blob-" + size), new byte[size]);
            Path target = tmp.resolve("out-" + size);

            assertThat(Objects.requireNonNull(CachedBlob.at(file)).copyTo(target, wrong))
                    .isEqualTo(CachedBlob.Copy.CORRUPT);
            assertThat(target).doesNotExist();
            assertThat(file).as("the caller decides what to drop").exists();
        }
    }

    @Test
    void a_blob_pruned_after_it_was_resolved_is_vanished(@TempDir Path tmp) throws IOException {
        for (int size : new int[] {5, (int) CachedBlob.CLONE_MIN_BYTES + 1}) {
            Path file = Files.write(tmp.resolve("blob-" + size), new byte[size]);
            String sha = Hashing.sha256Hex(file);
            CachedBlob blob = Objects.requireNonNull(CachedBlob.at(file));
            Files.delete(file);
            Path target = tmp.resolve("out-" + size);

            assertThat(blob.copyTo(target, sha)).isEqualTo(CachedBlob.Copy.VANISHED);
            assertThat(target).doesNotExist();
        }
    }

    @Test
    void a_target_is_identical_only_at_the_same_size_and_a_settled_digest(@TempDir Path tmp) throws IOException {
        Path file = Files.writeString(tmp.resolve("blob"), "bytes");
        String sha = Hashing.sha256Hex(file);
        CachedBlob blob = Objects.requireNonNull(CachedBlob.at(file));
        Path target = Files.writeString(tmp.resolve("target"), "bytes");
        Path longer = Files.writeString(tmp.resolve("longer"), "bytes!");
        FileHashMemo.rememberContent(longer, sha);

        assertThat(blob.identicalAt(tmp.resolve("absent"), sha)).isFalse();
        assertThat(blob.identicalAt(longer, sha))
                .as("a size mismatch never hashes")
                .isFalse();
        assertThat(blob.identicalAt(target, sha)).isTrue();
    }
}
