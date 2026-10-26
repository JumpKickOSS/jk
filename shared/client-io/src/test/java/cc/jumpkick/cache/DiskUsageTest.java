// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
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
        Files.writeString(repoJar.resolveSibling("a.jk"), "x".repeat(64));

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

    /**
     * The {@code fileKey}-less path only runs on Windows, so these two drive {@link
     * DiskUsage.SameFileKeys} directly — on Linux a walk would always take the {@code unix:} route.
     */
    @Test
    void zero_length_files_are_not_link_deduplicated(@TempDir Path dir) throws IOException {
        Assumptions.assumeTrue(probeHardLink(dir), "hard links required");
        Path a = Files.createFile(dir.resolve("a"));
        Path b = Files.createLink(dir.resolve("b"), a);

        DiskUsage.SameFileKeys keys = new DiskUsage.SameFileKeys();
        // Distinct identities, so no isSameFile call is ever made for an empty file. Both still
        // contribute zero bytes, so the accounting is unaffected either way.
        assertThat(keys.identity(a, 0, mtime(a))).isNotEqualTo(keys.identity(b, 0, mtime(b)));
    }

    @Test
    void link_candidate_buckets_are_bounded(@TempDir Path dir) throws IOException {
        Assumptions.assumeTrue(probeHardLink(dir), "hard links required");
        String payload = "same-size";
        DiskUsage.SameFileKeys keys = new DiskUsage.SameFileKeys();
        Path first = sameStamp(Files.writeString(dir.resolve("f0"), payload));
        Object firstId = keys.identity(first, payload.length(), mtime(first));
        for (int i = 1; i < DiskUsage.SameFileKeys.MAX_LINK_CANDIDATES; i++) {
            Path f = sameStamp(Files.writeString(dir.resolve("f" + i), payload));
            keys.identity(f, payload.length(), mtime(f));
        }
        // Inside the bucket a link still resolves to the file it shares an inode with.
        Path insideLink = Files.createLink(dir.resolve("inside-link"), first);
        assertThat(keys.identity(insideLink, payload.length(), mtime(insideLink)))
                .isEqualTo(firstId);

        // Past the cap the bucket stops growing, so a later link is over-counted rather than
        // costing another full scan — bounded error for bounded work.
        Path overflow = sameStamp(Files.writeString(dir.resolve("overflow"), payload));
        Object overflowId = keys.identity(overflow, payload.length(), mtime(overflow));
        Path overflowLink = Files.createLink(dir.resolve("overflow-link"), overflow);
        assertThat(keys.identity(overflowLink, payload.length(), mtime(overflowLink)))
                .isNotEqualTo(overflowId);
    }

    private static final FileTime STAMP = FileTime.fromMillis(1_700_000_000_000L);

    private static Path sameStamp(Path p) throws IOException {
        Files.setLastModifiedTime(p, STAMP);
        return p;
    }

    private static FileTime mtime(Path p) throws IOException {
        return Files.getLastModifiedTime(p);
    }

    /** Files that merely share a size are never candidates for each other; a link still finds its twin. */
    @Test
    void same_size_files_with_other_timestamps_do_not_crowd_out_a_link(@TempDir Path dir) throws IOException {
        Assumptions.assumeTrue(probeHardLink(dir), "hard links required");
        DiskUsage.SameFileKeys keys = new DiskUsage.SameFileKeys();
        Path target = sameStamp(Files.writeString(dir.resolve("target"), "payload"));
        Object targetId = keys.identity(target, 7, mtime(target));
        for (int i = 0; i < DiskUsage.SameFileKeys.MAX_LINK_CANDIDATES * 3; i++) {
            Path other = Files.writeString(dir.resolve("other" + i), "payload");
            Files.setLastModifiedTime(other, FileTime.fromMillis(1_600_000_000_000L + i));
            keys.identity(other, 7, mtime(other));
        }
        Path link = Files.createLink(dir.resolve("link"), target);
        assertThat(keys.identity(link, 7, mtime(link))).isEqualTo(targetId);
    }

    private static boolean probeHardLink(Path dir) throws IOException {
        Path x = dir.resolve(".hl-x");
        Path y = dir.resolve(".hl-y");
        Files.writeString(x, "z");
        try {
            Files.createLink(y, x);
            return true;
        } catch (UnsupportedOperationException | FileSystemException e) {
            return false;
        } finally {
            Files.deleteIfExists(y);
            Files.deleteIfExists(x);
        }
    }
}
