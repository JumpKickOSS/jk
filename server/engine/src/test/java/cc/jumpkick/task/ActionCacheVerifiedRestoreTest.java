// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.util.Hashing;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Restore must never seed {@link FileHashMemo} with a digest the bytes on disk don't have: a
 * corrupt CAS blob is a cache MISS (fall through to a real run), and a post-restore in-place
 * rewrite — even one landing in the same millisecond tick at the same size — voids the seed.
 */
class ActionCacheVerifiedRestoreTest {

    @AfterEach
    void reset() {
        FileHashMemo.clearThreadCache();
        SessionContext.reset();
    }

    @Test
    void corrupt_blob_fails_tree_restore_and_drops_the_blob(@TempDir Path tmp) throws Exception {
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        SessionContext.install(Session.defaults().withCacheDir(cache));
        Cas cas = new Cas(tmp.resolve("store"));
        ActionCache ac = new ActionCache(cas, cache.resolve("actions"));

        Path classes = Files.createDirectories(tmp.resolve("classes"));
        Files.writeString(classes.resolve("A.class"), "good-bytes");
        var rec = ac.store("compile-main", "key", Map.of(), classes);
        String sha = rec.outputs().values().iterator().next();

        // Corrupt the blob at rest (truncation / bit rot), then clean + restore.
        Files.writeString(cas.pathFor(sha), "corrupt");
        Files.delete(classes.resolve("A.class"));

        assertThat(ac.restore(rec, classes))
                .as("restore of a corrupt blob must be a miss, not a silent wrong-bytes hit")
                .isFalse();
        try (var walk = Files.walk(classes)) {
            assertThat(walk.filter(Files::isRegularFile).count())
                    .as("failed restore leaves an empty output dir for the fallback compile")
                    .isZero();
        }
        assertThat(Files.exists(cas.pathFor(sha)))
                .as("a blob whose bytes no longer match its name is dropped")
                .isFalse();
        // The memo must not assert the recorded digest for bytes that were never verified.
        FileHashMemo.clearThreadCache();
        Path a = classes.resolve("A.class");
        assertThat(Files.exists(a)).isFalse();
    }

    @Test
    void corrupt_blob_fails_artifact_restore(@TempDir Path tmp) throws Exception {
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        SessionContext.install(Session.defaults().withCacheDir(cache));
        Cas cas = new Cas(tmp.resolve("store"));
        ActionCache ac = new ActionCache(cas, cache.resolve("actions"));

        Path out = Files.createDirectories(tmp.resolve("out"));
        Path jar = out.resolve("lib.jar");
        Files.writeString(jar, "jar-bytes");
        var rec = ac.storeArtifacts("package-jar", "pkg", Map.of(), out, java.util.List.of(jar));
        String sha = rec.outputs().values().iterator().next();

        Files.writeString(cas.pathFor(sha), "corrupt!!");
        Files.delete(jar);

        assertThat(ac.restoreArtifacts(rec, out)).isFalse();
        assertThat(Files.exists(jar)).as("a mismatching restore target is deleted").isFalse();
    }

    @Test
    void same_size_same_millis_rewrite_voids_the_restore_seed(@TempDir Path tmp) throws Exception {
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        SessionContext.install(Session.defaults().withCacheDir(cache));

        Path f = tmp.resolve("A.class");
        Files.writeString(f, "AAAA");
        // Pin a nanosecond-precise mtime, then seed the memo the way restore does.
        Instant base = Instant.now().minusSeconds(10);
        Files.setLastModifiedTime(f, FileTime.from(base.plusNanos(123_456)));
        String seeded = Hashing.sha256Hex("AAAA".getBytes(StandardCharsets.UTF_8));
        FileHashMemo.rememberContent(f, seeded);

        // Compiler-style in-place rewrite: same byte count, same millisecond tick — only the
        // nanosecond stamp differs (a real rewrite always moves the nano clock).
        Files.writeString(f, "BBBB");
        Files.setLastModifiedTime(f, FileTime.from(base.plusNanos(654_321)));

        // Same thread: the nano-keyed thread cache must not alias the old entry.
        String rehash = FileHashMemo.contentHash(f);
        assertThat(rehash).isEqualTo(Hashing.sha256Hex("BBBB".getBytes(StandardCharsets.UTF_8)));

        // Fresh thread view (disk memo only): the seeded nano stamp no longer matches.
        FileHashMemo.clearThreadCache();
        assertThat(FileHashMemo.contentHash(f))
                .isEqualTo(Hashing.sha256Hex("BBBB".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void untouched_restore_seed_is_still_trusted_without_settling(@TempDir Path tmp) throws Exception {
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        SessionContext.install(Session.defaults().withCacheDir(cache));

        Path f = tmp.resolve("B.class");
        Files.writeString(f, "CCCC"); // fresh mtime — inside the settle window
        String sha = Hashing.sha256Hex("CCCC".getBytes(StandardCharsets.UTF_8));
        FileHashMemo.rememberContent(f, sha);

        FileHashMemo.clearThreadCache();
        FileHashMemo.resetStats();
        assertThat(FileHashMemo.contentHash(f)).isEqualTo(sha);
        assertThat(FileHashMemo.contentReads())
                .as("an unmodified seed serves from the memo even before settle")
                .isZero();
    }
}
