// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LockManifestDigestTest {

    @Test
    void hashParts_is_order_independent_by_sorting_keys() {
        Map<String, byte[]> a = new LinkedHashMap<>();
        a.put("b.toml", "x".getBytes());
        a.put("a.toml", "y".getBytes());
        Map<String, byte[]> b = new LinkedHashMap<>();
        b.put("a.toml", "y".getBytes());
        b.put("b.toml", "x".getBytes());
        assertThat(LockManifestDigest.hashParts(a)).isEqualTo(LockManifestDigest.hashParts(b));
    }

    @Test
    void hashParts_changes_when_content_changes() {
        Map<String, byte[]> a = Map.of("jk.toml", "name = \"a\"\n".getBytes());
        Map<String, byte[]> b = Map.of("jk.toml", "name = \"b\"\n".getBytes());
        assertThat(LockManifestDigest.hashParts(a)).isNotEqualTo(LockManifestDigest.hashParts(b));
    }

    @Test
    void compute_stable_for_standalone_project(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name = "app"
                version = "1.0.0"
                """);
        String d1 = LockManifestDigest.compute(dir);
        String d2 = LockManifestDigest.compute(dir);
        assertThat(d1).isEqualTo(d2).hasSize(64);
    }

    @Test
    void crlf_manifest_hashes_like_lf(@TempDir Path dir) throws Exception {
        // JK-1357: autocrlf checkouts must not read as permanently stale.
        String lf = """
                [project]
                group = "com.example"
                name = "app"
                version = "1.0.0"
                """;
        Path toml = dir.resolve("jk.toml");
        Files.writeString(toml, lf);
        String lfDigest = LockManifestDigest.compute(dir);
        Files.writeString(toml, lf.replace("\n", "\r\n"));
        assertThat(LockManifestDigest.compute(dir)).isEqualTo(lfDigest);
    }

    @Test
    void path_dep_manifest_feeds_the_digest(@TempDir Path dir) throws Exception {
        // JK-1357: a path-source dep's jk.toml feeds the lock, so editing it flips the digest.
        Path app = dir.resolve("app");
        Path lib = dir.resolve("lib");
        Files.createDirectories(app);
        Files.createDirectories(lib);
        Files.writeString(app.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name = "app"
                version = "1.0.0"

                [dependencies]
                lib = { path = "../lib" }
                """);
        Files.writeString(lib.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name = "lib"
                version = "1.0.0"
                """);
        String before = LockManifestDigest.compute(app);
        Files.writeString(lib.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name = "lib"
                version = "2.0.0"
                """);
        assertThat(LockManifestDigest.compute(app)).isNotEqualTo(before);
    }

    @Test
    void unreadable_manifest_fails_loud(@TempDir Path dir) throws Exception {
        // JK-1357: never silently produce an unstamped (permanently stale) digest — an I/O
        // failure reading a contributing manifest must surface, not vanish into a missing stamp.
        org.junit.jupiter.api.Assumptions.assumeTrue(
                dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        org.junit.jupiter.api.Assumptions.assumeTrue(!"root".equals(System.getProperty("user.name")));
        Path toml = dir.resolve("jk.toml");
        Files.writeString(toml, """
                [project]
                group = "com.example"
                name = "app"
                version = "1.0.0"
                """);
        var none = PosixFilePermissions.fromString("---------");
        Files.setPosixFilePermissions(toml, none);
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> LockManifestDigest.compute(dir))
                    .isInstanceOf(IOException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> LockfileWriter.write(Lockfile.empty("test"), dir.resolve("jk-lock.toml")))
                    .isInstanceOf(IOException.class);
        } finally {
            Files.setPosixFilePermissions(toml, PosixFilePermissions.fromString("rw-r--r--"));
        }
    }

    @Test
    void manifest_edited_mid_lock_reads_stale(@TempDir Path dir) throws Exception {
        // JK-1357 TOCTOU: the stamp reflects the bytes that fed resolution, not the live files.
        Files.writeString(dir.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name = "app"
                version = "1.0.0"
                """);
        String capturedAtParse = LockManifestDigest.compute(dir);

        // Manifest edited while the (slow) resolution is still running…
        Files.writeString(dir.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name = "app"
                version = "9.9.9"
                """);

        Path lockFile = dir.resolve("jk-lock.toml");
        LockfileWriter.write(Lockfile.empty("test"), lockFile, capturedAtParse);
        assertThat(LockFreshness.isStale(dir, lockFile)).isTrue();
    }

    @Test
    void compute_changes_when_manifest_edited(@TempDir Path dir) throws Exception {
        Path toml = dir.resolve("jk.toml");
        Files.writeString(toml, """
                [project]
                group = "com.example"
                name = "app"
                version = "1.0.0"
                """);
        String before = LockManifestDigest.compute(dir);
        Files.writeString(toml, """
                [project]
                group = "com.example"
                name = "app"
                version = "1.0.1"
                """);
        assertThat(LockManifestDigest.compute(dir)).isNotEqualTo(before);
    }
}
