// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Hashing;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A second engine pruning the same cache root can unlink a blob between {@code restore}'s
 * presence check and the copy that reads it, or while the copy reads it. That must degrade to a
 * cache miss, not escape as a build failure, and leave none of the record's outputs half laid. An
 * unlink while the copy holds the blob open lets it finish on POSIX and on Windows (Java opens with
 * delete sharing); a read that fails or comes up short fails the digest check, which {@link
 * ActionCacheVerifiedRestoreTest} covers.
 */
class ActionCacheMissingBlobRestoreTest {

    @AfterEach
    void reset() {
        SessionContext.reset();
    }

    @Test
    void restore_reports_a_miss_when_a_blob_vanishes_mid_copy(@TempDir Path tmp) throws Exception {
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        SessionContext.install(Session.defaults().withCacheDir(cache));

        Path classes = Files.createDirectories(tmp.resolve("classes"));
        // The CAS is planted under the restore target so restore's own clear step unlinks the
        // blob after the presence check and before the copy opens it — the interleaving a
        // concurrent prune produces, reached without a second process.
        Cas cas = new Cas(classes.resolve("cas"));
        ActionCache ac = new ActionCache(cas, cache.resolve("actions"));

        Path source = tmp.resolve("A.class");
        Files.writeString(source, "class-bytes");
        String sha = Hashing.sha256Hex(source);
        cas.putFile(source, sha);
        var record = ac.storeWithOutputs("compile-main", "key", Map.of(), Map.of("A.class", sha));

        assertThat(ac.restore(record, classes))
                .as("a blob unlinked mid-restore is a cache miss, not a NoSuchFileException")
                .isFalse();
        assertThat(classes.resolve("A.class"))
                .as("a failed restore leaves no half-copied output for the fallback run")
                .doesNotExist();
    }

    /** An output already laid down goes with the restore when a later blob has vanished. */
    @Test
    void a_tree_restore_cut_short_after_its_first_output_leaves_an_empty_tree(@TempDir Path tmp) throws Exception {
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        SessionContext.install(Session.defaults().withCacheDir(cache));
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        // A.class's blob survives in the store CAS; B.class's sits under the target, so the
        // restore's clear step takes it after the presence check and after A.class is copied.
        Cas vanishing = new Cas(classes.resolve("cas"));
        Cas store = new Cas(tmp.resolve("store"));
        ActionCache ac = new ActionCache(vanishing, cache.resolve("actions"), store);
        String shaA = put(store, tmp.resolve("A.class"), "a-bytes");
        String shaB = put(vanishing, tmp.resolve("B.class"), "b-bytes");
        ac.storeWithOutputs("compile-main", "key", Map.of(), Map.of("A.class", shaA, "B.class", shaB));
        var record = ac.lookup("key").orElseThrow();

        assertThat(ac.restore(record, classes)).isFalse();
        try (var left = Files.list(classes)) {
            assertThat(left).as("the fallback run starts from an empty tree").isEmpty();
        }
        assertThat(ac.restore(ac.lookup("key").orElseThrow(), classes))
                .as("the same record stays a miss, never a throw")
                .isFalse();
    }

    /** An artifact restore cut short removes the outputs it laid and leaves the rest of the directory alone. */
    @Test
    void an_artifact_restore_cut_short_removes_only_its_own_outputs(@TempDir Path tmp) throws Exception {
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        SessionContext.install(Session.defaults().withCacheDir(cache));
        Path target = Files.createDirectories(tmp.resolve("target"));
        Files.writeString(target.resolve("unrelated.txt"), "not the record's");
        Cas vanishing = new Cas(target.resolve("lib").resolve("cas"));
        Cas store = new Cas(tmp.resolve("store"));
        ActionCache ac = new ActionCache(vanishing, cache.resolve("actions"), store);
        String shaA = put(store, tmp.resolve("a.jar"), "a-jar");
        String shaB = put(vanishing, tmp.resolve("b.jar"), "b-jar");
        ac.storeWithOutputs("package", "key", Map.of(), Map.of("lib/a.jar", shaA, "lib/b.jar", shaB));
        var record = ac.lookup("key").orElseThrow();

        assertThat(ac.restoreArtifacts(record, target)).isFalse();
        assertThat(target.resolve("lib/a.jar"))
                .as("laid before the miss, then removed")
                .doesNotExist();
        assertThat(target.resolve("lib/b.jar")).doesNotExist();
        assertThat(target.resolve("unrelated.txt")).hasContent("not the record's");
    }

    private static String put(Cas cas, Path source, String content) throws Exception {
        Files.writeString(source, content);
        String sha = Hashing.sha256Hex(source);
        cas.putFile(source, sha);
        return sha;
    }
}
