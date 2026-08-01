// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LockFreshnessTest {

    @Test
    void needsRefresh_when_lock_missing(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("jk.toml"),
                """
                [project]
                group = "g"
                name = "n"
                version = "1"
                """);
        assertThat(LockFreshness.needsRefresh(dir)).isTrue();
    }

    @Test
    void digest_mismatch_is_stale(@TempDir Path dir) throws Exception {
        Path toml = dir.resolve("jk.toml");
        Files.writeString(
                toml,
                """
                [project]
                group = "g"
                name = "n"
                version = "1"
                """);
        Path lockFile = dir.resolve("jk-lock.toml");
        Lockfile lock = Lockfile.empty("test").withManifestsSha256("0".repeat(64));
        // Bypass LockfileWriter.stamp so we keep the wrong digest.
        Files.writeString(lockFile, LockfileWriter.render(lock));

        assertThat(LockFreshness.isStale(dir, lockFile)).isTrue();
        assertThat(LockFreshness.needsRefresh(dir)).isTrue();
    }

    @Test
    void missing_digest_is_always_stale(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("jk.toml"),
                """
                [project]
                group = "g"
                name = "n"
                version = "1"
                """);
        Path lockFile = dir.resolve("jk-lock.toml");
        // Legacy lock with no manifests-sha256 — even if mtime is newer on the lock.
        Lockfile lock = Lockfile.empty("test"); // null digest
        Files.writeString(lockFile, LockfileWriter.render(lock));
        // render may omit the field; Writer.write would stamp — we use render only.
        assertThat(lock.manifestsSha256()).isNull();
        assertThat(Files.readString(lockFile)).doesNotContain("manifests-sha256");

        assertThat(LockFreshness.isStale(dir, lockFile)).isTrue();
        assertThat(LockFreshness.needsRefresh(dir)).isTrue();
    }

    @Test
    void invalid_digest_is_stale(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("jk.toml"),
                """
                [project]
                group = "g"
                name = "n"
                version = "1"
                """);
        Path lockFile = dir.resolve("jk-lock.toml");
        Lockfile lock = Lockfile.empty("test").withManifestsSha256("not-a-sha");
        Files.writeString(lockFile, LockfileWriter.render(lock));
        assertThat(LockFreshness.isValidDigest("not-a-sha")).isFalse();
        assertThat(LockFreshness.isStale(dir, lockFile)).isTrue();
    }

    @Test
    void matching_digest_is_fresh(@TempDir Path dir) throws Exception {
        Path toml = dir.resolve("jk.toml");
        Files.writeString(
                toml,
                """
                [project]
                group = "g"
                name = "n"
                version = "1"
                """);
        Path lockFile = dir.resolve("jk-lock.toml");
        String digest = LockManifestDigest.compute(dir);
        Lockfile lock = Lockfile.empty("test").withManifestsSha256(digest);
        Files.writeString(lockFile, LockfileWriter.render(lock));
        // Mtime of toml newer is irrelevant — only digest matters.
        Files.setLastModifiedTime(
                toml, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 60_000));

        assertThat(LockFreshness.isValidDigest(digest)).isTrue();
        assertThat(LockFreshness.isStale(dir, lockFile)).isFalse();
        assertThat(LockFreshness.needsRefresh(dir)).isFalse();
    }

    @Test
    void writer_stamps_digest_on_write(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("jk.toml"),
                """
                [project]
                group = "g"
                name = "n"
                version = "1"
                """);
        Path lockFile = dir.resolve("jk-lock.toml");
        LockfileWriter.write(Lockfile.empty("test"), lockFile);
        Lockfile back = LockfileReader.read(lockFile);
        assertThat(back.manifestsSha256()).isEqualTo(LockManifestDigest.compute(dir));
        assertThat(LockFreshness.needsRefresh(dir)).isFalse();
    }

    @Test
    void isValidDigest_accepts_hex64_only() {
        assertThat(LockFreshness.isValidDigest(null)).isFalse();
        assertThat(LockFreshness.isValidDigest("")).isFalse();
        assertThat(LockFreshness.isValidDigest("abc")).isFalse();
        assertThat(LockFreshness.isValidDigest("0".repeat(64))).isTrue();
        assertThat(LockFreshness.isValidDigest("a".repeat(63) + "G")).isFalse();
        assertThat(LockFreshness.isValidDigest("AbCdEf0123456789".repeat(4))).isTrue();
    }
}
