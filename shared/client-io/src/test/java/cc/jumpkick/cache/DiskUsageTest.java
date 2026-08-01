// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiskUsageTest {

    @Test
    void exclusive_does_not_double_count_hardlinked_cas_and_repos(@TempDir Path dir) throws IOException {
        Assumptions.assumeTrue(probeHardLink(dir), "hard links required");

        Path casBlob = dir.resolve("sha256/ab/cd/blob");
        Files.createDirectories(casBlob.getParent());
        byte[] payload = new byte[10_000];
        Files.write(casBlob, payload);

        Path repoJar = dir.resolve("repos/central/g/a/1/a.jar");
        Files.createDirectories(repoJar.getParent());
        Files.createLink(repoJar, casBlob);
        Files.writeString(Path.of(repoJar + ".sha256"), "x".repeat(64));

        DiskUsage.Stats[] parts = DiskUsage.exclusive(dir.resolve("sha256"), dir.resolve("repos"));
        assertThat(parts[0].files()).isEqualTo(1);
        assertThat(parts[0].bytes()).isEqualTo(payload.length);
        // Repo: jar hard-link + sidecar — two entries, but jar bytes already claimed by CAS.
        assertThat(parts[1].files()).isEqualTo(2);
        assertThat(parts[1].bytes()).isEqualTo(64); // sidecar only
        assertThat(DiskUsage.totalBytes(parts)).isEqualTo(payload.length + 64);
    }

    @Test
    void of_counts_unique_keys_within_one_tree(@TempDir Path dir) throws IOException {
        Assumptions.assumeTrue(probeHardLink(dir), "hard links required");
        Path a = dir.resolve("a");
        Path b = dir.resolve("b");
        Files.writeString(a, "same-bytes-here");
        Files.createLink(b, a);

        DiskUsage.Stats s = DiskUsage.of(dir);
        assertThat(s.files()).isEqualTo(2);
        assertThat(s.bytes()).isEqualTo(Files.size(a));
    }

    private static boolean probeHardLink(Path dir) throws IOException {
        Path x = dir.resolve(".hl-x");
        Path y = dir.resolve(".hl-y");
        Files.writeString(x, "z");
        try {
            Files.createLink(y, x);
            return true;
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
            return false;
        } finally {
            Files.deleteIfExists(y);
            Files.deleteIfExists(x);
        }
    }
}
