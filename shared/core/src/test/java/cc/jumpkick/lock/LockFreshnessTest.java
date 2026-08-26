// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LockFreshnessTest {

    @Test
    void needsRefresh_when_lock_missing(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "g"
                name = "n"
                version = "1"
                """);
        assertThat(LockFreshness.needsRefresh(dir)).isTrue();
        // Digest-only: no lock file is not "stale". Invisible freshen must use needsRefresh
        // (LockCascade) or it will no-op and never write the first lock.
        assertThat(LockFreshness.isStale(dir, dir.resolve("jk-lock.toml"))).isFalse();
    }

    @Test
    void digest_mismatch_is_stale(@TempDir Path dir) throws Exception {
        Path toml = dir.resolve("jk.toml");
        Files.writeString(toml, """
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
        Files.writeString(dir.resolve("jk.toml"), """
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
        Files.writeString(dir.resolve("jk.toml"), """
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
        Files.writeString(toml, """
                group = "g"
                name = "n"
                version = "1"
                """);
        Path lockFile = dir.resolve("jk-lock.toml");
        String digest = LockManifestDigest.compute(dir);
        Lockfile lock = Lockfile.empty("test").withManifestsSha256(digest);
        Files.writeString(lockFile, LockfileWriter.render(lock));
        // Mtime of toml newer is irrelevant — only digest matters.
        Files.setLastModifiedTime(toml, FileTime.fromMillis(System.currentTimeMillis() + 60_000));

        assertThat(LockFreshness.isValidDigest(digest)).isTrue();
        assertThat(LockFreshness.isStale(dir, lockFile)).isFalse();
        assertThat(LockFreshness.needsRefresh(dir)).isFalse();
    }

    @Test
    void writer_stamps_digest_on_write(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
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
    void jk_libs_toml_edit_flips_staleness(@TempDir Path dir) throws Exception {
        // The workspace catalog layer changes short-name -> GA resolution, so a pin edit must
        // stale the lock exactly like a manifest edit.
        Files.writeString(dir.resolve("jk.toml"), """
                group = "g"
                name = "n"
                version = "1"
                """);
        Path lockFile = dir.resolve("jk-lock.toml");
        LockfileWriter.write(Lockfile.empty("test"), lockFile);
        assertThat(LockFreshness.isStale(dir, lockFile)).isFalse();

        Files.writeString(dir.resolve("jk-libs.toml"), """
                [libraries]
                foo = "com.example:foo"
                """);
        assertThat(LockFreshness.isStale(dir, lockFile)).isTrue();

        // Re-stamp with the pins present, then edit the pin: stale again.
        LockfileWriter.write(Lockfile.empty("test"), lockFile);
        assertThat(LockFreshness.isStale(dir, lockFile)).isFalse();
        Files.writeString(dir.resolve("jk-libs.toml"), """
                [libraries]
                foo = "org.elsewhere:foo"
                """);
        assertThat(LockFreshness.isStale(dir, lockFile)).isTrue();
    }

    @Test
    void pinless_lock_over_a_native_manifest_is_stale(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "g"
                name = "n"
                version = "1"

                [native]
                metadata-repository = "=1.1.4"
                """);
        Path lockFile = dir.resolve("jk-lock.toml");
        // Writer stamps a clean digest; the [native] pin is only stamped by a real re-lock.
        LockfileWriter.write(Lockfile.empty("test"), lockFile);
        assertThat(LockfileReader.read(lockFile).nativeMetadata()).isNull();

        assertThat(LockFreshness.isStale(dir, lockFile)).isTrue();
    }

    @Test
    void pinless_lock_over_a_non_native_manifest_follows_the_digest_rule(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "g"
                name = "n"
                version = "1"
                """);
        Path lockFile = dir.resolve("jk-lock.toml");
        LockfileWriter.write(Lockfile.empty("test"), lockFile);

        assertThat(LockFreshness.isStale(dir, lockFile)).isFalse();
    }

    @Test
    void pinned_lock_over_a_native_manifest_follows_the_digest_rule(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "g"
                name = "n"
                version = "1"

                [native]
                metadata-repository = "=1.1.4"
                """);
        Path lockFile = dir.resolve("jk-lock.toml");
        Lockfile pinned = Lockfile.empty("test")
                .withNativeMetadata(new Lockfile.NativeMetadata("1.1.4", "sha256:" + "0".repeat(64)));
        LockfileWriter.write(pinned, lockFile);
        assertThat(LockfileReader.read(lockFile).nativeMetadata()).isNotNull();

        assertThat(LockFreshness.isStale(dir, lockFile)).isFalse();

        Files.writeString(dir.resolve("jk-libs.toml"), """
                [libraries]
                foo = "com.example:foo"
                """);
        assertThat(LockFreshness.isStale(dir, lockFile)).isTrue();
    }

    @Test
    void conflicting_native_selectors_are_stale(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "g"
                name    = "n"
                version = "1"

                [workspace]
                modules = ["a", "b"]
                """);
        Files.createDirectories(dir.resolve("a"));
        Files.createDirectories(dir.resolve("b"));
        Files.writeString(dir.resolve("a/jk.toml"), "name = \"a\"\n\n[native]\nmetadata-repository = \"=1.1.4\"\n");
        Files.writeString(dir.resolve("b/jk.toml"), "name = \"b\"\n\n[native]\nmetadata-repository = \"=2.0.0\"\n");
        Path lockFile = dir.resolve("jk-lock.toml");
        LockfileWriter.write(Lockfile.empty("test"), lockFile);

        // Fail closed: the re-lock surfaces LockNativePin's conflict message.
        assertThat(LockFreshness.isStale(dir, lockFile)).isTrue();
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
